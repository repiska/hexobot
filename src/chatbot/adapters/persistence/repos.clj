(ns chatbot.adapters.persistence.repos
  "Repository implementations using PostgreSQL.
   Implements the repository ports using HoneySQL for query building."
  (:require [chatbot.adapters.persistence.db :as db]
            [chatbot.ports.repository :as ports]
            [chatbot.utils.errors :as errors]
            [honey.sql :as sql]
            [honey.sql.helpers :as h]
            [clojure.tools.logging :as log]
            [cheshire.core :as json])
  (:import [java.util UUID]))

;; ============================================
;; SQL Helpers
;; ============================================

(defn- ->sql
  "Convert HoneySQL map to SQL vector"
  [query]
  (sql/format query))

(defn- json->clj
  "Parse JSON field to Clojure data. Handles PGobject from PostgreSQL JSONB."
  [v]
  (when v
    (cond
      (string? v) (json/parse-string v true)
      (instance? org.postgresql.util.PGobject v) (json/parse-string (.getValue v) true)
      :else v)))

(defn- clj->jsonb
  "Convert Clojure data to JSONB for PostgreSQL with explicit cast"
  [v]
  (when v
    [:cast (json/generate-string v) :jsonb]))

;; ============================================
;; User Repository
;; ============================================

(defrecord PostgresUserRepo [ds]
  ports/UserRepository
  
  (find-user [this internal-id]
    (errors/with-error-boundary
      (str "find-user: " internal-id)
      nil
      (let [query (-> (h/select :*)
                      (h/from :users)
                      (h/where [:= :id internal-id])
                      ->sql)]
        (when-let [row (db/query-one ds query)]
          (-> row
              (update :state keyword)
              (update :state-data json->clj))))))
  
  (find-user-by-original [this platform original-id]
    (errors/with-error-boundary
      (str "find-user-by-original: " platform "/" original-id)
      nil
      (let [query (-> (h/select :*)
                      (h/from :users)
                      (h/where [:and
                                [:= :platform (name platform)]
                                [:= :original-id original-id]])
                      ->sql)]
        (when-let [row (db/query-one ds query)]
          (-> row
              (update :state keyword)
              (update :state-data json->clj))))))
  
  (create-user! [this user-data]
    (try
      (let [{:keys [id platform original-id username first-name last-name]} user-data
            query (-> (h/insert-into :users)
                      (h/values [{:id id
                                  :platform (name platform)
                                  :original-id original-id
                                  :username username
                                  :first-name first-name
                                  :last-name last-name
                                  :state "idle"
                                  :state-data (clj->jsonb {})}])
                      (h/returning :*)
                      ->sql)]
        (log/info "Creating user:" id)
        (-> (db/execute-one! ds query)
            (update :state keyword)
            (update :state-data json->clj)))
      (catch Exception e
        (log/error e "Failed to create user:" (:id user-data))
        (throw (ex-info "User creation failed"
                        {:user-id (:id user-data)
                         :cause e})))))
  
  (update-user! [this internal-id updates]
    (errors/with-error-boundary
      (str "update-user: " internal-id)
      nil
      (let [updates-map (cond-> updates
                          (:state updates) (update :state name)
                          (:state-data updates) (update :state-data clj->jsonb))
            query (-> (h/update :users)
                      (h/set (assoc updates-map :updated-at [:now]))
                      (h/where [:= :id internal-id])
                      (h/returning :*)
                      ->sql)]
        (-> (db/execute-one! ds query)
            (update :state keyword)
            (update :state-data json->clj)))))
  
  (update-state! [this internal-id new-state state-data]
    (ports/update-user! this internal-id 
                        {:state (name new-state)
                         :state-data state-data})))

;; ============================================
;; Ticket Repository          
;; ============================================

(defrecord PostgresTicketRepo [ds]
  ports/TicketRepository
  
  (find-ticket [this ticket-id]
    (errors/with-error-boundary
      (str "find-ticket: " ticket-id)
      nil
      (let [query (-> (h/select :*)
                      (h/from :tickets)
                      (h/where [:= :id ticket-id])
                      ->sql)]
        (when-let [row (db/query-one ds query)]
          (update row :status keyword)))))
  
  (find-active-ticket [this user-id]
    (let [query (-> (h/select :*)
                    (h/from :tickets)
                    (h/where [:and
                              [:= :user-id user-id]
                              [:= :status "active"]])
                    (h/order-by [:created-at :desc])
                    (h/limit 1)
                    ->sql)]
      (when-let [row (db/query-one ds query)]
        (update row :status keyword))))
  
  (create-ticket! [this user-id]
    (try
      (let [query (-> (h/insert-into :tickets)
                      (h/values [{:id (UUID/randomUUID)
                                  :user-id user-id
                                  :status "active"}])
                      (h/returning :*)
                      ->sql)]
        (log/info "Creating ticket for user:" user-id)
        (-> (db/execute-one! ds query)
            (update :status keyword)))
      (catch Exception e
        (log/error e "Failed to create ticket for user:" user-id)
        (throw (ex-info "Ticket creation failed"
                        {:user-id user-id
                         :cause e})))))
  
  (update-ticket! [this ticket-id updates]
    (let [updates-map (cond-> updates
                        (:status updates) (update :status name))
          query (-> (h/update :tickets)
                    (h/set updates-map)
                    (h/where [:= :id ticket-id])
                    (h/returning :*)
                    ->sql)]
      (-> (db/execute-one! ds query)
          (update :status keyword))))
  
  (close-ticket! [this ticket-id]
    (ports/update-ticket! this ticket-id {:status "closed" :resolved-at [:now]}))

  (find-ticket-by-conv-id [this chatwoot-conv-id]
    (errors/with-error-boundary
      (str "find-ticket-by-conv-id: " chatwoot-conv-id)
      nil
      (let [query (-> (h/select :*)
                      (h/from :tickets)
                      (h/where [:= :chatwoot-conv-id chatwoot-conv-id])
                      (h/order-by [:created-at :desc])
                      (h/limit 1)
                      ->sql)]
        (when-let [row (db/query-one ds query)]
          (update row :status keyword))))))

;; ============================================
;; Review Repository
;; ============================================

(defrecord PostgresReviewRepo [ds]
  ports/ReviewRepository
  
  (find-review [this review-id]
    (let [query (-> (h/select :*)
                    (h/from :reviews)
                    (h/where [:= :id review-id])
                    ->sql)]
      (when-let [row (db/query-one ds query)]
        (-> row
            (update :status keyword)
            (update :photo-urls json->clj)))))
  
  (find-pending-review [this user-id]
    (let [query (-> (h/select :*)
                    (h/from :reviews)
                    (h/where [:and
                              [:= :user-id user-id]
                              [:= :status "moderation"]])
                    (h/order-by [:created-at :desc])
                    (h/limit 1)
                    ->sql)]
      (when-let [row (db/query-one ds query)]
        (-> row
            (update :status keyword)
            (update :photo-urls json->clj)))))
  
  (find-all-pending-reviews [this]
    (errors/with-error-boundary
      "find-all-pending-reviews"
      []
      (let [query (-> (h/select :*)
                      (h/from :reviews)
                      (h/where [:= :status "moderation"])
                      (h/order-by [:created-at :asc])
                      ->sql)]
        (->> (db/query ds query)
             (mapv #(-> %
                        (update :status keyword)
                        (update :photo-urls json->clj)))))))
  
  (create-review! [this user-id content-text photo-urls author-name author-link]
    (try
      (let [query (-> (h/insert-into :reviews)
                      (h/values [{:id (UUID/randomUUID)
                                  :user-id user-id
                                  :content-text content-text
                                  :photo-urls (clj->jsonb (or photo-urls []))
                                  :author-name author-name
                                  :author-link author-link
                                  :status "moderation"}])
                      (h/returning :*)
                      ->sql)]
        (log/info "Creating review for user:" user-id)
        (-> (db/execute-one! ds query)
            (update :status keyword)
            (update :photo-urls json->clj)))
      (catch Exception e
        (log/error e "Failed to create review for user:" user-id)
        (throw (ex-info "Review creation failed"
                        {:user-id user-id
                         :cause e})))))
  
  (update-review! [this review-id updates]
    (let [updates-map (cond-> updates
                        (:status updates) (update :status name)
                        (:photo-urls updates) (update :photo-urls clj->jsonb))
          query (-> (h/update :reviews)
                    (h/set updates-map)
                    (h/where [:= :id review-id])
                    (h/returning :*)
                    ->sql)]
      (-> (db/execute-one! ds query)
          (update :status keyword)
          (update :photo-urls json->clj))))
  
  (publish-review! [this review-id published-message-id]
    (ports/update-review! this review-id 
                          {:status "published"
                           :published-message-id published-message-id
                           :moderated-at [:now]}))
  
  (reject-review! [this review-id]
    (ports/update-review! this review-id
                          {:status "rejected"
                           :moderated-at [:now]})))

;; ============================================
;; Promo Repository
;; ============================================

(defrecord PostgresPromoRepo [ds]
  ports/PromoRepository

  ;; ---- Campaign methods ----

  (find-active-campaign [this]
    (errors/with-error-boundary
      "find-active-campaign"
      nil
      (let [query (-> (h/select :*)
                      (h/from :promo-campaigns)
                      (h/where [:and
                                [:= :is-active true]
                                [:or [:= :expires-at nil] [:> :expires-at [:now]]]
                                [:or [:= :max-uses nil] [:< :usage-count :max-uses]]])
                      (h/order-by [:created-at :asc])
                      (h/limit 1)
                      ->sql)]
        (db/query-one ds query))))

  (find-active-campaign-for-user [this user-id]
    (errors/with-error-boundary
      (str "find-active-campaign-for-user: " user-id)
      nil
      (let [query (-> (h/select :*)
                      (h/from :promo-campaigns)
                      (h/where [:and
                                [:= :is-active true]
                                [:or [:= :expires-at nil] [:> :expires-at [:now]]]
                                [:or [:= :max-uses nil] [:< :usage-count :max-uses]]
                                [:not-in :id {:select [:campaign-id]
                                              :from   [:promo-log]
                                              :where  [:= :user-id user-id]}]])
                      (h/order-by [:created-at :asc])
                      (h/limit 1)
                      ->sql)]
        (db/query-one ds query))))

  (find-active-campaign-for-user-by-type [this user-id campaign-type]
    (errors/with-error-boundary
      (str "find-active-campaign-for-user-by-type: " user-id "/" campaign-type)
      nil
      (let [query (-> (h/select :*)
                      (h/from :promo-campaigns)
                      (h/where [:and
                                [:= :is-active true]
                                [:= :campaign-type campaign-type]
                                [:or [:= :expires-at nil] [:> :expires-at [:now]]]
                                [:or [:= :max-uses nil] [:< :usage-count :max-uses]]
                                [:not-in :id {:select [:campaign-id]
                                              :from   [:promo-log]
                                              :where  [:= :user-id user-id]}]])
                      (h/order-by [:created-at :asc])
                      (h/limit 1)
                      ->sql)]
        (db/query-one ds query))))

  (find-issued-promo-for-campaign [this user-id campaign-id]
    (errors/with-error-boundary
      (str "find-issued-promo-for-campaign: " user-id "/" campaign-id)
      nil
      (let [query (-> (h/select :*)
                      (h/from :promo-log)
                      (h/where [:and
                                [:= :user-id user-id]
                                [:= :campaign-id campaign-id]])
                      ->sql)]
        (db/query-one ds query))))

  (issue-promo-for-campaign! [this user-id campaign]
    (let [campaign-id (:id campaign)
          code        (:promo-code campaign)
          expires-at  (:expires-at campaign)]
      (if (nil? code)
        {:success? false :reason :no-promo-code}
        (try
          (db/execute! ds (-> (h/insert-into :promo-log)
                              (h/values [(cond-> {:user-id     user-id
                                                  :campaign-id campaign-id
                                                  :code-issued code}
                                           expires-at (assoc :expires-at expires-at))])
                              ->sql))
          (db/execute! ds (-> (h/update :promo-campaigns)
                              (h/set {:usage-count [:+ :usage-count 1]})
                              (h/where [:= :id campaign-id])
                              ->sql))
          (log/info "Issued promo code" code "to user:" user-id "campaign:" campaign-id)
          {:success? true :code code}
          (catch Exception e
            (if (and (instance? org.postgresql.util.PSQLException e)
                     (= "23505" (.getSQLState ^org.postgresql.util.PSQLException e)))
              (do
                (log/info "Duplicate promo issue attempt for user:" user-id "campaign:" campaign-id)
                {:success? false :reason :already-issued :code code})
              (do
                (log/error e "Failed to issue promo to user:" user-id)
                {:success? false :reason :db-error})))))))

  (list-campaigns [this]
    (errors/with-error-boundary
      "list-campaigns"
      []
      (let [query (-> (h/select :*)
                      (h/from :promo-campaigns)
                      (h/where [:!= :id (UUID/fromString "00000000-0000-0000-0000-000000000001")])
                      (h/order-by [:created-at :desc])
                      ->sql)]
        (db/query ds query))))

  (create-campaign! [this data]
    (try
      (let [{:keys [name promo-code max-uses expires-at description]} data
            query (-> (h/insert-into :promo-campaigns)
                      (h/values [{:id          (UUID/randomUUID)
                                  :name        name
                                  :promo-code  promo-code
                                  :max-uses    max-uses
                                  :expires-at  expires-at
                                  :description description
                                  :is-active   true}])
                      (h/returning :*)
                      ->sql)]
        (log/info "Creating campaign:" name)
        (db/execute-one! ds query))
      (catch Exception e
        (log/error e "Failed to create campaign:" (:name data))
        (throw (ex-info "Campaign creation failed" {:data data :cause e})))))

  (update-campaign! [this campaign-id updates]
    (errors/with-error-boundary
      (str "update-campaign: " campaign-id)
      nil
      (let [query (-> (h/update :promo-campaigns)
                      (h/set updates)
                      (h/where [:= :id campaign-id])
                      (h/returning :*)
                      ->sql)]
        (db/execute-one! ds query))))

  (ensure-welcome-campaign! [this welcome-code]
    (when welcome-code
      (let [existing (db/query-one ds
                       (-> (h/select :id)
                           (h/from :promo-campaigns)
                           (h/where [:and
                                     [:= :is-active true]
                                     [:= :campaign-type "welcome"]])
                           (h/limit 1)
                           ->sql))]
        (when-not existing
          (db/execute-one! ds (-> (h/insert-into :promo-campaigns)
                                  (h/values [{:id          (UUID/randomUUID)
                                              :name        "Приветственный"
                                              :promo-code  welcome-code
                                              :description "Создан автоматически из конфигурации .env"
                                              :campaign-type "welcome"
                                              :is-active   true}])
                                  ->sql))
          (log/info "Created default welcome campaign with code:" welcome-code)))))

  (ensure-email-campaign! [this email-code]
    (when email-code
      (let [existing (db/query-one ds
                       (-> (h/select :id)
                           (h/from :promo-campaigns)
                           (h/where [:and
                                     [:= :is-active true]
                                     [:= :campaign-type "email"]])
                           (h/limit 1)
                           ->sql))]
        (when-not existing
          (db/execute-one! ds (-> (h/insert-into :promo-campaigns)
                                  (h/values [{:id          (UUID/randomUUID)
                                              :name        "Email бонус"
                                              :promo-code  email-code
                                              :description "Создан автоматически из конфигурации .env"
                                              :campaign-type "email"
                                              :is-active   true}])
                                  ->sql))
          (log/info "Created default email campaign with code:" email-code)))))

  ;; ---- User promo history ----

  (get-user-promos [this user-id]
    (errors/with-error-boundary
      (str "get-user-promos: " user-id)
      []
      (let [query (-> (h/select [:pl.code-issued :code-issued]
                                [:pl.issued-at :issued-at]
                                [:pl.expires-at :expires-at]
                                [:pc.name :campaign-name])
                      (h/from [:promo-log :pl])
                      (h/join [:promo-campaigns :pc] [:= :pl.campaign-id :pc.id])
                      (h/where [:= :pl.user-id user-id])
                      (h/order-by [:pl.issued-at :desc])
                      ->sql)]
        (db/query ds query))))

  (cleanup-expired-promos! [this user-id grace-days]
    (errors/with-error-boundary
      (str "cleanup-expired-promos!: " user-id)
      0
      (let [query (-> (h/delete-from :promo-log)
                      (h/where [:and
                                [:= :user-id user-id]
                                [:!= :expires-at nil]
                                [:< :expires-at [:- [:now] [:raw (str "interval '" grace-days " days'")]]]])
                      ->sql)]
        (:next.jdbc/update-count (db/execute-one! ds query)))))

  ;; ---- Campaign deletion ----

  (delete-campaign! [this campaign-id]
    (errors/with-error-boundary
      (str "delete-campaign!: " campaign-id)
      false
      (db/with-transaction [tx ds]
        (db/execute-one! tx (-> (h/delete-from :broadcast-log)
                                (h/where [:= :campaign-id campaign-id])
                                ->sql))
        (db/execute-one! tx (-> (h/delete-from :promo-log)
                                (h/where [:= :campaign-id campaign-id])
                                ->sql))
        (let [result (db/execute-one! tx (-> (h/delete-from :promo-campaigns)
                                             (h/where [:= :id campaign-id])
                                             ->sql))]
          (pos? (or (:next.jdbc/update-count result) 0))))))

  ;; ---- Broadcast methods ----

  (get-broadcast-targets [this campaign-id]
    (errors/with-error-boundary
      (str "get-broadcast-targets: " campaign-id)
      []
      (let [query (-> (h/select [:id :user-id])
                      (h/from :users)
                      (h/where [:and
                                [:not-in :id {:select [:user-id]
                                              :from   [:broadcast-log]
                                              :where  [:= :campaign-id campaign-id]}]
                                [:not-in :id {:select [:user-id]
                                              :from   [:promo-log]
                                              :where  [:= :campaign-id campaign-id]}]])
                      ->sql)]
        (mapv :user-id (db/query ds query)))))

  (mark-broadcast-sent! [this campaign-id user-id]
    (errors/with-error-boundary
      (str "mark-broadcast-sent: " campaign-id "/" user-id)
      nil
      (db/execute! ds (-> (h/insert-into :broadcast-log)
                          (h/values [{:campaign-id campaign-id
                                      :user-id     user-id
                                      :sent-at     [:now]}])
                          ->sql)))))

;; ============================================
;; Stats Repository
;; ============================================

(defrecord PostgresStatsRepo [ds]
  ports/StatsRepository
  
  (count-users [this]
    (let [query (-> (h/select [[:count :*] :cnt])
                    (h/from :users)
                    ->sql)]
      (:cnt (db/query-one ds query) 0)))
  
  (count-pending-reviews [this]
    (let [query (-> (h/select [[:count :*] :cnt])
                    (h/from :reviews)
                    (h/where [:= :status "moderation"])
                    ->sql)]
      (:cnt (db/query-one ds query) 0))))

;; ============================================
;; Factory Functions
;; ============================================

(defn create-user-repo [ds] (->PostgresUserRepo ds))
(defn create-ticket-repo [ds] (->PostgresTicketRepo ds))
(defn create-review-repo [ds] (->PostgresReviewRepo ds))
(defn create-promo-repo [ds] (->PostgresPromoRepo ds))
(defn create-stats-repo [ds] (->PostgresStatsRepo ds))

(defn create-all-repos
  "Create all repository instances"
  [ds]
  {:user-repo   (create-user-repo ds)
   :ticket-repo (create-ticket-repo ds)
   :review-repo (create-review-repo ds)
   :promo-repo  (create-promo-repo ds)
   :stats-repo  (create-stats-repo ds)})
