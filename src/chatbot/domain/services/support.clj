(ns chatbot.domain.services.support
  "Support service - handles Chatwoot CRM integration for support tickets.
   Uses dependency injection pattern (like promo.clj)."
  (:require [chatbot.ports.crm :as crm]
            [chatbot.ports.repository :as repo]
            [chatbot.domain.messages :as msg]
            [clojure.tools.logging :as log]))

(defn start-support-session!
  "Start a new support session: create CRM contact, ticket, and conversation.
   Called asynchronously after user enters :chat_mode.

   deps: {:crm-adapter, :user-repo, :ticket-repo}
   user: user map from DB
   message-text: initial problem description

   Returns {:ok true/false} - errors are logged but don't break the bot."
  [{:keys [crm-adapter user-repo ticket-repo]} user message-text]
  (let [user-id (:id user)]
    (try
      ;; 1. Ensure contact in CRM
      (let [contact-result (crm/ensure-contact! crm-adapter
                             {:user-id    user-id
                              :first-name (:first-name user)
                              :last-name  (:last-name user)
                              :platform   (keyword (:platform user))})]
        (when (:ok contact-result)
          (let [contact-id (:contact-id contact-result)]
            ;; 2. Save contact-id to user
            (repo/update-user! user-repo user-id {:chatwoot-contact-id contact-id})

            ;; 3. Create ticket
            (let [ticket (repo/create-ticket! ticket-repo user-id)]
              ;; 4. Create conversation in CRM
              (let [conv-result (crm/create-conversation! crm-adapter contact-id nil message-text)]
                (when (:ok conv-result)
                  ;; 5. Save conversation-id to ticket
                  (repo/update-ticket! ticket-repo (:id ticket)
                    {:chatwoot-conv-id (:conversation-id conv-result)})
                  (log/info "Support session started for" user-id
                            "ticket:" (:id ticket)
                            "conversation:" (:conversation-id conv-result))))))))
      {:ok true}
      (catch Exception e
        (log/error e "Failed to start support session for:" user-id)
        {:ok false :error (ex-message e)}))))

(defn forward-message!
  "Forward a user message to the active Chatwoot conversation.
   Called asynchronously when user sends text/photo in :chat_mode.

   deps: {:crm-adapter, :ticket-repo}
   user-id: internal user ID
   content: {:text, :photo-url} - at least one should be present"
  [{:keys [crm-adapter ticket-repo]} user-id content]
  (try
    (let [ticket (repo/find-active-ticket ticket-repo user-id)]
      (when-let [conv-id (:chatwoot-conv-id ticket)]
        (let [opts (when (:photo-url content)
                     {:attachments [(:photo-url content)]})]
          (crm/send-message! crm-adapter conv-id
            (or (:text content) "")
            (or opts {})))))
    (catch Exception e
      (log/error e "Failed to forward message for:" user-id))))

(defn handle-agent-reply!
  "Handle an agent's reply from Chatwoot webhook.
   Finds the ticket by conversation-id and returns response actions.

   deps: {:ticket-repo, :user-repo}
   conversation-id: Chatwoot conversation ID
   message-text: agent's reply text

   Returns list of response actions or nil."
  [{:keys [ticket-repo user-repo]} conversation-id message-text]
  (try
    (when-let [ticket (repo/find-ticket-by-conv-id ticket-repo conversation-id)]
      (let [user-id (:user-id ticket)]
        (log/info "Agent reply for user:" user-id "conv:" conversation-id)
        [{:action  :send-text
          :user-id user-id
          :data    {:text (msg/t :support-agent-message message-text)
                    :keyboard (msg/support-chat-keyboard)}}]))
    (catch Exception e
      (log/error e "Failed to handle agent reply for conversation:" conversation-id)
      nil)))

(defn handle-conversation-resolved!
  "Handle conversation resolved event from Chatwoot webhook.
   Closes the ticket and notifies the user.

   deps: {:ticket-repo, :user-repo}
   conversation-id: Chatwoot conversation ID

   Returns list of response actions or nil."
  [{:keys [ticket-repo user-repo]} conversation-id]
  (try
    (when-let [ticket (repo/find-ticket-by-conv-id ticket-repo conversation-id)]
      (let [user-id (:user-id ticket)]
        (log/info "Conversation resolved for user:" user-id "conv:" conversation-id)
        ;; Close ticket
        (repo/close-ticket! ticket-repo (:id ticket))
        ;; Reset user state to idle
        (repo/update-state! user-repo user-id :idle {})
        ;; Return notification for user
        [{:action  :send-text
          :user-id user-id
          :data    {:text    (msg/t :support-session-closed)
                    :keyboard (msg/main-menu-keyboard)}}]))
    (catch Exception e
      (log/error e "Failed to handle conversation resolved for:" conversation-id)
      nil)))
