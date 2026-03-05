(ns chatbot.adapters.persistence.db
  "Database connection and query execution.
   Uses HikariCP for connection pooling."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.connection :as connection]
            [clojure.tools.logging :as log])
  (:import [com.zaxxer.hikari HikariDataSource]))

(defn create-datasource
  "Create a HikariCP datasource from a JDBC URL"
  [jdbc-url]
  (log/info "Creating database connection pool...")
  (let [ds (jdbc/get-datasource {:jdbcUrl jdbc-url
                                  :maximumPoolSize 10
                                  :minimumIdle 2
                                  :connectionTimeout 30000
                                  :idleTimeout 600000
                                  :maxLifetime 1800000})]
    (log/info "Database connection pool created")
    ds))

(defn close-datasource
  "Close the datasource and release connections"
  [ds]
  (when (instance? HikariDataSource ds)
    (.close ^HikariDataSource ds)
    (log/info "Database connection pool closed")))

(def ^:private default-opts
  "Default options for query execution"
  {:builder-fn rs/as-unqualified-kebab-maps})

(defn execute!
  "Execute a SQL statement (INSERT, UPDATE, DELETE)"
  [ds sql-params]
  (jdbc/execute! ds sql-params default-opts))

(defn execute-one!
  "Execute a SQL statement and return one row"
  [ds sql-params]
  (jdbc/execute-one! ds sql-params default-opts))

(defn query
  "Execute a SELECT query and return all rows"
  [ds sql-params]
  (jdbc/execute! ds sql-params default-opts))

(defn query-one
  "Execute a SELECT query and return one row"
  [ds sql-params]
  (jdbc/execute-one! ds sql-params default-opts))

(defmacro with-transaction
  "Execute body within a database transaction.
   Binds the transactional connection to `tx-sym`.
   Rolls back on any exception."
  [[tx-sym ds] & body]
  `(jdbc/with-transaction [~tx-sym ~ds]
     ~@body))

(defn health-check
  "Check if database is accessible"
  [ds]
  (try
    (query-one ds ["SELECT 1 as ok"])
    true
    (catch Exception e
      (log/error e "Database health check failed")
      false)))
