(ns chatbot.adapters.persistence.migrations
  "Simple migration runner. Tracks applied migrations in schema_migrations table."
  (:require [next.jdbc :as jdbc]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(defn- ensure-migrations-table! [ds]
  (jdbc/execute! ds ["
    CREATE TABLE IF NOT EXISTS schema_migrations (
      filename   TEXT        PRIMARY KEY,
      applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )"]))

(defn- applied-migrations [ds]
  (->> (jdbc/execute! ds ["SELECT filename FROM schema_migrations ORDER BY filename"])
       (map :schema_migrations/filename)
       set))

(defn- migration-files []
  (when-let [dir (io/resource "migrations")]
    (->> (io/file dir)
         .listFiles
         (filter #(str/ends-with? (.getName %) ".sql"))
         (sort-by #(.getName %)))))

(defn- apply-migration! [ds ^java.io.File file]
  (let [sql      (slurp file)
        filename (.getName file)]
    (log/info "Applying migration:" filename)
    (with-open [conn (jdbc/get-connection ds)]
      (.setAutoCommit conn false)
      (try
        (with-open [stmt (.createStatement conn)]
          (.execute stmt sql))
        (with-open [pstmt (.prepareStatement conn
                            "INSERT INTO schema_migrations (filename) VALUES (?)")]
          (.setString pstmt 1 filename)
          (.execute pstmt))
        (.commit conn)
        (catch Exception e
          (.rollback conn)
          (throw e))))
    (log/info "Migration applied:" filename)))

(defn run-migrations! [ds]
  (log/info "Running database migrations...")
  (ensure-migrations-table! ds)
  (let [applied (applied-migrations ds)
        pending (remove #(contains? applied (.getName %)) (migration-files))]
    (if (seq pending)
      (doseq [f pending]
        (apply-migration! ds f))
      (log/info "All migrations already applied"))
    (log/info (str "Migrations complete. Applied " (count pending) " new migration(s)"))))
