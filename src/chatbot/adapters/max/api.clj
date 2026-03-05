(ns chatbot.adapters.max.api
  "MAX Bot API HTTP client.
   Handles all communication with MAX servers.
   Base URL: https://platform-api.max.ru
   Auth: Header 'Authorization: <token>' (without Bearer!)"
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [chatbot.utils.errors :as errors]
            [chatbot.utils.circuit-breaker :as cb]
            [chatbot.utils.rate-limiter :as rl])
  (:import [java.net URLEncoder]))

(def base-url "https://platform-api.max.ru")

;; Circuit breaker for MAX API (5 failures, 30s timeout)
(defonce max-breaker
  (cb/register-breaker! :max
    (cb/create-breaker "max-api" 5 30000 2)))

;; Rate limiter for MAX API (20 requests/second - conservative estimate)
(defonce max-limiter
  (rl/register-limiter! :max
    (rl/create-named-limiter "max-api" 20 1000)))

(defn make-client
  "Create a MAX API client with the given token"
  [token]
  {:token token
   :base-url base-url
   :breaker max-breaker})

(defn- auth-headers
  "Create headers with MAX authorization (no Bearer prefix!)"
  [token]
  {"Authorization" token
   "Content-Type" "application/json"})

(defn- url-encode
  "URL-encode a string value for use in query parameters."
  [value]
  (when (some? value)
    (URLEncoder/encode (str value) "UTF-8")))

(defn- build-query-string
  "Build URL query string with proper encoding."
  [params]
  (when (seq params)
    (->> params
         (filter (fn [[_ v]] (some? v)))
         (map (fn [[k v]] (str (url-encode (name k)) "=" (url-encode v))))
         (clojure.string/join "&"))))
(defn- api-request-get
  "Make a GET request to MAX API"
  [{:keys [base-url token breaker]} endpoint params]
  (when-not (rl/acquire-blocking! max-limiter 5000)
    (log/warn "MAX rate limit timeout for GET:" endpoint))
  (let [url (str base-url endpoint)
        query-string (build-query-string params)]
    (cb/execute breaker
      (fn []
        (try
          (let [full-url (if query-string
                           (str url "?" query-string)
                           url)
                {:keys [status body error]} @(http/get full-url
                                               {:headers (auth-headers token)
                                                :timeout 60000})] ;; 60s for long polling
            (cond
              error
              (do
                (log/error "MAX API network error:" error "endpoint:" endpoint)
                (throw (ex-info "Network error"
                                {:type :network-error
                                 :endpoint endpoint
                                 :error (str error)})))

              (= 429 status)
              (do
                (log/warn "MAX API rate limit hit for:" endpoint)
                {:ok false :error "Rate limit exceeded" :retry? true :status 429})

              (>= status 400)
              (do
                (log/error "MAX API HTTP error" status "for:" endpoint)
                {:ok false :error (str "HTTP " status) :status status :body body})

              :else
              (let [result (errors/safe-parse-json body {:ok false :error "Invalid JSON"})]
                result)))
          (catch Exception e
            (log/error e "Unexpected error in MAX API GET request:" endpoint)
            (throw e)))))))

(defn- api-request-post
  "Make a POST request to MAX API.
   query-params are passed as URL query string (e.g., chat_id, user_id).
   body-params are sent as JSON body."
  [{:keys [base-url token breaker]} endpoint query-params body-params]
  (when-not (rl/acquire-blocking! max-limiter 5000)
    (log/warn "MAX rate limit timeout for POST:" endpoint))
  (let [query-string (build-query-string query-params)
        url (if query-string
              (str base-url endpoint "?" query-string)
              (str base-url endpoint))]
    (cb/execute breaker
      (fn []
        (try
          ;; MAX API requires a JSON body even if empty
          (let [req-body (errors/safe-generate-json (or body-params {}))
                {:keys [status body error]} @(http/post url
                                               {:headers (auth-headers token)
                                                :body req-body
                                                :timeout 30000})]
            (cond
              error
              (do
                (log/error "MAX API network error:" error "endpoint:" endpoint)
                (throw (ex-info "Network error"
                                {:type :network-error
                                 :endpoint endpoint
                                 :error (str error)})))

              (= 429 status)
              (do
                (log/warn "MAX API rate limit hit for:" endpoint)
                {:ok false :error "Rate limit exceeded" :retry? true :status 429})

              (>= status 400)
              (do
                (log/error "MAX API HTTP error" status "for:" endpoint "body:" body)
                {:ok false :error (str "HTTP " status) :status status :body body})

              :else
              (let [result (errors/safe-parse-json body {:ok false :error "Invalid JSON"})]
                result)))
          (catch Exception e
            (log/error e "Unexpected error in MAX API POST request:" endpoint)
            (throw e)))))))

(defn- api-request-put
  "Make a PUT request to MAX API.
   body-params are sent as JSON body."
  [{:keys [base-url token breaker]} endpoint body-params]
  (when-not (rl/acquire-blocking! max-limiter 5000)
    (log/warn "MAX rate limit timeout for PUT:" endpoint))
  (let [url (str base-url endpoint)]
    (cb/execute breaker
      (fn []
        (try
          (let [req-body (errors/safe-generate-json (or body-params {}))
                {:keys [status body error]} @(http/put url
                                               {:headers (auth-headers token)
                                                :body req-body
                                                :timeout 30000})]
            (cond
              error
              (do
                (log/error "MAX API network error:" error "endpoint:" endpoint)
                (throw (ex-info "Network error"
                                {:type :network-error
                                 :endpoint endpoint
                                 :error (str error)})))

              (= 429 status)
              (do
                (log/warn "MAX API rate limit hit for PUT:" endpoint)
                {:ok false :error "Rate limit exceeded" :retry? true :status 429})

              (>= status 400)
              (do
                (log/error "MAX API HTTP error" status "for PUT:" endpoint "body:" body)
                {:ok false :error (str "HTTP " status) :status status :body body})

              :else
              (errors/safe-parse-json body {:ok false :error "Invalid JSON"})))
          (catch Exception e
            (log/error e "Unexpected error in MAX API PUT request:" endpoint)
            (throw e)))))))

;; ============================================
;; Keyboard Builders
;; ============================================

(defn- build-inline-button
  "Build a MAX inline button
   MAX format: {type: 'callback', text: 'Button', payload: 'data'}
   or {type: 'link', text: 'Link', url: 'https://...'}"
  [{:keys [text callback url]}]
  (cond
    url {:type "link" :text text :url url}
    callback {:type "callback" :text text :payload callback}
    :else {:type "callback" :text text :payload ""}))

(defn build-keyboard
  "Build a keyboard structure for MAX API.
   MAX uses: {type: 'inline_keyboard', payload: {buttons: [[...]]}}.
   Both :inline and :reply are rendered as inline buttons (MAX has no reply keyboard)."
  [{:keys [type buttons]}]
  (case type
    (:inline :reply)
    {:type "inline_keyboard"
     :payload {:buttons (mapv #(mapv build-inline-button %) buttons)}}

    :remove
    nil

    nil))

;; ============================================
;; API Methods
;; ============================================

(defn get-me
  "Get bot information"
  [client]
  (api-request-get client "/me" {}))

(defn get-updates
  "Long polling for updates.
   marker: update marker for pagination
   timeout: seconds to wait (default 30, max 90)"
  [client {:keys [marker timeout] :or {timeout 30}}]
  (api-request-get client "/updates"
    (cond-> {:timeout timeout}  ;; MAX uses seconds, max 90
      marker (assoc :marker marker))))

(defn send-message
  "Send a text message to a chat.
   chat_id/user_id is passed as query parameter per MAX API spec.
   For direct messages, user_id can be used instead of chat_id."
  [client chat-id text {:keys [keyboard user-id]}]
  (let [query-params (cond-> {}
                       (not (clojure.string/blank? chat-id)) (assoc :chat_id chat-id)
                       (not (clojure.string/blank? user-id)) (assoc :user_id user-id))
        body (cond-> {:text text}
               keyboard (assoc :attachments [(build-keyboard keyboard)]))]
    (api-request-post client "/messages" query-params body)))

(defn edit-message
  "Edit text and keyboard of an existing bot message.
   Returns the updated message object (with :id) on success,
   or {:ok false ...} on error."
  [client message-id text {:keys [keyboard]}]
  (api-request-put client (str "/messages/" message-id)
    (cond-> {:text text}
      keyboard (assoc :attachments [(build-keyboard keyboard)]))))

(defn send-photo
  "Send a photo to a chat.
   photo-url can be a URL or MAX file token.
   chat_id/user_id is passed as query parameter per MAX API spec."
  [client chat-id photo-url {:keys [caption keyboard user-id]}]
  (let [query-params (cond-> {}
                       (not (clojure.string/blank? chat-id)) (assoc :chat_id chat-id)
                       (not (clojure.string/blank? user-id)) (assoc :user_id user-id))
        attachments (cond-> [{:type "image" :payload {:url photo-url}}]
                      keyboard (conj (build-keyboard keyboard)))
        body (cond-> {:attachments attachments}
               caption (assoc :text caption))]
    (api-request-post client "/messages" query-params body)))

(defn send-media-group
  "Send multiple photos as a media group.
   In MAX, we send multiple image attachments in one message.
   chat_id/user_id is passed as query parameter per MAX API spec."
  [client chat-id photos {:keys [user-id caption]}]
  (let [query-params (cond-> {}
                       (not (clojure.string/blank? chat-id)) (assoc :chat_id chat-id)
                       (not (clojure.string/blank? user-id)) (assoc :user_id user-id))
        attachments (mapv (fn [photo-url]
                            {:type "image" :payload {:url photo-url}})
                          photos)
        body (cond-> {:attachments attachments}
               caption (assoc :text caption))]
    (api-request-post client "/messages" query-params body)))

(defn answer-callback
  "Answer a callback query.
   callback_id is passed as query parameter per MAX API spec.
   MAX requires either 'message' or 'notification' in body."
  [client callback-id {:keys [text notification]}]
  (let [query-params {:callback_id callback-id}
        ;; MAX API requires message or notification - default to notification: false (silent ack)
        body (cond
               text {:message {:text text}}
               (some? notification) {:notification notification}
               :else {:notification false})]
    (api-request-post client "/answers" query-params body)))

(defn get-chat-member
  "Check if a specific user is a member of the chat.
   Uses user_ids parameter per MAX API spec.
   Returns {:ok true :is-member bool} or {:ok false :error msg}"
  [client chat-id user-id]
  (let [;; MAX API requires numeric user_id
        numeric-user-id (if (string? user-id)
                          (try
                            (Long/parseLong user-id)
                            (catch NumberFormatException _
                              (log/warn "Invalid user-id format:" user-id)
                              nil))
                          user-id)
        result (api-request-get client
                 (str "/chats/" chat-id "/members")
                 {:user_ids numeric-user-id})]
    ;; Circuit breaker wraps result, extract from :result if present
    (let [api-result (or (:result result) result)
          members (:members api-result)]
      (if (contains? api-result :members)
        {:ok true
         :is-member (boolean (and (seq members)
                                  (some #(= (:user_id %) numeric-user-id) members)))}
        {:ok false :error (or (:error api-result) "Could not check chat membership")}))))

;; ============================================
;; File Operations
;; ============================================

(defn upload-file
  "Upload a file to MAX servers"
  [client file-url file-type]
  (api-request-post client "/uploads" {}
    {:url file-url
     :type file-type}))

(defn get-file-url
  "Get downloadable URL for a MAX file token.
   In MAX, files are typically URLs already."
  [client file-token]
  ;; MAX files are usually direct URLs
  file-token)

;; ============================================
;; Subscription Check
;; ============================================

(defn check-subscription
  "Check if user is a member of the chat.
   Returns true if member, false otherwise.
   Note: circuit-breaker wraps response as {:ok true :result <api-response>},
   so the actual MAX response is at [:result]."
  [client chat-id user-id]
  (let [result     (get-chat-member client chat-id user-id)
        api-result (or (:result result) result)]
    (if (:ok api-result)
      (boolean (:is-member api-result))
      (do
        (log/warn "Could not check MAX chat membership:" (:error api-result))
        false))))
