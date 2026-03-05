(ns chatbot.adapters.chatwoot.webhook
  "Chatwoot webhook handler.
   Processes incoming events from Chatwoot and dispatches to support service."
  (:require [chatbot.domain.services.support :as support]
            [clojure.tools.logging :as log]
            [clojure.string :as str]))

(defn- validate-webhook-secret
  "Validate webhook secret.
   - Blank secret: allow all requests (dev/test mode, logs warning).
   - Non-blank secret: compare directly with x-chatwoot-signature header.
   NOTE: production should use HMAC-SHA256 verification instead of plain compare."
  [req secret]
  (if (str/blank? secret)
    (do
      (log/warn "CHATWOOT_WEBHOOK_SECRET is not set — accepting all webhook requests (dev mode)")
      true)
    (= secret (get-in req [:headers "x-chatwoot-signature"]))))

(defn- handle-message-created
  "Handle message_created event from Chatwoot.
   Only process outgoing messages (from agents)."
  [deps payload]
  (let [message-type (get payload :message_type)
        content      (get payload :content)
        conversation (get payload :conversation)
        conv-id      (get conversation :id)]
    (cond
      ;; Skip incoming messages (our own forwarded messages)
      (= message-type "incoming")
      (do
        (log/debug "Skipping incoming message echo for conv:" conv-id)
        nil)

      ;; Process outgoing messages (agent replies)
      (= message-type "outgoing")
      (when (and conv-id content)
        (log/info "Agent reply received for conv:" conv-id)
        (support/handle-agent-reply! deps conv-id content))

      :else
      (do
        (log/debug "Ignoring message type:" message-type "for conv:" conv-id)
        nil))))

(defn- handle-conversation-status-changed
  "Handle conversation_status_changed event from Chatwoot."
  [deps payload]
  (let [status (get payload :status)
        conv-id (get payload :id)]
    (when (and (= status "resolved") conv-id)
      (log/info "Conversation resolved via Chatwoot:" conv-id)
      (support/handle-conversation-resolved! deps conv-id))))

(defn create-webhook-handler
  "Create a Ring handler for Chatwoot webhooks.

   deps: {:ticket-repo, :user-repo, :execute-responses-fn, :webhook-secret}
   execute-responses-fn: (fn [responses]) to send responses to users"
  [{:keys [execute-responses-fn webhook-secret] :as deps}]
  (fn [req]
    ;; Validate secret
    (if-not (validate-webhook-secret req webhook-secret)
      (do
        (log/warn "Invalid webhook signature")
        {:status 401 :body {:error "Invalid signature"}})
      (try
        (let [body (:body req)
              event (get body :event)]
          (log/debug "Chatwoot webhook event:" event)
          (let [responses (case event
                            "message_created"
                            (handle-message-created deps body)

                            "conversation_status_changed"
                            (handle-conversation-status-changed deps body)

                            ;; Ignore other events
                            (do
                              (log/debug "Ignoring Chatwoot event:" event)
                              nil))]
            ;; Execute responses (send messages to users)
            (when (seq responses)
              (execute-responses-fn responses))
            {:status 200 :body {:ok true}}))
        (catch Exception e
          (log/error e "Error processing Chatwoot webhook")
          ;; Return 200 to prevent Chatwoot from retrying
          {:status 200 :body {:ok false :error "Internal error"}})))))
