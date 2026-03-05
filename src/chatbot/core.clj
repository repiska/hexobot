(ns chatbot.core
  "Application entry point.
   Wires up all components and starts the bot."
  (:require [chatbot.config :as config]
            [chatbot.domain.router :as router]
            [chatbot.domain.entities :as entities]
            [chatbot.domain.messages :as msg]
            [chatbot.domain.services.promo :as promo-service]
            [chatbot.domain.services.broadcast :as broadcast]
            [chatbot.domain.services.support :as support]
            [chatbot.domain.admin :as admin]
            [chatbot.adapters.telegram.api :as tg-api]
            [chatbot.adapters.telegram.poller :as tg-poller]
            [chatbot.adapters.max.api :as max-api]
            [chatbot.adapters.max.poller :as max-poller]
            [chatbot.adapters.persistence.db :as db]
            [chatbot.adapters.persistence.repos :as repos]
            [chatbot.adapters.persistence.migrations :as migrations]
            [chatbot.adapters.chatwoot.api :as cw-api]
            [chatbot.adapters.chatwoot.adapter :as cw-adapter]
            [chatbot.adapters.chatwoot.webhook :as cw-webhook]
            [chatbot.ports.repository :as repo-ports]
            [chatbot.ports.crm :as crm-ports]
            [chatbot.services.channel-sync :as channel-sync]
            [chatbot.web.server :as web-server]
            [chatbot.utils.errors :as errors]
            [clojure.tools.logging :as log]
            [clojure.string :as str])
  (:gen-class))

;; ============================================
;; Application State
;; ============================================

(defonce app-state (atom nil))

;; ============================================
;; Message Handler
;; ============================================

(defn- ensure-user!
  "Ensure user exists in database, create if not.
   Returns {:user <user-map> :created? <bool>}."
  [user-repo message]
  (let [user-id (:user-id message)
        user-info (:user-info message)]
    (if-let [existing (repo-ports/find-user user-repo user-id)]
      {:user existing :created? false}
      {:user (repo-ports/create-user! user-repo
               {:id user-id
                :platform (:platform message)
                :original-id (:original-user-id message)
                :username (:username user-info)
                :first-name (:first-name user-info)
                :last-name (:last-name user-info)})
       :created? true})))

(defn- build-author-link
  "Build a profile deeplink for the user based on platform."
  [user-info message]
  (let [platform    (:platform message)
        username    (:username user-info)
        original-id (:original-user-id message)]
    (case platform
      :telegram (if username
                  (str "https://t.me/" username)
                  (str "tg://user?id=" original-id))
      :max      (str "https://max.ru/u/" original-id)
      nil)))

(defn- build-author-name
  "Build a human-readable author name from user-info (current request) with fallback to DB user."
  [user-info user]
  (let [first-name (or (:first-name user-info) (:first-name user))
        last-name  (or (:last-name  user-info) (:last-name  user))
        username   (or (:username   user-info) (:username   user))
        max-name   (:name user-info)]
    (cond
      first-name (str/trim (str first-name (when last-name (str " " last-name))))
      max-name   max-name
      username   username
      :else      "Клиент")))

(defn- execute-responses!
  "Execute response actions from router.
   Supports both Telegram and MAX adapters.
   user-repo is used for edit-or-send logic; pass nil to always send new messages."
  [adapters user-repo responses callback-id]
  (doseq [{:keys [action user-id data]} responses]
    (when-let [{:keys [platform]} (entities/parse-internal-id user-id)]
      (case action
        :send-text
        (let [{:keys [text keyboard]} data]
          (case platform
            :telegram
            (let [{:keys [original-id]} (entities/parse-internal-id user-id)
                  last-msg-id (when user-repo
                                (:last-bot-message-id (repo-ports/find-user user-repo user-id)))
                  ;; editMessageText only supports InlineKeyboardMarkup in reply_markup.
                  ;; Skip edit entirely for reply keyboards — always send a new message.
                  can-edit?   (not= (:type keyboard) :reply)
                  edit-result (when (and last-msg-id can-edit?)
                                (tg-api/edit-message (:tg-client adapters)
                                  original-id last-msg-id text {:keyboard keyboard}))
                  ;; Circuit breaker wraps: {:ok true :result <tg-response>}
                  ;; Inner Telegram response is at [:result], message_id at [:result :result :message_id]
                  edit-ok? (and edit-result (get-in edit-result [:result :ok]))
                  ;; "message is not modified" = content identical, treat as success (no new send)
                  edit-not-modified? (and edit-result (not edit-ok?)
                                          (str/includes? (str (get-in edit-result [:result :body]))
                                                         "message is not modified"))
                  result (if (or edit-ok? edit-not-modified?)
                           edit-result
                           (tg-api/send-message (:tg-client adapters) original-id text {:keyboard keyboard}))
                  new-msg-id (when (and (not edit-ok?) (not edit-not-modified?))
                               (get-in result [:result :result :message_id]))]
              (if (or edit-ok? edit-not-modified? (get-in result [:result :ok]))
                (do
                  (cond
                    edit-ok? (log/debug "Telegram message EDITED in-place:" original-id "msg:" last-msg-id)
                    edit-not-modified? (log/debug "Telegram message unchanged (not modified):" original-id "msg:" last-msg-id)
                    :else (log/debug "Telegram NEW message sent:" original-id "msg:" new-msg-id
                                     (when last-msg-id "(edit failed)")))
                  (when (and user-repo new-msg-id)
                    (repo-ports/update-user! user-repo user-id
                      {:last-bot-message-id (str new-msg-id)})))
                (log/error "Failed to send/edit Telegram message:" result)))

            :max
            (let [{:keys [original-id chat-id]} (entities/parse-internal-id user-id)
                  ;; Check if this is a channel (format: max_channel_<id>)
                  ;; parse-internal-id returns {:original-id "channel" :chat-id "<actual-id>"}
                  is-channel? (= original-id "channel")
                  target-chat-id (when is-channel? chat-id)
                  last-msg-id (when (and user-repo (not is-channel?))
                                (:last-bot-message-id (repo-ports/find-user user-repo user-id)))
                  edit-result (when last-msg-id
                                (max-api/edit-message (:max-client adapters)
                                  last-msg-id text {:keyboard keyboard}))
                  ;; Circuit breaker wraps: {:ok true :result <max-response>}
                  ;; MAX send-message returns {:message {:body {:mid "mid.xxx"} ...}}
                  ;; Message ID (mid) is at [:result :message :body :mid]
                  edit-ok? (and edit-result (some? (get-in edit-result [:result :message :body :mid])))
                  result (if edit-ok?
                           edit-result
                           (if is-channel?
                             (max-api/send-message (:max-client adapters) target-chat-id text {:keyboard keyboard})
                             (max-api/send-message (:max-client adapters) nil text {:keyboard keyboard :user-id original-id})))
                  new-msg-id (when-not edit-ok?
                               (get-in result [:result :message :body :mid]))]
              (if (or edit-ok? (some? new-msg-id))
                (do
                  (log/debug "Message sent to MAX" (if is-channel? "channel:" "user:") (or target-chat-id original-id))
                  (when (and user-repo (not is-channel?) new-msg-id)
                    (repo-ports/update-user! user-repo user-id
                      {:last-bot-message-id (str new-msg-id)})))
                (log/error "Failed to send MAX message:" result)))))

        :send-photo
        (let [{:keys [photo caption keyboard]} data]
          (case platform
            :telegram
            (let [{:keys [original-id]} (entities/parse-internal-id user-id)
                  result (tg-api/send-photo (:tg-client adapters) original-id photo {:caption caption :keyboard keyboard})]
              (if (:ok result)
                (log/debug "Photo sent to Telegram:" original-id)
                (log/error "Failed to send Telegram photo:" result)))

            :max
            (let [{:keys [original-id chat-id]} (entities/parse-internal-id user-id)
                  ;; Check if this is a channel (format: max_channel_<id>)
                  ;; parse-internal-id returns {:original-id "channel" :chat-id "<actual-id>"}
                  is-channel? (= original-id "channel")
                  target-chat-id (when is-channel? chat-id)
                  ;; Convert Telegram file_id to URL if needed
                  is-url? (or (clojure.string/starts-with? photo "http://")
                              (clojure.string/starts-with? photo "https://"))
                  photo-url (if is-url?
                              photo
                              (or (tg-api/resolve-file-to-url (:tg-client adapters) photo)
                                  photo))]
              (if (or (clojure.string/starts-with? photo-url "http://")
                      (clojure.string/starts-with? photo-url "https://"))
                (let [result (if is-channel?
                               (max-api/send-photo (:max-client adapters) target-chat-id photo-url {:caption caption :keyboard keyboard})
                               (max-api/send-photo (:max-client adapters) nil photo-url {:caption caption :keyboard keyboard :user-id original-id}))]
                  (if (:ok result)
                    (log/debug "Photo sent to MAX" (if is-channel? "channel:" "user:") (or target-chat-id original-id))
                    (log/error "Failed to send MAX photo:" result)))
                (log/warn "Could not resolve photo URL for MAX, skipping send")))))
        
        :send-photos
        (let [{:keys [photos caption]} data]
          (case platform
            :telegram
            (let [{:keys [original-id]} (entities/parse-internal-id user-id)]
              (if (= 1 (count photos))
                (let [result (tg-api/send-photo (:tg-client adapters) original-id (first photos) {:caption caption})]
                  (if (:ok result)
                    (log/debug "Photo sent to Telegram:" original-id)
                    (log/error "Failed to send Telegram photo:" result)))
                (let [result (tg-api/send-media-group (:tg-client adapters) original-id photos {:caption caption})]
                  (if (:ok result)
                    (log/debug "Media group sent to Telegram:" original-id)
                    (log/error "Failed to send Telegram media group:" result)))))
            
            :max
            (let [{:keys [original-id chat-id]} (entities/parse-internal-id user-id)
                  ;; Check if this is a channel (format: max_channel_<id>)
                  ;; parse-internal-id returns {:original-id "channel" :chat-id "<actual-id>"}
                  is-channel? (= original-id "channel")
                  target-chat-id (when is-channel? chat-id)
                  ;; Convert Telegram file_ids to URLs if needed (MAX requires URLs)
                  is-url? (fn [s] (or (clojure.string/starts-with? s "http://")
                                      (clojure.string/starts-with? s "https://")))
                  resolved-photos (if (every? is-url? photos)
                                    photos
                                    ;; Convert file_ids to URLs
                                    (mapv #(if (is-url? %)
                                             %
                                             (or (tg-api/resolve-file-to-url (:tg-client adapters) %)
                                                 %))
                                          photos))
                  ;; Filter to only valid URLs
                  valid-photos (filterv is-url? resolved-photos)]
              (if (seq valid-photos)
                (let [result (if is-channel?
                               (max-api/send-media-group (:max-client adapters) target-chat-id valid-photos {:caption caption})
                               (max-api/send-media-group (:max-client adapters) nil valid-photos {:user-id original-id :caption caption}))]
                  (if (:ok result)
                    (log/debug "Media group sent to MAX" (if is-channel? "channel:" "user:") (or target-chat-id original-id))
                    (log/error "Failed to send MAX media group:" result)))
                (log/warn "No valid photo URLs for MAX, skipping send")))))

        :no-op nil
        
        (log/warn "Unknown action:" action))))
  
  ;; Answer callback if present
  (when callback-id
    (let [{:keys [platform callback-id-value]} callback-id]
      (case platform
        :telegram
        (tg-api/answer-callback-query (:tg-client adapters) callback-id-value {})
        
        :max
        (max-api/answer-callback (:max-client adapters) callback-id-value {})
        
        (log/warn "Unknown callback platform:" platform)))))

(defn- format-promo-expiry-status
  "Format expires_at for the user promo history display."
  [expires-at]
  (if (nil? expires-at)
    "✅ Бессрочный"
    (let [now   (java.time.Instant/now)
          inst  (cond
                  (instance? java.sql.Timestamp expires-at)
                  (.toInstant ^java.sql.Timestamp expires-at)
                  (instance? java.time.OffsetDateTime expires-at)
                  (.toInstant ^java.time.OffsetDateTime expires-at)
                  :else nil)
          fmt   (java.time.format.DateTimeFormatter/ofPattern "dd.MM.yyyy")
          zoned (when inst (.atZone inst (java.time.ZoneId/of "Europe/Moscow")))
          date-str (when zoned (.format zoned fmt))]
      (cond
        (nil? inst) "✅ Бессрочный"
        (.isAfter inst now)   (str "⏳ до " date-str)
        :else                 (str "❌ Истёк " date-str)))))

(defn- format-user-promos
  "Format a list of promo entries for display."
  [promos]
  (str/join "\n\n"
    (map (fn [{:keys [code-issued campaign-name expires-at]}]
           (format "• `%s` — *%s*\n  %s"
                   (or code-issued "—")
                   (or campaign-name "—")
                   (format-promo-expiry-status expires-at)))
         promos)))

(defn- my-promos-response
  "Build the 'My Promos' response for a user."
  [promo-repo user-id]
  (try
    (repo-ports/cleanup-expired-promos! promo-repo user-id 3)
    (catch Exception e
      (log/warn "cleanup-expired-promos! failed for:" user-id "-" e)))
  (let [promos (repo-ports/get-user-promos promo-repo user-id)]
    (if (seq promos)
      {:text (msg/t :my-promos-title (format-user-promos promos))
       :keyboard (msg/my-promos-keyboard)}
      {:text (msg/t :my-promos-empty)
       :keyboard (msg/my-promos-keyboard)})))

(defn- handle-promo-request
  "Handle promo code request with subscription check"
  [{:keys [adapters promo-repo config]} user-id platform]
  (let [;; Telegram channel settings (from :platforms :telegram :promo)
        tg-channel-username (get-in config [:platforms :telegram :promo :channel-username])
        tg-channel-link (get-in config [:platforms :telegram :promo :channel-link])
        tg-channel-id (when tg-channel-username (str "@" tg-channel-username))

        ;; MAX channel settings (from :platforms :max :channel)
        max-channel-id (get-in config [:platforms :max :channel :id])
        max-channel-link (get-in config [:platforms :max :channel :link])

        ;; Select platform-specific values
        channel-id (case platform
                     :telegram tg-channel-id
                     :max max-channel-id
                     nil)
        channel-link (case platform
                       :telegram tg-channel-link
                       :max max-channel-link
                       nil)

        {:keys [original-id]} (entities/parse-internal-id user-id)
        deps {:check-subscription-fn
              (fn [uid cid]
                (case platform
                  :telegram (tg-api/check-subscription (:tg-client adapters) cid original-id)
                  :max (if max-channel-id
                         ;; Check membership in MAX channel
                         (max-api/check-subscription (:max-client adapters) max-channel-id original-id)
                         ;; No MAX channel configured - skip check, allow promo
                         (do
                           (log/warn "MAX_CHANNEL_ID not configured - skipping subscription check for user:" original-id)
                           true))
                  {:ok false :error "Unknown platform"}))

              :find-active-campaign-fn
              (fn [uid]
                (repo-ports/find-active-campaign-for-user-by-type promo-repo uid "welcome"))

              :issue-for-campaign-fn
              (fn [uid campaign]
                (repo-ports/issue-promo-for-campaign! promo-repo uid campaign))}
        
        result (promo-service/check-and-issue-promo deps user-id channel-id)]

    (assoc (promo-service/build-promo-response user-id result channel-link)
           :reason (:reason result))))

(defn- handle-email-promo-request
  "Save user email and issue promo from the active email campaign."
  [{:keys [promo-repo user-repo]} user-id email]
  (repo-ports/update-user! user-repo user-id {:email email})
  (let [deps {:find-active-email-campaign-fn
              (fn [uid]
                (repo-ports/find-active-campaign-for-user-by-type promo-repo uid "email"))
              :issue-for-campaign-fn
              (fn [uid campaign]
                (repo-ports/issue-promo-for-campaign! promo-repo uid campaign))}
        result (promo-service/check-and-issue-email-promo deps user-id)]
    (promo-service/build-email-promo-response result)))

(defn- next-review-responses
  "Return response list showing next pending review, or empty-queue message."
  [review-repo admin-user-id]
  (let [pending (repo-ports/find-all-pending-reviews review-repo)]
    (if (seq pending)
      (let [review     (first pending)
            has-photos (seq (:photo-urls review))
            resp-text  (admin/format-review-for-admin review 0 (count pending))]
        [(router/text-response admin-user-id
           resp-text
           :keyboard (admin/review-moderation-keyboard (:id review) has-photos))])
      [(router/text-response admin-user-id
         (msg/t :admin-reviews-empty)
         :keyboard (admin/admin-menu-keyboard))])))

(defn- handle-message
  "Main message handler - processes incoming messages"
  [{:keys [adapters user-repo promo-repo review-repo stats-repo config] :as ctx} message]
  (try
    (log/debug "Processing message:" (:message-id message) "from:" (:user-id message))

    ;; Handle channel posts separately (no user needed)
    (when (= (:type message) :channel_post)
      (let [tg-channel-username (get-in config [:platforms :telegram :promo :channel-username])
            max-channel-id (get-in config [:platforms :max :channel :id])
            channel-info (:channel-info message)
            our-tg-channel? (and tg-channel-username
                                 (= (:channel-username channel-info) tg-channel-username))]
        ;; Only sync posts from our configured channels
        (when (or our-tg-channel?
                  (= (:channel-id channel-info) max-channel-id))
          (log/info "Channel post received from:" (:platform message)
                    "channel:" (or (:channel-username channel-info) (:channel-id channel-info)))
          (channel-sync/handle-channel-post!
            {:tg-client (:tg-client adapters)
             :max-client (:max-client adapters)
             :tg-channel-id (when tg-channel-username (str "@" tg-channel-username))
             :max-channel-id max-channel-id
             :bot-username (get-in @app-state [:bot-info :username])}
            message)))
      ;; Return early for channel posts
      (throw (ex-info "channel-post-handled" {:handled true})))

    ;; Ensure user exists with error handling
    (let [{:keys [user created?]}
          (try
            (ensure-user! user-repo message)
            (catch Exception e
              (log/error e "Failed to ensure user exists:" (:user-id message))
              (throw (ex-info "User creation/retrieval failed"
                              {:user-id (:user-id message)
                               :message-id (:message-id message)
                               :cause e}))))
          ;; Normalize reply keyboard button text to callback-data
          message       (router/normalize-reply-text message)
          callback-data (get-in message [:content :callback-data])
          command       (get-in message [:content :command])
          is-admin      (admin/admin? config (:user-id message))
          user-state    (keyword (or (:state user) :idle))
          platform      (:platform message)

          ;; Check for promo-related callbacks that need special handling
          result (cond
                   ;; /start for new user — welcome with promo button
                   (and (= command "/start") created?)
                   {:responses [(router/menu-response (:user-id message))
                                (router/text-response (:user-id message)
                                  (msg/t :welcome-promo)
                                  :keyboard (msg/welcome-promo-keyboard))]
                    :state-update {:state :idle :state-data {}}}

                   ;; Admin command
                   (and is-admin (= command "/admin"))
                   (do
                     (log/info "Admin panel accessed by:" (:user-id message))
                     (let [resp (admin/admin-menu-response (:user-id message))]
                       {:responses [(router/text-response (:user-id message)
                                      (:text resp)
                                      :keyboard (:keyboard resp))]
                        :state-update {:state :idle :state-data {}}}))

                   ;; Wizard guard: intercept admin_campaigns/admin_menu while in wizard state
                   (and is-admin
                        (contains? #{:admin_campaign_name :admin_campaign_code :admin_campaign_expires
                                     :admin_campaign_edit_name :admin_campaign_edit_code :admin_campaign_edit_expires}
                                   user-state)
                        (contains? #{"admin_campaigns" "admin_menu"} callback-data))
                   {:responses [(router/text-response (:user-id message)
                                  "⚠️ Мастер создания/редактирования не завершён. Выйти и потерять данные?"
                                  :keyboard {:type :inline
                                             :buttons [[{:text "✅ Выйти" :callback "wizard_exit_confirm"}]
                                                       [{:text "↩ Продолжить" :callback "wizard_continue"}]]})]
                    :state-update nil}

                   ;; Wizard guard: confirmed exit
                   (and is-admin (= callback-data "wizard_exit_confirm"))
                   (let [campaigns (repo-ports/list-campaigns promo-repo)]
                     {:responses [(router/text-response (:user-id message)
                                    (admin/format-campaigns-text campaigns)
                                    :keyboard (msg/admin-campaigns-keyboard campaigns))]
                      :state-update {:state :idle :state-data {}}})

                   ;; Wizard guard: continue — re-show current step prompt
                   (and is-admin (= callback-data "wizard_continue"))
                   (let [wizard (or (get-in user [:state-data :campaign-wizard])
                                    (get-in user [:state-data :edit-wizard])
                                    {})]
                     {:responses [(router/text-response (:user-id message)
                                    "↩ Продолжайте ввод."
                                    :keyboard (case user-state
                                                (:admin_campaign_name :admin_campaign_edit_name)
                                                (msg/admin-campaign-step-keyboard nil)
                                                (:admin_campaign_code :admin_campaign_edit_code)
                                                (msg/admin-campaign-step-keyboard nil)
                                                (:admin_campaign_expires)
                                                (msg/admin-campaign-step-keyboard "campaign_skip_expires")
                                                (:admin_campaign_edit_expires)
                                                (msg/admin-campaign-edit-step-keyboard "edit_keep_expires")
                                                (msg/admin-campaign-step-keyboard nil)))]
                      :state-update nil})

                   ;; Admin menu
                   (and is-admin (= callback-data "admin_menu"))
                   (let [resp (admin/admin-menu-response (:user-id message))]
                     {:responses [(router/text-response (:user-id message)
                                    (:text resp)
                                    :keyboard (:keyboard resp))]
                      :state-update {:state :idle :state-data {}}})

                   ;; Campaign list
                   (and is-admin (= callback-data "admin_campaigns"))
                   (let [campaigns (repo-ports/list-campaigns promo-repo)]
                     {:responses [(router/text-response (:user-id message)
                                    (admin/format-campaigns-text campaigns)
                                    :keyboard (msg/admin-campaigns-keyboard campaigns))]
                      :state-update {:state :idle :state-data {}}})

                   ;; New campaign - step 1: enter name
                   (and is-admin (= callback-data "admin_campaign_new"))
                   {:responses [(router/text-response (:user-id message)
                                  (msg/t :admin-campaign-enter-name)
                                  :keyboard (msg/admin-campaign-step-keyboard nil))]
                    :state-update {:state :admin_campaign_name
                                   :state-data {:campaign-wizard {}}}}

                   ;; Toggle campaign — show confirmation
                   (and is-admin callback-data (str/starts-with? callback-data "toggle_campaign_"))
                   (let [campaign-id (java.util.UUID/fromString (subs callback-data 16))
                         campaigns   (repo-ports/list-campaigns promo-repo)
                         campaign    (first (filter #(= (str (:id %)) (str campaign-id)) campaigns))
                         action-word (if (:is-active campaign) "деактивирована" "активирована")]
                     {:responses [(router/text-response (:user-id message)
                                    (str "Вы уверены? Кампания *" (:name campaign) "* будет " action-word ".")
                                    :keyboard {:type :inline
                                               :buttons [[{:text "✅ Да" :callback (str "toggle_confirm_" campaign-id)}]
                                                         [{:text "❌ Отмена" :callback "admin_campaigns"}]]})]
                      :state-update nil})

                   ;; Toggle campaign — confirmed
                   (and is-admin callback-data (str/starts-with? callback-data "toggle_confirm_"))
                   (let [campaign-id (java.util.UUID/fromString (subs callback-data 15))
                         campaigns   (repo-ports/list-campaigns promo-repo)
                         campaign    (first (filter #(= (str (:id %)) (str campaign-id)) campaigns))
                         new-active  (not (:is-active campaign))]
                     (repo-ports/update-campaign! promo-repo campaign-id {:is-active new-active})
                     (log/info "Admin toggled campaign:" campaign-id "is-active:" new-active)
                     (let [updated (repo-ports/list-campaigns promo-repo)]
                       {:responses [(router/text-response (:user-id message)
                                      (admin/format-campaigns-text updated)
                                      :keyboard (msg/admin-campaigns-keyboard updated))]
                        :state-update nil}))

                   ;; Delete campaign — show confirmation
                   (and is-admin callback-data (str/starts-with? callback-data "delete_campaign_"))
                   (let [campaign-id (java.util.UUID/fromString (subs callback-data 16))
                         campaigns   (repo-ports/list-campaigns promo-repo)
                         campaign    (first (filter #(= (str (:id %)) (str campaign-id)) campaigns))
                         usage       (or (:usage-count campaign) 0)]
                     {:responses [(router/text-response (:user-id message)
                                    (str "Удалить кампанию *" (:name campaign) "*?"
                                         (when (pos? usage)
                                           (str "\n\nПромокоды уже получили " usage " пользователей.")))
                                    :keyboard {:type :inline
                                               :buttons [[{:text "✅ Удалить" :callback (str "delete_confirm_" campaign-id)}]
                                                         [{:text "❌ Отмена" :callback "admin_campaigns"}]]})]
                      :state-update nil})

                   ;; Delete campaign — confirmed
                   (and is-admin callback-data (str/starts-with? callback-data "delete_confirm_"))
                   (let [campaign-id (java.util.UUID/fromString (subs callback-data 15))
                         campaigns   (repo-ports/list-campaigns promo-repo)
                         campaign    (first (filter #(= (str (:id %)) (str campaign-id)) campaigns))
                         usage       (or (:usage-count campaign) 0)]
                     (if (zero? usage)
                       (let [deleted? (repo-ports/delete-campaign! promo-repo campaign-id)]
                         (if deleted?
                           (do
                             (log/info "Admin deleted campaign:" campaign-id)
                             (let [updated (repo-ports/list-campaigns promo-repo)]
                               {:responses [(router/text-response (:user-id message)
                                              (str "✅ Кампания удалена.\n\n" (admin/format-campaigns-text updated))
                                              :keyboard (msg/admin-campaigns-keyboard updated))]
                                :state-update nil}))
                           (do
                             (log/warn "Failed to delete campaign:" campaign-id)
                             {:responses [(router/text-response (:user-id message)
                                            "❌ Не удалось удалить кампанию. Попробуйте снова."
                                            :keyboard {:type :inline
                                                       :buttons [[{:text "↩️ К кампаниям" :callback "admin_campaigns"}]]})]
                              :state-update nil})))
                       (do
                         (repo-ports/update-campaign! promo-repo campaign-id {:is-active false})
                         (log/info "Admin deactivated campaign (has usage):" campaign-id)
                         (let [updated (repo-ports/list-campaigns promo-repo)]
                           {:responses [(router/text-response (:user-id message)
                                          (str "⚠️ Кампания деактивирована (нельзя удалить — промокоды уже выданы "
                                               usage " пользователям).\n\n"
                                               (admin/format-campaigns-text updated))
                                          :keyboard (msg/admin-campaigns-keyboard updated))]
                            :state-update nil}))))

                   ;; Edit campaign — start edit wizard step 1: name
                   (and is-admin callback-data (str/starts-with? callback-data "edit_campaign_"))
                   (let [campaign-id (java.util.UUID/fromString (subs callback-data 14))
                         campaigns   (repo-ports/list-campaigns promo-repo)
                         campaign    (first (filter #(= (str (:id %)) (str campaign-id)) campaigns))]
                     (if campaign
                       (let [wizard {:campaign-id campaign-id
                                     :name        (:name campaign)
                                     :code        (:promo-code campaign)
                                     :expires-at  (when-let [e (:expires-at campaign)]
                                                    (try
                                                      (cond
                                                        (instance? java.sql.Timestamp e)
                                                        (.format (.toLocalDateTime ^java.sql.Timestamp e)
                                                                 (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd"))
                                                        (instance? java.time.OffsetDateTime e)
                                                        (.format (.toLocalDate ^java.time.OffsetDateTime e)
                                                                 (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd"))
                                                        :else (str e))
                                                      (catch Exception _ nil)))}]
                         {:responses [(router/text-response (:user-id message)
                                        (str "✏️ Текущее название: *" (:name wizard) "*\n\n"
                                             "Введите новое название или нажмите «Оставить текущее»:")
                                        :keyboard (msg/admin-campaign-edit-step-keyboard "edit_keep_name"))]
                          :state-update {:state :admin_campaign_edit_name
                                         :state-data {:edit-wizard wizard}}})
                       {:responses [(router/text-response (:user-id message)
                                      "⚠️ Кампания не найдена."
                                      :keyboard (msg/admin-campaigns-keyboard
                                                  (repo-ports/list-campaigns promo-repo)))]
                        :state-update nil}))

                   ;; Edit wizard: keep current name → step 2
                   (and is-admin (= callback-data "edit_keep_name"))
                   (let [wizard (get-in user [:state-data :edit-wizard] {})]
                     {:responses [(router/text-response (:user-id message)
                                    (str "🎁 Текущий промокод: `" (:code wizard) "`\n\n"
                                         "Введите новый промокод или нажмите «Оставить текущее»:")
                                    :keyboard (msg/admin-campaign-edit-step-keyboard "edit_keep_code"))]
                      :state-update {:state :admin_campaign_edit_code
                                     :state-data {:edit-wizard wizard}}})

                   ;; Edit wizard: keep current code → step 3
                   (and is-admin (= callback-data "edit_keep_code"))
                   (let [wizard (get-in user [:state-data :edit-wizard] {})]
                     {:responses [(router/text-response (:user-id message)
                                    (str "📅 Текущий срок: " (or (:expires-at wizard) "Без ограничений") "\n\n"
                                         "Введите новую дату (ГГГГ-ММ-ДД) или нажмите «Оставить текущее»:")
                                    :keyboard (msg/admin-campaign-edit-step-keyboard "edit_keep_expires"))]
                      :state-update {:state :admin_campaign_edit_expires
                                     :state-data {:edit-wizard wizard}}})

                   ;; Edit wizard: keep current expires → show summary
                   (and is-admin (= callback-data "edit_keep_expires"))
                   (let [wizard (get-in user [:state-data :edit-wizard] {})]
                     {:responses [(router/text-response (:user-id message)
                                    (admin/format-campaign-edit-summary wizard)
                                    :keyboard (msg/admin-campaign-edit-confirm-keyboard))]
                      :state-update {:state :idle :state-data {:edit-wizard wizard}}})

                   ;; Edit wizard: save
                   (and is-admin (= callback-data "campaign_edit_save"))
                   (let [wizard     (get-in user [:state-data :edit-wizard])
                         campaign-id (:campaign-id wizard)
                         expires-at (when-let [d (:expires-at wizard)]
                                      (try
                                        (java.time.OffsetDateTime/of
                                          (java.time.LocalDate/parse d)
                                          java.time.LocalTime/MIDNIGHT
                                          java.time.ZoneOffset/UTC)
                                        (catch Exception _ nil)))]
                     (if wizard
                       (do
                         (repo-ports/update-campaign! promo-repo campaign-id
                           {:name       (:name wizard)
                            :promo-code (:code wizard)
                            :expires-at expires-at})
                         (log/info "Admin updated campaign:" campaign-id)
                         (let [updated (repo-ports/list-campaigns promo-repo)]
                           {:responses [(router/text-response (:user-id message)
                                          (str "✅ Кампания *" (:name wizard) "* обновлена!\n\n"
                                               (admin/format-campaigns-text updated))
                                          :keyboard (msg/admin-campaigns-keyboard updated))]
                            :state-update {:state :idle :state-data {}}}))
                       {:responses [(router/text-response (:user-id message)
                                      "⚠️ Данные редактирования потеряны. Начните заново."
                                      :keyboard (msg/admin-campaigns-keyboard
                                                  (repo-ports/list-campaigns promo-repo)))]
                        :state-update {:state :idle :state-data {}}}))

                   ;; Edit wizard text: name input → step 2 (code)
                   (and is-admin (= user-state :admin_campaign_edit_name)
                        (get-in message [:content :text]))
                   (let [name   (str/trim (get-in message [:content :text]))
                         wizard (assoc (get-in user [:state-data :edit-wizard] {}) :name name)]
                     {:responses [(router/text-response (:user-id message)
                                    (str "🎁 Текущий промокод: `" (:code wizard) "`\n\n"
                                         "Введите новый промокод или нажмите «Оставить текущее»:")
                                    :keyboard (msg/admin-campaign-edit-step-keyboard "edit_keep_code"))]
                      :state-update {:state :admin_campaign_edit_code
                                     :state-data {:edit-wizard wizard}}})

                   ;; Edit wizard text: code input → step 3 (expires)
                   (and is-admin (= user-state :admin_campaign_edit_code)
                        (get-in message [:content :text]))
                   (let [code   (str/trim (str/upper-case (get-in message [:content :text])))
                         wizard (assoc (get-in user [:state-data :edit-wizard] {}) :code code)]
                     {:responses [(router/text-response (:user-id message)
                                    (str "📅 Текущий срок: " (or (:expires-at wizard) "Без ограничений") "\n\n"
                                         "Введите новую дату (ГГГГ-ММ-ДД) или нажмите «Оставить текущее»:")
                                    :keyboard (msg/admin-campaign-edit-step-keyboard "edit_keep_expires"))]
                      :state-update {:state :admin_campaign_edit_expires
                                     :state-data {:edit-wizard wizard}}})

                   ;; Edit wizard text: expires date input
                   (and is-admin (= user-state :admin_campaign_edit_expires)
                        (get-in message [:content :text]))
                   (let [date-str   (str/trim (get-in message [:content :text]))
                         valid-date (try (java.time.LocalDate/parse date-str) (catch Exception _ nil))]
                     (if valid-date
                       (let [wizard (assoc (get-in user [:state-data :edit-wizard] {}) :expires-at date-str)]
                         {:responses [(router/text-response (:user-id message)
                                        (admin/format-campaign-edit-summary wizard)
                                        :keyboard (msg/admin-campaign-edit-confirm-keyboard))]
                          :state-update {:state :idle :state-data {:edit-wizard wizard}}})
                       {:responses [(router/text-response (:user-id message)
                                      (msg/t :admin-campaign-invalid-date)
                                      :keyboard (msg/admin-campaign-edit-step-keyboard "edit_keep_expires"))]
                        :state-update nil}))

                   ;; Broadcast: show confirmation with target count
                   (and is-admin callback-data (str/starts-with? callback-data "notify_campaign_"))
                   (let [campaign-id (java.util.UUID/fromString (subs callback-data 16))
                         campaigns   (repo-ports/list-campaigns promo-repo)
                         campaign    (first (filter #(= (str (:id %)) (str campaign-id)) campaigns))
                         targets     (repo-ports/get-broadcast-targets promo-repo campaign-id)
                         target-count (count targets)]
                     (if (pos? target-count)
                       {:responses [(router/text-response (:user-id message)
                                      (msg/t :broadcast-confirm-prompt
                                             target-count
                                             (:name campaign))
                                      :keyboard {:type :inline
                                                 :buttons [[{:text "✅ Отправить" :callback (str "notify_confirm_" campaign-id)}]
                                                           [{:text "❌ Отмена" :callback "admin_campaigns"}]]})]
                        :state-update nil}
                       {:responses [(router/text-response (:user-id message)
                                      (msg/t :broadcast-no-targets)
                                      :keyboard (msg/admin-campaigns-keyboard campaigns))]
                        :state-update nil}))

                   ;; Broadcast: confirm and launch async
                   (and is-admin callback-data (str/starts-with? callback-data "notify_confirm_"))
                   (let [campaign-id (java.util.UUID/fromString (subs callback-data 15))
                         campaigns   (repo-ports/list-campaigns promo-repo)
                         campaign    (first (filter #(= (str (:id %)) (str campaign-id)) campaigns))
                         targets     (repo-ports/get-broadcast-targets promo-repo campaign-id)]
                     (when (and campaign (seq targets))
                       (future
                         (try
                           (broadcast/send-campaign-broadcast!
                             campaign
                             targets
                             (fn [user-id]
                               (let [text (msg/t :broadcast-notification
                                                 (:name campaign))
                                     keyboard (msg/broadcast-notify-keyboard)]
                                 (execute-responses! adapters nil
                                   [(router/text-response user-id text :keyboard keyboard)]
                                   nil)
                                 true))
                             (fn [cid uid]
                               (repo-ports/mark-broadcast-sent! promo-repo cid uid)))
                           (catch Exception e
                             (log/error e "Broadcast failed for campaign:" campaign-id)))))
                     {:responses [(router/text-response (:user-id message)
                                    (msg/t :broadcast-started)
                                    :keyboard (msg/admin-campaigns-keyboard campaigns))]
                      :state-update nil})

                   ;; Wizard: skip expires → show confirm summary
                   (and is-admin (= callback-data "campaign_skip_expires"))
                   (let [wizard (get-in user [:state-data :campaign-wizard] {})]
                     {:responses [(router/text-response (:user-id message)
                                    (admin/format-campaign-wizard-summary wizard)
                                    :keyboard (msg/admin-campaign-confirm-keyboard))]
                      :state-update {:state :idle :state-data {:campaign-wizard wizard}}})

                   ;; Wizard: confirm and create campaign
                   (and is-admin (= callback-data "campaign_confirm"))
                   (let [wizard     (get-in user [:state-data :campaign-wizard])
                         expires-at (when-let [d (:expires-at wizard)]
                                      (try
                                        (java.time.OffsetDateTime/of
                                          (java.time.LocalDate/parse d)
                                          java.time.LocalTime/MIDNIGHT
                                          java.time.ZoneOffset/UTC)
                                        (catch Exception _ nil)))]
                     (if wizard
                       (do
                         (repo-ports/create-campaign! promo-repo
                           {:name         (:name wizard)
                            :promo-code   (:code wizard)
                            :expires-at   expires-at})
                         (log/info "Admin created campaign:" (:name wizard))
                         (let [campaigns (repo-ports/list-campaigns promo-repo)]
                           {:responses [(router/text-response (:user-id message)
                                          (str "✅ Кампания *" (:name wizard) "* создана!\n\n"
                                               (admin/format-campaigns-text campaigns))
                                          :keyboard (msg/admin-campaigns-keyboard campaigns))]
                            :state-update {:state :idle :state-data {}}}))
                       {:responses [(router/text-response (:user-id message)
                                      "⚠️ Данные кампании потеряны. Начните заново."
                                      :keyboard (msg/admin-campaigns-keyboard
                                                  (repo-ports/list-campaigns promo-repo)))]
                        :state-update {:state :idle :state-data {}}}))

                   ;; Wizard text: campaign name input → step 2: code
                   (and is-admin (= user-state :admin_campaign_name)
                        (get-in message [:content :text]))
                   (let [name   (str/trim (get-in message [:content :text]))
                         wizard (assoc (get-in user [:state-data :campaign-wizard] {}) :name name)]
                     {:responses [(router/text-response (:user-id message)
                                    (msg/t :admin-campaign-enter-code)
                                    :keyboard (msg/admin-campaign-step-keyboard nil))]
                      :state-update {:state :admin_campaign_code
                                     :state-data {:campaign-wizard wizard}}})

                   ;; Wizard text: code input → step 3: expires
                   (and is-admin (= user-state :admin_campaign_code)
                        (get-in message [:content :text]))
                   (let [code   (str/trim (str/upper-case (get-in message [:content :text])))
                         wizard (assoc (get-in user [:state-data :campaign-wizard] {}) :code code)]
                     {:responses [(router/text-response (:user-id message)
                                    (msg/t :admin-campaign-enter-expires)
                                    :keyboard (msg/admin-campaign-step-keyboard "campaign_skip_expires"))]
                      :state-update {:state :admin_campaign_expires
                                     :state-data {:campaign-wizard wizard}}})

                   ;; Wizard text: expires-at date input
                   (and is-admin (= user-state :admin_campaign_expires)
                        (get-in message [:content :text]))
                   (let [date-str   (str/trim (get-in message [:content :text]))
                         valid-date (try (java.time.LocalDate/parse date-str) (catch Exception _ nil))]
                     (if valid-date
                       (let [wizard (assoc (get-in user [:state-data :campaign-wizard] {}) :expires-at date-str)]
                         {:responses [(router/text-response (:user-id message)
                                        (admin/format-campaign-wizard-summary wizard)
                                        :keyboard (msg/admin-campaign-confirm-keyboard))]
                          :state-update {:state :idle :state-data {:campaign-wizard wizard}}})
                       {:responses [(router/text-response (:user-id message)
                                      (msg/t :admin-campaign-invalid-date)
                                      :keyboard (msg/admin-campaign-step-keyboard "campaign_skip_expires"))]
                        :state-update nil}))

                   ;; View pending reviews (resets skipped set)
                   (and is-admin (= callback-data "admin_reviews"))
                   (let [reviews (repo-ports/find-all-pending-reviews review-repo)
                         total   (count reviews)]
                     (if (seq reviews)
                       (let [review     (first reviews)
                             has-photos (seq (:photo-urls review))]
                         {:responses [(router/text-response (:user-id message)
                                        (admin/format-review-for-admin review 0 total)
                                        :keyboard (admin/review-moderation-keyboard (:id review) has-photos))]
                          :state-update {:state :idle :state-data {:skipped-review-ids []}}})
                       {:responses [(router/text-response (:user-id message)
                                      (msg/t :admin-reviews-empty)
                                      :keyboard (admin/admin-menu-keyboard))]
                        :state-update nil}))

                   ;; Skip review - advance to next non-skipped
                   (and is-admin callback-data (str/starts-with? callback-data "skip_review_"))
                   (let [review-id-str   (subs callback-data 12)
                         skipped-ids     (set (map str (get-in user [:state-data :skipped-review-ids] [])))
                         new-skipped     (conj skipped-ids review-id-str)
                         all-pending     (repo-ports/find-all-pending-reviews review-repo)
                         remaining       (remove #(contains? new-skipped (str (:id %))) all-pending)
                         total           (count all-pending)]
                     (log/info "Admin skipped review:" review-id-str "total skipped:" (count new-skipped))
                     (if (seq remaining)
                       (let [review     (first remaining)
                             has-photos (seq (:photo-urls review))
                             idx        (- total (count remaining))]
                         {:responses [(router/text-response (:user-id message)
                                        (admin/format-review-for-admin review idx total)
                                        :keyboard (admin/review-moderation-keyboard (:id review) has-photos))]
                          :state-update {:state :idle :state-data {:skipped-review-ids (vec new-skipped)}}})
                       ;; All reviews skipped — reset and show message
                       {:responses [(router/text-response (:user-id message)
                                      (str "⏭️ Все отзывы просмотрены (" (count new-skipped) " пропущено).\n\n"
                                           "Нажмите «Отзывы на модерации» чтобы пройти их заново.")
                                      :keyboard (admin/admin-menu-keyboard))]
                        :state-update {:state :idle :state-data {:skipped-review-ids []}}}))
                   
                   ;; Show review photos
                   (and is-admin callback-data (str/starts-with? callback-data "show_photos_"))
                   (let [review-id (java.util.UUID/fromString (subs callback-data 12))
                         review (repo-ports/find-review review-repo review-id)]
                     (if (and review (seq (:photo-urls review)))
                       {:responses [(router/photos-response (:user-id message)
                                      (:photo-urls review)
                                      :caption (str "📷 Фото к отзыву\nID: `" review-id "`"))
                                    (router/text-response (:user-id message)
                                      "⬆️ Фото отзыва выше.\n\nВыберите действие:"
                                      :keyboard (admin/review-moderation-keyboard review-id true))]
                        :state-update nil}
                       {:responses [(router/text-response (:user-id message)
                                      "❌ Фото не найдены"
                                      :keyboard (admin/admin-menu-keyboard))]
                        :state-update nil}))
                   
                   ;; Approve review - publish to both Telegram and MAX channels
                   (and is-admin callback-data (str/starts-with? callback-data "approve_"))
                   (let [review-id (java.util.UUID/fromString (subs callback-data 8))
                         review (repo-ports/find-review review-repo review-id)
                         ;; Telegram channel (from :platforms :telegram :promo)
                         tg-channel-username (get-in config [:platforms :telegram :promo :channel-username])
                         ;; MAX channel (from :platforms :max :channel)
                         max-channel-id (get-in config [:platforms :max :channel :id])]
                     (repo-ports/publish-review! review-repo review-id nil)
                     (log/info "Review approved:" review-id)

                     (let [content-text  (or (:content-text review) "")
                           author-name  (:author-name review)
                           author-link  (:author-link review)
                           author-display (cond
                                            (and author-name author-link) (str "[" author-name "](" author-link ")")
                                            author-name                   (str "_" author-name "_")
                                            :else                         nil)
                           text (if author-display
                                  (str "⭐️ *Новый отзыв!*\n\n"
                                       content-text
                                       "\n\n" author-display "\n\n#отзывы")
                                  (msg/t :review-published content-text))

                           ;; Telegram publish action
                           tg-action (when (and tg-channel-username review)
                                       (let [clean-username (if (str/starts-with? tg-channel-username "@")
                                                              tg-channel-username
                                                              (str "@" tg-channel-username))
                                             channel-id (str "telegram_" clean-username)]
                                         (if (seq (:photo-urls review))
                                           (router/photos-response channel-id (:photo-urls review) :caption text)
                                           (router/text-response channel-id text))))

                           ;; MAX publish action (use max_channel_<id> format for channels)
                           max-action (when (and max-channel-id review)
                                        (let [channel-id (str "max_channel_" max-channel-id)]
                                          (if (seq (:photo-urls review))
                                            (router/photos-response channel-id (:photo-urls review) :caption text)
                                            (router/text-response channel-id text))))

                           ;; Build status message
                           published-to (cond-> []
                                          tg-action (conj "Telegram")
                                          max-action (conj "MAX"))
                           status-text (if (seq published-to)
                                         (str "✅ Отзыв одобрен и опубликован в: " (clojure.string/join ", " published-to) "!")
                                         "✅ Отзыв одобрен (каналы не настроены).")]

                       (let [next-responses (next-review-responses review-repo (:user-id message))]
                         {:responses (vec (concat
                                           (when tg-action [tg-action])
                                           (when max-action [max-action])
                                           [(router/text-response (:user-id message) status-text)]
                                           next-responses
                                           [(router/text-response (:user-id review)
                                              (msg/t :review-approved)
                                              :keyboard (msg/main-menu-keyboard))]))
                          :state-update {:state :idle :state-data {:skipped-review-ids []}}})))
                   
                   ;; Reject review
                   (and is-admin callback-data (str/starts-with? callback-data "reject_"))
                   (let [review-id (java.util.UUID/fromString (subs callback-data 7))
                         review    (repo-ports/find-review review-repo review-id)]
                     (repo-ports/reject-review! review-repo review-id)
                     (log/info "Review rejected:" review-id)
                     (let [next-responses (next-review-responses review-repo (:user-id message))]
                       {:responses (vec (concat
                                         [(router/text-response (:user-id message) "❌ Отзыв отклонён.")]
                                         next-responses
                                         [(router/text-response (:user-id review)
                                            (msg/t :review-rejected)
                                            :keyboard (msg/main-menu-keyboard))]))
                        :state-update {:state :idle :state-data {:skipped-review-ids []}}}))
                   
                   ;; Admin stats
                   (and is-admin (= callback-data "admin_stats"))
                   (let [user-count (repo-ports/count-users stats-repo)
                         pending-count (repo-ports/count-pending-reviews stats-repo)
                         campaigns (repo-ports/list-campaigns promo-repo)
                         active-count (count (filter :is-active campaigns))
                         stats-text (admin/format-stats {:total-users user-count
                                                          :active-campaigns active-count
                                                          :total-campaigns (count campaigns)
                                                          :pending-reviews pending-count})]
                     {:responses [(router/text-response (:user-id message)
                                    stats-text
                                    :keyboard (admin/admin-menu-keyboard))]
                      :state-update nil})
                   
                   ;; My promos history
                   (= callback-data "my_promos")
                   (let [resp (my-promos-response promo-repo (:user-id message))]
                     {:responses [(router/text-response (:user-id message)
                                    (:text resp)
                                    :keyboard (:keyboard resp))]
                      :state-update nil})

                   ;; Promo request
                   (= callback-data "menu_promo")
                   (let [promo-result (handle-promo-request ctx (:user-id message) platform)]
                     (if (= :already-issued (:reason promo-result))
                       ;; Redirect to my promos
                       (let [resp (my-promos-response promo-repo (:user-id message))]
                         {:responses [(router/text-response (:user-id message)
                                        (str (msg/t :promo-already-issued-redirect)
                                             "\n\n" (:text resp))
                                        :keyboard (:keyboard resp))]
                          :state-update nil})
                       {:responses [(router/text-response (:user-id message)
                                      (:text promo-result)
                                      :keyboard (:keyboard promo-result))]
                        :state-update nil}))
                   
                   ;; Check subscription (retry promo)
                   (= callback-data "check_subscription")
                   (let [promo-response (handle-promo-request ctx (:user-id message) platform)]
                     {:responses [(router/text-response (:user-id message)
                                    (:text promo-response)
                                    :keyboard (:keyboard promo-response))]
                      :state-update nil})
                   
                   ;; Support request
                   (= callback-data "menu_support")
                   (if (config/chatwoot-enabled? config)
                     ;; Chatwoot enabled: start support flow through router
                     (router/route-message message user)
                     ;; Chatwoot disabled: show external link
                     (let [support-link (case platform
                                          :telegram (get-in config [:platforms :telegram :support-link])
                                          :max (or (get-in config [:platforms :max :support-link])
                                                   (get-in config [:platforms :telegram :support-link]))
                                          (get-in config [:platforms :telegram :support-link]))]
                       (if support-link
                         {:responses [(router/text-response (:user-id message)
                                        "📞 *Техподдержка*\n\nНажмите кнопку ниже, чтобы связаться с нашим менеджером:"
                                        :keyboard {:type :inline
                                                   :buttons [[{:text "💬 Написать менеджеру" :url support-link}]
                                                             [{:text "🔙 В меню" :callback "back_to_menu"}]]})]
                          :state-update nil}
                         ;; Fallback to router if no link configured
                         (router/route-message message user))))
                   
                   ;; Submit review - show preview in published format
                   (= callback-data "submit_review")
                   (let [state-data    (:state-data user)
                         photos        (:photos state-data)
                         text          (:text state-data)]
                     (if (or (seq photos) text)
                       (let [author-name    (build-author-name (:user-info message) user)
                             author-link    (build-author-link (:user-info message) message)
                             author-display (cond
                                              (and author-name author-link) (str "[" author-name "](" author-link ")")
                                              author-name                   (str "_" author-name "_")
                                              :else                         nil)
                             published-text (if author-display
                                              (str "⭐️ *Новый отзыв!*\n\n"
                                                   (or text "")
                                                   "\n\n" author-display "\n\n#отзывы")
                                              (msg/t :review-published (or text "")))
                             confirm-prompt "👆 Так будет выглядеть ваш отзыв в канале."]
                         {:responses (if (seq photos)
                                       [(router/photos-response (:user-id message) photos :caption published-text)
                                        (router/text-response (:user-id message)
                                          confirm-prompt
                                          :keyboard (msg/review-confirm-keyboard))]
                                       [(router/text-response (:user-id message)
                                          published-text
                                          :keyboard (msg/review-confirm-keyboard))])
                          :state-update {:state :review_confirm :state-data state-data}})
                       {:responses [(router/text-response (:user-id message)
                                      "⚠️ Пожалуйста, добавьте фото или текст для отзыва.")]
                        :state-update nil}))

                   ;; Confirm review - actually save to database
                   (= callback-data "confirm_review")
                   (let [state-data  (:state-data user)
                         photos      (:photos state-data)
                         text        (:text state-data)
                         author-name (build-author-name (:user-info message) user)
                         author-link (build-author-link (:user-info message) message)]
                     (log/info "Confirming review for user:" (:user-id message)
                               "photos:" (count photos) "text:" (some? text))
                     (repo-ports/create-review! review-repo
                                                (:user-id message)
                                                text
                                                photos
                                                author-name
                                                author-link)
                     {:responses [(router/text-response (:user-id message)
                                    "✅ Ваш отзыв отправлен на модерацию!\n\nМы уведомим вас о результате.")
                                  (router/menu-response (:user-id message))]
                      :state-update {:state :idle :state-data {}}})
                   
                   ;; End support session
                   (= callback-data "end_support")
                   (do
                     ;; Close ticket and resolve Chatwoot conversation async
                     (when (config/chatwoot-enabled? config)
                       (future
                         (try
                           (when-let [ticket (repo-ports/find-active-ticket
                                               (:ticket-repo ctx) (:user-id message))]
                             (repo-ports/close-ticket! (:ticket-repo ctx) (:id ticket))
                             (when-let [conv-id (:chatwoot-conv-id ticket)]
                               (crm-ports/resolve-conversation! (:crm-adapter ctx) conv-id)))
                           (catch Exception e
                             (log/error e "Failed to close Chatwoot session for:" (:user-id message))))))
                     {:responses [(router/text-response (:user-id message) (msg/t :action-cancelled))
                                  (router/menu-response (:user-id message))]
                      :state-update {:state :idle :state-data {}}})

                   ;; Back to menu
                   (= callback-data "back_to_menu")
                   {:responses [(router/menu-response (:user-id message))]
                    :state-update {:state :idle :state-data {}}}

                   ;; Delete photos from draft
                   (= callback-data "delete_photos")
                   (let [state-data (assoc (or (:state-data user) {}) :photos [])]
                     (log/info "Deleting draft photos for:" (:user-id message))
                     {:responses [(router/text-response (:user-id message)
                                    (msg/format-draft-text state-data)
                                    :keyboard (msg/draft-panel-keyboard state-data))]
                      :state-update {:state :review_editing :state-data state-data}})

                   ;; Delete text from draft
                   (= callback-data "delete_text")
                   (let [state-data (assoc (or (:state-data user) {}) :text nil)]
                     (log/info "Deleting draft text for:" (:user-id message))
                     {:responses [(router/text-response (:user-id message)
                                    (msg/format-draft-text state-data)
                                    :keyboard (msg/draft-panel-keyboard state-data))]
                      :state-update {:state :review_editing :state-data state-data}})

                   ;; Email input — validate and issue promo
                   (and (= user-state :waiting_email)
                        (get-in message [:content :text]))
                   (let [email (clojure.string/trim (get-in message [:content :text]))]
                     (if (re-matches #"(?i)[a-z0-9._%+\-]+@[a-z0-9.\-]+\.[a-z]{2,}" email)
                       (let [resp (handle-email-promo-request ctx (:user-id message) email)]
                         {:responses [(router/text-response (:user-id message)
                                        (:text resp)
                                        :keyboard (:keyboard resp))]
                          :state-update {:state :idle :state-data {}}})
                       {:responses [(router/text-response (:user-id message)
                                       (msg/t :email-invalid)
                                       :keyboard (msg/email-ask-keyboard))]
                        :state-update nil}))

                   ;; Default - use router
                   :else
                   (router/route-message message user))
          
          {:keys [responses state-update]} result]

      ;; Debug logging for message processing
      (log/debug "Message processed:"
                 {:user-id (:user-id message)
                  :platform platform
                  :user-state user-state
                  :msg-type (:type message)
                  :callback-data callback-data
                  :state-update state-update})

      ;; Update state if needed (with error handling)
      (when state-update
        (log/info "Updating state for user:" (:user-id message) "to:" (:state state-update))
        (try
          (repo-ports/update-state! user-repo
                                     (:user-id message)
                                     (:state state-update)
                                     (:state-data state-update))
          (catch Exception e
            (log/error e "Failed to update user state, continuing anyway:" (:user-id message)))))

      ;; Chatwoot CRM async operations (non-blocking)
      (when (config/chatwoot-enabled? config)
        ;; Start support session when user enters :chat_mode with pending init
        (when (and state-update
                   (= (:state state-update) :chat_mode)
                   (get-in state-update [:state-data :pending-support-init]))
          (let [support-deps {:crm-adapter  (:crm-adapter ctx)
                              :user-repo    user-repo
                              :ticket-repo  (:ticket-repo ctx)}
                initial-text (get-in state-update [:state-data :initial-message])]
            (future
              (try
                (support/start-support-session! support-deps user initial-text)
                ;; Clear pending flag
                (repo-ports/update-state! user-repo (:user-id message) :chat_mode
                  {:initial-message initial-text})
                (catch Exception e
                  (log/error e "Async: failed to start support session for:" (:user-id message)))))))

        ;; Forward messages when user is in :chat_mode
        (when (and (= user-state :chat_mode)
                   (nil? state-update)  ; Not a state transition
                   (or (get-in message [:content :text])
                       (get-in message [:content :photo-file-id])))
          (let [support-deps {:crm-adapter (:crm-adapter ctx)
                              :ticket-repo (:ticket-repo ctx)}
                photo-id (get-in message [:content :photo-file-id])
                photo-url (when photo-id
                            (case platform
                              :telegram (tg-api/resolve-file-to-url (:tg-client adapters) photo-id)
                              :max photo-id
                              nil))]
            (future
              (try
                (support/forward-message! support-deps (:user-id message)
                  {:text      (get-in message [:content :text])
                   :photo-url photo-url})
                (catch Exception e
                  (log/error e "Async: failed to forward message for:" (:user-id message))))))))

      ;; Execute responses (with error handling)
      (try
        (execute-responses! adapters user-repo responses (:callback-id message))
        (catch Exception e
          (log/error e "Failed to execute responses, attempting to send error message")
          ;; Try to send error notification to user
          (try
            (execute-responses! adapters user-repo
              [(router/text-response (:user-id message)
                 "⚠️ Произошла ошибка при отправке ответа. Попробуйте позже.")]
              nil)
            (catch Exception e2
              (log/error e2 "Failed to send error notification to user"))))))

    (catch clojure.lang.ExceptionInfo e
      ;; Check if this is our channel-post-handled signal
      (when-not (= "channel-post-handled" (.getMessage e))
        (log/error e "Error handling message:" (:message-id message))
        ;; Try to notify user
        (try
          (let [platform (:platform message)
                user-id (:user-id message)]
            (case platform
              :telegram
              (let [{:keys [original-id]} (entities/parse-internal-id user-id)]
                (tg-api/send-message (:tg-client adapters)
                                     original-id
                                     (msg/t :technical-error)
                                     {}))
              :max
              (let [{:keys [original-id chat-id]} (entities/parse-internal-id user-id)
                    effective-chat-id (when (not= chat-id original-id) chat-id)]
                (max-api/send-message (:max-client adapters)
                                      effective-chat-id
                                      (msg/t :technical-error)
                                      {:user-id original-id}))
              (log/error "Cannot send error notification - unknown platform:" platform)))
          (catch Exception e2
            (log/error e2 "Failed to send error notification")))))
    (catch Exception e
      (log/error e "Critical error handling message:" (:message-id message))
      ;; Last resort - try to notify user
      (try
        (let [platform (:platform message)
              user-id (:user-id message)]
          (case platform
            :telegram
            (let [{:keys [original-id]} (entities/parse-internal-id user-id)]
              (tg-api/send-message (:tg-client adapters)
                                   original-id
                                   (msg/t :technical-error)
                                   {}))
            :max
            (let [{:keys [original-id chat-id]} (entities/parse-internal-id user-id)
                  effective-chat-id (when (not= chat-id original-id) chat-id)]
              (max-api/send-message (:max-client adapters)
                                    effective-chat-id
                                    (msg/t :technical-error)
                                    {:user-id original-id}))
            (log/error "Cannot send error notification - unknown platform:" platform)))
        (catch Exception e2
          (log/error e2 "Failed to send critical error notification"))))))

;; ============================================
;; Lifecycle
;; ============================================

(defn start!
  "Start the application"
  [config]
  (log/info "Starting chatbot application...")
  
  ;; Create database connection
  (let [ds (db/create-datasource (get-in config [:database :url]))
        _ (when-not (db/health-check ds)
            (throw (ex-info "Database connection failed" {})))

        ;; Run database migrations
        _ (migrations/run-migrations! ds)

        ;; Create repositories
        user-repo (repos/create-user-repo ds)
        ticket-repo (repos/create-ticket-repo ds)
        promo-repo (repos/create-promo-repo ds)
        review-repo (repos/create-review-repo ds)
        stats-repo (repos/create-stats-repo ds)

        ;; Seed default welcome campaign from env if none active exists
        _ (let [welcome-code (get-in config [:platforms :telegram :promo :welcome-code])]
            (repo-ports/ensure-welcome-campaign! promo-repo welcome-code))

        ;; Seed default email promo campaign from env if none active exists
        _ (let [email-code (get-in config [:features :email-promo-code])]
            (repo-ports/ensure-email-campaign! promo-repo email-code))

        ;; Initialize Telegram adapter if enabled
        tg-enabled (get-in config [:platforms :telegram :enabled])
        [tg-client tg-poller tg-bot-info] (when tg-enabled
                                            (let [token (get-in config [:platforms :telegram :token])
                                                  client (tg-api/make-client token)
                                                  response (tg-api/get-me client)
                                                  ;; Circuit breaker wraps result, extract inner result
                                                  bot-info (or (:result response) response)]
                                              (if (:ok bot-info)
                                                (let [bot-data (get-in bot-info [:result])]
                                                  (log/info "✅ Telegram connected as bot:" (:username bot-data))
                                                  [client (tg-poller/create-poller client) bot-data])
                                                (throw (ex-info "Failed to connect to Telegram" bot-info)))))
        
        ;; Initialize MAX adapter if enabled
        max-enabled (get-in config [:platforms :max :enabled])
        [max-client max-poller-inst] (when max-enabled
                                       (let [token (get-in config [:platforms :max :token])
                                             client (max-api/make-client token)
                                             bot-info (max-api/get-me client)]
                                         (if (:ok bot-info)
                                           (do
                                             (log/info "✅ MAX connected as bot:" (get-in bot-info [:result :name]))
                                             [client (max-poller/create-poller client)])
                                           (do
                                             (log/error "MAX API response:" bot-info)
                                             (throw (ex-info "Failed to connect to MAX" bot-info))))))
        
        ;; Initialize Chatwoot adapter if enabled
        chatwoot-enabled (config/chatwoot-enabled? config)
        [cw-client crm-adapter] (when chatwoot-enabled
                                   (let [cw-cfg (:chatwoot config)
                                         client (cw-api/make-client
                                                  (:base-url cw-cfg)
                                                  (:api-token cw-cfg)
                                                  (:account-id cw-cfg)
                                                  (:inbox-id cw-cfg))
                                         adapter (cw-adapter/create-adapter client)]
                                     (log/info "Chatwoot CRM adapter initialized")
                                     [client adapter]))

        ;; Build adapters object
        adapters {:tg-client tg-client
                  :max-client max-client}

        ;; Build context for handler
        ctx {:adapters adapters
             :user-repo user-repo
             :ticket-repo ticket-repo
             :promo-repo promo-repo
             :review-repo review-repo
             :stats-repo stats-repo
             :crm-adapter crm-adapter
             :config config
             :ds ds}]
    
    ;; Log configuration
    (log/info "Configuration summary:")
    (if-let [channel (get-in config [:platforms :telegram :promo :channel-username])]
      (log/info "  Promo channel (Telegram): @" channel)
      (log/warn "  No Telegram promo channel configured"))

    (if-let [max-ch (get-in config [:platforms :max :channel :id])]
      (log/info "  Promo channel (MAX):" max-ch)
      (when max-enabled
        (log/warn "  MAX enabled but MAX_CHANNEL_ID not configured - subscription checks will be skipped")))

    (when-let [support (get-in config [:platforms :telegram :support-link])]
      (log/info "  Support link (Telegram):" support))
    
    ;; Start polling for enabled adapters
    (when tg-enabled
      (log/info "Starting Telegram polling...")
      (tg-poller/start-polling tg-poller (partial handle-message ctx)))
    
    (when max-enabled
      (log/info "Starting MAX polling...")
      (max-poller/start-polling max-poller-inst (partial handle-message ctx)))

    ;; Start HTTP server with Chatwoot webhook (if Chatwoot enabled)
    (let [http-stop-fn (when chatwoot-enabled
                         (let [webhook-handler (cw-webhook/create-webhook-handler
                                                {:ticket-repo ticket-repo
                                                 :user-repo user-repo
                                                 :webhook-secret (get-in config [:chatwoot :webhook-secret])
                                                 :execute-responses-fn
                                                 (fn [responses]
                                                   (execute-responses! adapters nil responses nil))})
                               app (web-server/create-app webhook-handler)
                               host (get-in config [:server :host])
                               port (get-in config [:server :port])]
                           (web-server/start-server! app host port)))]

      ;; Store state for shutdown
      (reset! app-state {:ds ds
                         :tg-poller tg-poller
                         :max-poller max-poller-inst
                         :http-stop-fn http-stop-fn
                         :config config
                         :bot-info {:username (:username tg-bot-info)
                                    :id (:id tg-bot-info)}})

      (log/info "✅ Bot started successfully! Polling for messages...")
      (log/info "Telegram enabled:" tg-enabled)
      (log/info "MAX enabled:" max-enabled)
      (log/info "Chatwoot enabled:" chatwoot-enabled)

      @app-state)))

(defn stop!
  "Stop the application gracefully"
  []
  (when-let [{:keys [ds tg-poller max-poller http-stop-fn]} @app-state]
    (log/info "Stopping chatbot application...")
    (when tg-poller
      (tg-poller/stop-polling tg-poller))
    (when max-poller
      (max-poller/stop-polling max-poller))
    (when http-stop-fn
      (web-server/stop-server! http-stop-fn))
    (db/close-datasource ds)
    (reset! app-state nil)
    (log/info "Application stopped")))

;; ============================================
;; Entry Point
;; ============================================

(defn -main
  "Main entry point"
  [& args]
  (try
    ;; Load and validate config
    (let [config (config/load-and-validate!)]
      ;; Add shutdown hook
      (.addShutdownHook (Runtime/getRuntime)
        (Thread. ^Runnable stop!))
      
      ;; Start application
      (start! config)
      
      ;; Keep main thread alive
      @(promise))
    
    (catch Exception e
      (log/error e "Failed to start application")
      (System/exit 1))))
