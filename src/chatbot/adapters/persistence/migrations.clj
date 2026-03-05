(ns chatbot.adapters.persistence.migrations
  "Simple migration runner. Tracks applied migrations in schema_migrations table."
  (:require [next.jdbc :as jdbc]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]))

;; Ordered list of migration files — add new entries here when creating migrations.
(def ^:private migrations
  ["001_init.sql"
   "002_email_promo.sql"])

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

(defn- apply-migration! [ds filename url]
  (let [sql (slurp url)]
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
        pending (->> migrations
                     (remove applied)
                     (map (fn [f] [f (io/resource (str "migrations/" f))]))
                     (filter (fn [[_ url]] (some? url))))]
    (if (seq pending)
      (doseq [[filename url] pending]
        (apply-migration! ds filename url))
      (log/info "All migrations already applied"))
    (log/info (str "Migrations complete. Applied " (count pending) " new migration(s)"))))
