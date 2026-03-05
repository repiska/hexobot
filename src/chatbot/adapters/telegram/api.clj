(ns chatbot.adapters.telegram.api
  "Telegram Bot API HTTP client.
   Handles all communication with Telegram servers.

   Rate limits:
   - ~30 messages/second to same chat
   - ~30 messages/second globally for bot"
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [chatbot.utils.errors :as errors]
            [chatbot.utils.circuit-breaker :as cb]
            [chatbot.utils.rate-limiter :as rl]))

(def base-url "https://api.telegram.org/bot")

;; Circuit breaker for Telegram API (5 failures, 30s timeout)
(defonce telegram-breaker
  (cb/register-breaker! :telegram
    (cb/create-breaker "telegram-api" 5 30000 2)))

;; Rate limiter for Telegram API (25 requests/second - slightly under 30 limit)
(defonce telegram-limiter
  (rl/register-limiter! :telegram
    (rl/create-named-limiter "telegram-api" 25 1000)))

(defn make-client
  "Create a Telegram API client with the given token"
  [token]
  {:token token
   :base-url (str base-url token)
   :breaker telegram-breaker})

(defn- api-request
  "Make a request to Telegram API with comprehensive error handling"
  [{:keys [base-url breaker]} method params]
  (when-not (rl/acquire-blocking! telegram-limiter 5000)
    (log/warn "Telegram rate limit timeout for method:" method))
  (let [url (str base-url "/" method)]
    (cb/execute breaker
      (fn []
        (try
          (let [req-body (errors/safe-generate-json params)
                {:keys [status body error]} @(http/post url
                                                {:headers {"Content-Type" "application/json"}
                                                 :body req-body
                                                 :timeout 30000})]
            (cond
              ;; Network/timeout error
              error
              (do
                (log/error "Telegram API network error:" error "method:" method)
                (throw (ex-info "Network error"
                                {:type :network-error
                                 :method method
                                 :error (str error)})))

              ;; Rate limit (HTTP 429)
              (= 429 status)
              (do
                (log/warn "Telegram API rate limit hit for method:" method)
                (let [result (errors/safe-parse-json body {:ok false})
                      retry-after (or (get-in result [:parameters :retry_after]) 60)]
                  {:ok false
                   :error "Rate limit exceeded"
                   :retry? true
                   :retry-after retry-after
                   :status 429}))

              ;; Other HTTP errors
              (>= status 400)
              (do
                (log/error "Telegram API HTTP error" status "for method:" method "body:" body)
                {:ok false
                 :error (str "HTTP " status)
                 :status status
                 :body body})

              ;; Success - parse body
              :else
              (let [result (errors/safe-parse-json body {:ok false :error "Invalid JSON"})]
                (when-not (:ok result)
                  (log/warn "Telegram API returned error:" result))
                result)))
          (catch Exception e
            (log/error e "Unexpected error in Telegram API request:" method)
            (throw e)))))))

(defn- api-request-multipart
  "Make a multipart request to Telegram API (for file uploads)"
  [{:keys [base-url breaker]} method params]
  (when-not (rl/acquire-blocking! telegram-limiter 5000)
    (log/warn "Telegram rate limit timeout for multipart method:" method))
  (let [url (str base-url "/" method)]
    (cb/execute breaker
      (fn []
        (try
          (let [{:keys [status body error]} @(http/post url
                                                {:multipart (mapv (fn [[k v]]
                                                                    {:name (name k)
                                                                     :content (str v)})
                                                                  params)
                                                 :timeout 30000})]
            (cond
              error
              (do
                (log/error "Telegram API multipart error:" error "method:" method)
                (throw (ex-info "Network error"
                                {:type :network-error
                                 :method method
                                 :error (str error)})))

              (= 429 status)
              (do
                (log/warn "Telegram API rate limit hit for multipart:" method)
                {:ok false :error "Rate limit exceeded" :retry? true :status 429})

              (>= status 400)
              (do
                (log/error "Telegram API HTTP error" status "for multipart:" method)
                {:ok false :error (str "HTTP " status) :status status})

              :else
              (errors/safe-parse-json body {:ok false :error "Invalid JSON"})))
          (catch Exception e
            (log/error e "Unexpected error in Telegram multipart request:" method)
            (throw e)))))))

;; ============================================
;; Keyboard Builders (must be defined before API methods that use them)
;; ============================================

(defn- build-inline-button
  [{:keys [text callback url]}]
  (cond-> {:text text}
    callback (assoc :callback_data callback)
    url (assoc :url url)))

(defn build-keyboard
  "Build a keyboard structure for Telegram API"
  [{:keys [type buttons]}]
  (case type
    :inline
    {:inline_keyboard (mapv #(mapv build-inline-button %) buttons)}
    
    :reply
    {:keyboard (mapv #(mapv (fn [b] {:text (:text b)}) %) buttons)
     :resize_keyboard true
     :one_time_keyboard false}
    
    :remove
    {:remove_keyboard true}
    
    nil))

;; ============================================
;; API Methods
;; ============================================

(defn get-me
  "Get bot information. Useful for testing connection."
  [client]
  (api-request client "getMe" {}))

(defn get-updates
  "Long polling for updates.
   offset: ID of first update to return
   timeout: seconds to wait for updates (long polling)"
  [client {:keys [offset timeout] :or {timeout 30}}]
  (api-request client "getUpdates"
    (cond-> {:timeout timeout
             :allowed_updates ["message" "callback_query" "channel_post"]}
      offset (assoc :offset offset))))

(defn send-message
  "Send a text message to a chat"
  [client chat-id text {:keys [parse-mode keyboard disable-notification]
                         :or {parse-mode "Markdown"}}]
  (api-request client "sendMessage"
    (cond-> {:chat_id chat-id
             :text text
             :parse_mode parse-mode}
      disable-notification (assoc :disable_notification true)
      keyboard (assoc :reply_markup (build-keyboard keyboard)))))

(defn edit-message
  "Edit text and inline keyboard of an existing bot message.
   Returns {:ok true, :result {:message_id ...}} on success,
   or {:ok false ...} if editing failed (e.g., message too old, not text message)."
  [client chat-id message-id text {:keys [keyboard]}]
  (api-request client "editMessageText"
    (cond-> {:chat_id    chat-id
             :message_id (if (string? message-id)
                           (Long/parseLong message-id)
                           message-id)
             :text       text
             :parse_mode "Markdown"}
      keyboard (assoc :reply_markup (build-keyboard keyboard)))))

(defn send-photo
  "Send a photo to a chat"
  [client chat-id photo {:keys [caption parse-mode keyboard]}]
  (api-request client "sendPhoto"
    (cond-> {:chat_id chat-id
             :photo photo}
      caption (assoc :caption caption)
      parse-mode (assoc :parse_mode parse-mode)
      keyboard (assoc :reply_markup (build-keyboard keyboard)))))

(defn send-media-group
  "Send multiple photos as a media group"
  [client chat-id photos {:keys [caption]}]
  (let [media (mapv (fn [idx photo]
                      (cond-> {:type "photo" :media photo}
                        (and caption (zero? idx)) (assoc :caption caption :parse_mode "Markdown")))
                    (range) photos)]
    (api-request client "sendMediaGroup"
      {:chat_id chat-id
       :media media})))

(defn answer-callback-query
  "Answer a callback query"
  [client callback-id {:keys [text show-alert]}]
  (api-request client "answerCallbackQuery"
    (cond-> {:callback_query_id callback-id}
      text (assoc :text text)
      show-alert (assoc :show_alert show-alert))))

(defn get-chat-member
  "Get information about a chat member"
  [client chat-id user-id]
  (api-request client "getChatMember"
    {:chat_id chat-id
     :user_id user-id}))

(defn get-file
  "Get file info by file_id"
  [client file-id]
  (api-request client "getFile" {:file_id file-id}))

(defn get-file-url
  "Get downloadable URL for a file"
  [{:keys [token]} file-path]
  (str "https://api.telegram.org/file/bot" token "/" file-path))

(defn resolve-file-to-url
  "Convert Telegram file_id to a downloadable URL.
   Returns URL string or nil on error.
   Useful for cross-platform photo sharing (MAX needs URLs, not file_ids)."
  [client file-id]
  (when file-id
    (let [result (get-file client file-id)]
      (when (:ok result)
        (get-file-url client (get-in result [:result :file_path]))))))

;; ============================================
;; Subscription Check
;; ============================================

(defn check-subscription
  "Check if user is subscribed to a channel.
   Returns true if member/admin/creator, false otherwise.
   Note: circuit-breaker wraps response as {:ok true :result <tg-response>},
   so Telegram's ok/result are at [:result :ok] and [:result :result ...]."
  [client channel-id user-id]
  (let [result  (get-chat-member client channel-id user-id)
        tg-ok?  (get-in result [:result :ok])
        status  (get-in result [:result :result :status])]
    (and tg-ok?
         (contains? #{"member" "administrator" "creator"} status))))
