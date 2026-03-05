(ns chatbot.adapters.chatwoot.api
  "Chatwoot REST API HTTP client.
   Handles all communication with Chatwoot server.

   API docs: https://www.chatwoot.com/developers/api/"
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [chatbot.utils.errors :as errors]
            [chatbot.utils.circuit-breaker :as cb]
            [chatbot.utils.rate-limiter :as rl]))

;; Circuit breaker for Chatwoot API (5 failures, 30s timeout)
(defonce chatwoot-breaker
  (cb/register-breaker! :chatwoot
    (cb/create-breaker "chatwoot-api" 5 30000 2)))

;; Rate limiter for Chatwoot API (20 requests/second)
(defonce chatwoot-limiter
  (rl/register-limiter! :chatwoot
    (rl/create-named-limiter "chatwoot-api" 20 1000)))

(defn make-client
  "Create a Chatwoot API client.
   Returns a map with connection parameters."
  [base-url api-token account-id inbox-id]
  {:base-url   (str base-url "/api/v1/accounts/" account-id)
   :api-token  api-token
   :account-id account-id
   :inbox-id   inbox-id
   :breaker    chatwoot-breaker})

(defn- api-request
  "Make a request to Chatwoot API with circuit breaker and rate limiting.
   Unwraps the circuit breaker envelope so callers see {:ok true/false :result ...} directly."
  [{:keys [base-url api-token breaker]} method url params]
  (when-not (rl/acquire-blocking! chatwoot-limiter 5000)
    (log/warn "Chatwoot rate limit timeout for:" method url))
  (let [cb-result (cb/execute breaker
                    (fn []
                      (try
                        (let [req-opts (cond-> {:headers {"api_access_token" api-token
                                                          "Content-Type"     "application/json"}
                                                :timeout 30000}
                                         (= method :post) (assoc :body (errors/safe-generate-json params))
                                         (= method :get)  (assoc :query-params params))
                              full-url (str base-url url)
                              response (case method
                                         :get  @(http/get full-url req-opts)
                                         :post @(http/post full-url req-opts))]
                          (let [{:keys [status body error]} response]
                            (cond
                              error
                              (do
                                (log/error "Chatwoot API network error:" error "url:" url)
                                (throw (ex-info "Network error"
                                                {:type :network-error :url url :error (str error)})))

                              (= 429 status)
                              (do
                                (log/warn "Chatwoot API rate limit hit for:" url)
                                {:ok false :error "Rate limit exceeded" :status 429})

                              (>= status 400)
                              (do
                                (log/error "Chatwoot API HTTP error" status "for:" url "body:" body)
                                {:ok false :error (str "HTTP " status) :status status
                                 :body (errors/safe-parse-json body nil)})

                              :else
                              (let [result (errors/safe-parse-json body nil)]
                                {:ok true :result result}))))
                        (catch Exception e
                          (log/error e "Unexpected error in Chatwoot API request:" url)
                          (throw e)))))]
    ;; cb/execute wraps fn result as {:ok true :result <fn-result>} for non-exceptions.
    ;; Unwrap here so callers always get the inner {:ok true/false :result ...} directly.
    (if (:ok cb-result)
      (:result cb-result)
      cb-result)))

;; ============================================
;; API Methods
;; ============================================

(defn search-contacts
  "Search contacts by query string.
   GET /contacts/search?q=query"
  [client query]
  (api-request client :get "/contacts/search" {:q query}))

(defn create-contact
  "Create a new contact.
   POST /contacts
   params: {:name, :identifier, :email, :phone_number, :custom_attributes}"
  [client params]
  (api-request client :post "/contacts" params))

(defn create-conversation
  "Create a new conversation.
   POST /conversations
   params: {:source_id, :inbox_id, :contact_id, :message, :status}"
  [client params]
  (api-request client :post "/conversations" params))

(defn send-message
  "Send a message to a conversation.
   POST /conversations/{conv-id}/messages
   params: {:content, :message_type, :content_type, :attachments}"
  [client conv-id params]
  (api-request client :post (str "/conversations/" conv-id "/messages") params))

(defn toggle-status
  "Toggle conversation status (open/resolved/pending).
   POST /conversations/{conv-id}/toggle_status
   params: {:status}"
  [client conv-id status]
  (api-request client :post (str "/conversations/" conv-id "/toggle_status")
    {:status status}))

(defn get-conversation
  "Get conversation details.
   GET /conversations/{conv-id}"
  [client conv-id]
  (api-request client :get (str "/conversations/" conv-id) {}))
