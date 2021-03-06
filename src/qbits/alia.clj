(ns qbits.alia
  (:require
   [qbits.knit :as knit]
   [qbits.alia.codec :as codec]
   [qbits.alia.utils :as utils]
   [qbits.alia.enum :as enum]
   [qbits.hayt :as hayt]
   [lamina.core :as l]
   [clojure.core.memoize :as memo]
   [clojure.core.async :as async]
   [qbits.alia.cluster-options :as copt])
  (:import
   (com.datastax.driver.core
    BoundStatement
    Cluster
    Cluster$Builder
    PreparedStatement
    Statement
    ResultSet
    ResultSetFuture
    Session
    SimpleStatement
    Statement)
   (com.google.common.util.concurrent
    Futures
    FutureCallback)
   (java.nio ByteBuffer)))

(def ^:dynamic *consistency* nil)

(defmacro with-consistency
  "Binds qbits.alia/*consistency*"
  [consistency & body]
  `(binding [qbits.alia/*consistency* ~consistency]
     ~@body))

(def set-consistency!
  "Sets root value of *consistency*"
  (utils/var-root-setter *consistency*))

(def ^:dynamic *serial-consistency* nil)

(defmacro with-serial-consistency
  "Binds qbits.alia/*serial-consistency*"
  [serial-consistency & body]
  `(binding [qbits.alia/*serial-consistency* ~serial-consistency]
     ~@body))

(def set-serial-consistency!
  "Sets root value of *serial-consistency*"
  (utils/var-root-setter *serial-consistency*))

(def ^:dynamic *session*)

(defmacro with-session
  "Binds qbits.alia/*session*"
  [session & body]
  `(binding [qbits.alia/*session* ~session]
     ~@body))

(def set-session!
  "Sets root value of *session*"
  (utils/var-root-setter *session*))

(def ^:dynamic *executor* (knit/executor :cached))

(def set-executor!
  "Sets root value of *executor*"
  (utils/var-root-setter *executor*))

(defmacro with-executor
  "Binds qbits.alia/*executor*"
  [executor & body]
  `(binding [qbits.alia/*executor* ~executor]
     ~@body))

(def ^:dynamic *keywordize* true)

(def set-keywordize!
  "Sets root value of *keywordize*"
  (utils/var-root-setter *keywordize*))

(def ^:dynamic *hayt-query-fn* (memo/lu hayt/->raw :lu/threshold 100))

(def set-hayt-query-fn!
  "Sets root value of *query-fn*, allowing to control how
   hayt queries are executed , defaults to LU with a threshold of 100"
  (utils/var-root-setter *hayt-query-fn*))

(def ^:dynamic *fetch-size* nil)

(def set-fetch-size!
    (utils/var-root-setter *fetch-size*))

(defn cluster
  "Returns a new com.datastax.driver.core/Cluster instance"
  [hosts & {:as options}]
  (-> (Cluster/builder)
      (copt/set-cluster-options! (assoc options :contact-points hosts))
      .build))

(defn ^Session connect
  "Returns a new com.datastax.driver.core/Session instance. We need to
have this separate in order to allow users to connect to multiple
keyspaces from a single cluster instance"
  ([^Cluster cluster keyspace]
     (.connect cluster (name keyspace)))
  ([^Cluster cluster]
     (.connect cluster)))

(defn shutdown
  "Shutdowns Session or Cluster instance, clearing the underlying
pools/connections"
  ([^java.io.Closeable c]
     (.closeAsync c))
  ([]
     (shutdown *session*)))

(defn ^:private ex->ex-info
  ([^Exception ex data msg]
     (ex-info msg
              (merge {:type ::execute
                      :exception ex}
                     data)
              (.getCause ex)))
  ([ex data]
     (ex->ex-info ex data "Query execution failed")))

(defn prepare
  "Returns a com.datastax.driver.core.PreparedStatement instance to be
used in `execute` after it's been bound with `bind`. Can take a string query or
a Hayt query, in that case they will be compiled with ->raw internaly.
ex: (prepare (select :foo (where {:bar ?})))"
  ([^Session session query]
     (let [^String q (if (map? query)
                       (hayt/->raw query)
                       query)]
       (try
         (.prepare session q)
         (catch Exception ex
           (throw (ex->ex-info ex
                           {:type ::prepare-error
                            :query q}
                           "Query prepare failed"))))))
  ([query]
     (prepare *session* query)))

(defn bind
  "Returns a com.datastax.driver.core.BoundStatement instance to be
  used with `execute`"
  [^PreparedStatement statement values]
  (try
    (.bind statement (to-array (map codec/encode values)))
    (catch Exception ex
      (throw (ex->ex-info ex {:query statement
                              :type ::bind-error
                              :values values}
                          "Query binding failed")))))

(defprotocol PStatement
  (^:no-doc query->statement
    [q values] "Encodes input into a Statement instance"))

(extend-protocol PStatement
  Statement
  (query->statement [q _] q)

  PreparedStatement
  (query->statement [q values]
    (bind q values))

  String
  (query->statement [q _]
    (SimpleStatement. q))

  clojure.lang.IPersistentMap
  (query->statement [q _]
    (query->statement (*hayt-query-fn* q) nil)))

(defn ^:private set-statement-options!
  [^Statement statement routing-key retry-policy tracing? consistency
   serial-consistency fetch-size]
  (when routing-key
    (.setRoutingKey ^SimpleStatement statement
                    ^ByteBuffer routing-key))
  (when retry-policy
    (.setRetryPolicy statement retry-policy))
  (when tracing?
    (.enableTracing statement))
  (when fetch-size
    (.setFetchSize statement fetch-size))
  (when serial-consistency
    (.setSerialConsistencyLevel statement
                                (enum/consistency-levels serial-consistency)))
  (when consistency
    (.setConsistencyLevel statement (enum/consistency-levels consistency))))

(defn ^:private fix-session-arg
  [args]
  (if (instance? Session (first args))
    args
    (conj args *session*)))

(defn execute
  "Executes a query against a session. Returns a collection of rows.
The first argument can be either a Session instance or the query
directly.

So 2 signatures:

 [session query & {:keys [consistency serial-consistency
                          routing-key retry-policy
                          tracing? values fetch-size]
                  :or {executor default-async-executor
                       consistency *consistency*
                       serial-consistency *serial-consistency*
                       fetch-size *fetch-size*}}]

or

 [query & {:keys [consistency serial-consistency routing-key retry-policy
                  tracing? values fetch-size]
                  :or {executor default-async-executor
                       consistency *consistency*
                       serial-consistency *serial-consistency*
                       fetch-size *fetch-size*}}]

If you chose the latter the Session must be bound with
`with-session`.

The query can be a raw string, a PreparedStatement (returned by
`prepare`) with values passed as `:values` that will be bound by
`execute`, BoundStatement (returned by `bind`), or a Hayt query.
"
  [& args]
  (let [[^Session session query & {:keys [consistency serial-consistency
                                          routing-key retry-policy
                                          tracing? keywordize?
                                          fetch-size values]
                                   :or {keywordize? *keywordize*
                                        consistency *consistency*
                                        serial-consistency *serial-consistency*
                                        fetch-size *fetch-size*}}]
        (fix-session-arg args)
        ^Statement statement (query->statement query values)]
    (set-statement-options! statement routing-key retry-policy tracing?
                            consistency serial-consistency fetch-size)
    (try
      (codec/result-set->maps (.execute session statement) keywordize?)
      (catch Exception err
        (throw (ex->ex-info err {:query statement :values values}))))))

(defn execute-async
  "Same as execute, but returns a promise and accepts :success and :error
  handlers, you can also pass :executor for the ResultFuture, it
  defaults to a cachedThreadPool if you don't"
  [& args]
  (let [[^Session session query & {:keys [success error executor consistency
                                          serial-consistency routing-key
                                          retry-policy tracing?
                                          keywordize? fetch-size values]
                                   :or {executor *executor*
                                        keywordize? *keywordize*
                                        consistency *consistency*
                                        serial-consistency *serial-consistency*
                                        fetch-size *fetch-size*}}]
        (fix-session-arg args)
        ^Statement statement (query->statement query values)]
    (set-statement-options! statement routing-key retry-policy tracing?
                            consistency serial-consistency fetch-size)
    (let [^ResultSetFuture rs-future
          (try
            (.executeAsync session statement)
            (catch Exception ex
              (throw (ex->ex-info ex {:query statement :values values}))))
          async-result (l/result-channel)]
      (l/on-realized async-result success error)
      (Futures/addCallback
       rs-future
       (reify FutureCallback
         (onSuccess [_ result]
           (l/success async-result
                      (codec/result-set->maps (.get rs-future) keywordize?)))
         (onFailure [_ ex]
           (l/error async-result
                    (ex->ex-info ex {:query statement :values values}))))
       executor)
      async-result)))

(defn execute-chan
  "Alpha, subject to changes:
  Same as execute, but returns a clojure.core.async/chan that is
  wired to the underlying ResultSetFuture. This means this is usable
  with `go` blocks or `take!`. Exceptions are sent to the
  channel as a value, it's your responsability to handle these how you
  deem appropriate."
  [& args]
  (let [[^Session session query & {:keys [executor consistency serial-consistency
                                          routing-key retry-policy tracing?
                                          keywordize? fetch-size values]
                                   :or {executor *executor*
                                        keywordize? *keywordize*
                                        consistency *consistency*
                                        serial-consistency *serial-consistency*
                                        fetch-size *fetch-size*}}]
        (fix-session-arg args)
        ^Statement statement (query->statement query values)]
    (set-statement-options! statement routing-key retry-policy tracing?
                            consistency serial-consistency fetch-size)
    (let [^ResultSetFuture rs-future (.executeAsync session statement)
          ch (async/chan 1)]
      (Futures/addCallback
       rs-future
       (reify FutureCallback
         (onSuccess [_ result]
           (async/put! ch (codec/result-set->maps (.get rs-future) keywordize?))
           (async/close! ch))
         (onFailure [_ ex]
           (async/put! ch
                       (ex->ex-info ex {:query statement :values values}))
           (async/close! ch)))
       executor)
      ch)))

(defn ^:private lazy-query-
  [session query pred coll opts]
  (lazy-cat coll
            (when query
              (let [coll (apply execute session query opts)]
                (lazy-query- session (pred query coll) pred coll opts)))))

(defn ^:private lazy-query-
  [session query pred coll opts]
  (lazy-cat coll
            (when query
              (let [coll (apply execute session query opts)]
                (lazy-query- session (pred query coll) pred coll opts)))))

(defn lazy-query
  "Takes a query (hayt, raw or prepared) and a query modifier fn (that
receives the last query and last chunk and returns a new query or nil).
The first chunk will be the original query result, then for each
subsequent chunk the query will be the result of last query
modified by the modifier fn unless the fn returns nil,
which would causes the iteration to stop.

You must pass the session as first argument or
use a binding.  It also accepts any `execute` options as trailing
arguments.

2 signatures:
  [session query pred & opts] or [query pred & opts]

ex: (lazy-query (select :items (limit 2) (where {:x (int 1)}))
                  (fn [q coll]
                    (merge q (where {:si (-> coll last :x inc)})))
                  :consistency :quorum :tracing? true)"
  [& args]
  (let [[session query pred & opts]
        (fix-session-arg args)]
    (lazy-query- session query pred [] opts)))
