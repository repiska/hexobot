(ns chatbot.adapters.chatwoot.adapter
  "Chatwoot CRM adapter - implements CRMService protocol via Chatwoot API."
  (:require [chatbot.ports.crm :as crm]
            [chatbot.adapters.chatwoot.api :as api]
            [clojure.tools.logging :as log]))

(defn- find-contact-by-identifier
  "Search for a contact by identifier (internal user-id)."
  [client identifier]
  (let [result (api/search-contacts client identifier)]
    (when (and (:ok result) (get-in result [:result :payload]))
      (let [contacts (get-in result [:result :payload])]
        (->> contacts
             (filter #(= (:identifier %) identifier))
             first)))))

(defn- build-contact-name
  "Build display name for CRM contact."
  [{:keys [first-name last-name platform]}]
  (let [name-parts (remove nil? [first-name last-name])
        name-str (if (seq name-parts)
                   (clojure.string/join " " name-parts)
                   "Unknown")]
    (str name-str " (" (name platform) ")")))

(defrecord ChatwootAdapter [client]
  crm/CRMService

  (ensure-contact! [this user-data]
    (let [{:keys [user-id]} user-data
          identifier user-id]
      (try
        ;; Try to find existing contact
        (if-let [contact (find-contact-by-identifier client identifier)]
          (do
            (log/debug "Found existing Chatwoot contact:" (:id contact) "for" identifier)
            {:ok true :contact-id (:id contact)})
          ;; Create new contact
          (let [result (api/create-contact client
                         {:name       (build-contact-name user-data)
                          :identifier identifier})]
            (if (:ok result)
              ;; Chatwoot wraps create-contact response: {payload: {contact: {id: ...}}}
              (let [contact-id (get-in result [:result :payload :contact :id])]
                (log/info "Created Chatwoot contact:" contact-id "for" identifier)
                {:ok true :contact-id contact-id})
              (do
                (log/error "Failed to create Chatwoot contact:" (:error result))
                {:ok false :error (:error result)}))))
        (catch Exception e
          (log/error e "Error ensuring Chatwoot contact for:" identifier)
          {:ok false :error (ex-message e)}))))

  (create-conversation! [this contact-id inbox-id initial-message]
    (try
      ;; Chatwoot API rejects conversation creation when :message field is present.
      ;; Create the conversation first, then send the initial message separately.
      (let [result (api/create-conversation client
                     {:inbox_id   (or inbox-id (:inbox-id client))
                      :contact_id contact-id
                      :status     "open"})]
        (if (:ok result)
          (let [conv-id (get-in result [:result :id])]
            (log/info "Created Chatwoot conversation:" conv-id "for contact:" contact-id)
            ;; Send initial message as a separate call
            (when (and conv-id (seq initial-message))
              (api/send-message client conv-id
                {:content      initial-message
                 :message_type "incoming"
                 :content_type "text"}))
            {:ok true :conversation-id conv-id})
          (do
            (log/error "Failed to create Chatwoot conversation:" (:error result))
            {:ok false :error (:error result)})))
      (catch Exception e
        (log/error e "Error creating Chatwoot conversation for contact:" contact-id)
        {:ok false :error (ex-message e)})))

  (send-message! [this conversation-id message-text opts]
    (try
      (let [params (cond-> {:content      message-text
                            :message_type "incoming"
                            :content_type "text"}
                     (:attachments opts)
                     (assoc :attachments (mapv (fn [url]
                                                 {:external_url url
                                                  :type "image"})
                                               (:attachments opts))))
            result (api/send-message client conversation-id params)]
        (if (:ok result)
          (do
            (log/debug "Sent message to Chatwoot conversation:" conversation-id)
            {:ok true})
          (do
            (log/error "Failed to send message to Chatwoot:" (:error result))
            {:ok false :error (:error result)})))
      (catch Exception e
        (log/error e "Error sending message to Chatwoot conversation:" conversation-id)
        {:ok false :error (ex-message e)})))

  (resolve-conversation! [this conversation-id]
    (try
      (let [result (api/toggle-status client conversation-id "resolved")]
        (if (:ok result)
          (do
            (log/info "Resolved Chatwoot conversation:" conversation-id)
            {:ok true})
          (do
            (log/error "Failed to resolve Chatwoot conversation:" (:error result))
            {:ok false :error (:error result)})))
      (catch Exception e
        (log/error e "Error resolving Chatwoot conversation:" conversation-id)
        {:ok false :error (ex-message e)}))))

(defn create-adapter
  "Create a ChatwootAdapter instance."
  [client]
  (->ChatwootAdapter client))
