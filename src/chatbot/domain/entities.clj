(ns chatbot.domain.entities
  "Domain entities and Malli schemas for the chatbot platform.
   These are platform-agnostic data structures used across the system.

   Internal ID Format:
   ==================
   Internal IDs uniquely identify users across platforms.

   Format: {platform}_{original-id}[_{chat-id}]

   Examples:
     telegram_123456789     - Telegram user with ID 123456789
     max_987654321_555      - MAX user 987654321 in chat 555
     max_channel_-12345     - MAX channel with ID -12345

   The platform prefix makes it immediately clear which platform
   the ID belongs to when reading logs or database records."
  (:require [malli.core :as m]
            [malli.transform :as mt]
            [clojure.string :as str]))

;; ============================================================
;; Platform Constants
;; ============================================================

(def platforms
  "Supported messaging platforms"
  #{:telegram :max})

(def platform-prefixes
  "Mapping of platforms to their ID prefixes.
   Used for creating and parsing internal IDs."
  {:telegram "telegram"
   :max      "max"})

;; ============================================================
;; FSM States
;; ============================================================

(def FSMState
  "Valid states for the Finite State Machine"
  [:enum :idle :waiting_support :chat_mode :waiting_review :review_confirm :review_editing
   :waiting_email
   :admin_campaign_name :admin_campaign_code :admin_campaign_expires
   :admin_campaign_edit_name :admin_campaign_edit_code :admin_campaign_edit_expires])

(def valid-states #{:idle :waiting_support :chat_mode :waiting_review :review_confirm :review_editing
                    :waiting_email
                    :admin_campaign_name :admin_campaign_code :admin_campaign_expires
                    :admin_campaign_edit_name :admin_campaign_edit_code :admin_campaign_edit_expires})

(def empty-state-data
  "Default empty state data for new users or state reset"
  {:photos []
   :text nil})

;; ============================================
;; Unified Message (Platform-agnostic)
;; ============================================

(def MessageType
  [:enum :command :text :callback :photo :document :unknown])

(def UnifiedMessage
  "A normalized message that abstracts away platform specifics.
   This is the primary input to the Router."
  [:map
   [:message-id :string]
   [:platform [:enum :telegram :max]]
   [:user-id :string]                    ; internal_id like 'tg_12345'
   [:original-user-id :string]           ; platform-specific ID
   [:type MessageType]
   [:timestamp inst?]
   [:content
    [:map
     [:text {:optional true} [:maybe :string]]
     [:command {:optional true} [:maybe :string]]
     [:callback-data {:optional true} [:maybe :string]]
     [:photo-file-id {:optional true} [:maybe :string]]
     [:document-file-id {:optional true} [:maybe :string]]]]
   [:raw-data {:optional true} :any]])   ; Original platform data

;; ============================================
;; User Entity
;; ============================================

(def User
  "A user in the system, unified across platforms"
  [:map
   [:id :string]                         ; internal_id
   [:platform [:enum :telegram :max]]
   [:original-id :string]
   [:username {:optional true} [:maybe :string]]
   [:first-name {:optional true} [:maybe :string]]
   [:last-name {:optional true} [:maybe :string]]
   [:state FSMState]
   [:state-data {:optional true} :map]
   [:chatwoot-contact-id {:optional true} [:maybe :int]]
   [:created-at {:optional true} inst?]
   [:updated-at {:optional true} inst?]])

;; ============================================
;; Ticket Entity
;; ============================================

(def TicketStatus
  [:enum :active :resolved :closed])

(def Ticket
  [:map
   [:id uuid?]
   [:user-id :string]
   [:chatwoot-conv-id {:optional true} [:maybe :int]]
   [:status TicketStatus]
   [:created-at {:optional true} inst?]
   [:resolved-at {:optional true} [:maybe inst?]]])

;; ============================================
;; Review Entity
;; ============================================

(def ReviewStatus
  [:enum :moderation :published :rejected])

(def Review
  [:map
   [:id uuid?]
   [:user-id :string]
   [:content-text {:optional true} [:maybe :string]]
   [:photo-urls {:optional true} [:vector :string]]
   [:status ReviewStatus]
   [:moderation-message-id {:optional true} [:maybe :int]]
   [:published-message-id {:optional true} [:maybe :int]]
   [:created-at {:optional true} inst?]
   [:moderated-at {:optional true} [:maybe inst?]]])

;; ============================================
;; Response Actions (What the bot should do)
;; ============================================

(def ResponseAction
  "Actions that the domain core can request from adapters"
  [:map
   [:action [:enum :send-text :send-photo :send-keyboard :update-state :no-op]]
   [:user-id :string]
   [:data {:optional true} :map]])

;; ============================================================
;; Internal ID Functions
;; ============================================================

(defn make-internal-id
  "Create an internal user ID from platform and original ID.

   For MAX, an optional opts map with :chat-id can be provided
   since MAX uses separate user_id and chat_id.

   Args:
     platform    - keyword :telegram or :max
     original-id - platform's native user ID (string or number)
     opts        - optional map with :chat-id for MAX

   Returns:
     String internal ID

   Examples:
     (make-internal-id :telegram 123456789)
     => \"telegram_123456789\"

     (make-internal-id :max 987654321 {:chat-id 555})
     => \"max_987654321_555\"

     (make-internal-id :max \"channel\" {:chat-id -12345})
     => \"max_channel_-12345\""
  ([platform original-id]
   (str (get platform-prefixes platform (name platform)) "_" original-id))
  ([platform original-id {:keys [chat-id]}]
   (if (and (= platform :max) chat-id)
     (str (get platform-prefixes platform) "_" original-id "_" chat-id)
     (str (get platform-prefixes platform (name platform)) "_" original-id))))

(defn parse-internal-id
  "Parse internal ID back to platform, original ID, and chat-id (for MAX).

   Args:
     internal-id - string like \"telegram_123\" or \"max_456_789\"

   Returns:
     Map with :platform, :original-id, and optionally :chat-id

   Examples:
     (parse-internal-id \"telegram_123456789\")
     => {:platform :telegram :original-id \"123456789\"}

     (parse-internal-id \"max_987654321_555\")
     => {:platform :max :original-id \"987654321\" :chat-id \"555\"}

     (parse-internal-id \"max_channel_-12345\")
     => {:platform :max :original-id \"channel\" :chat-id \"-12345\"}"
  [internal-id]
  (when internal-id
    (let [parts (str/split internal-id #"_")
          platform (keyword (first parts))]
      (if (and (= platform :max) (>= (count parts) 3))
        {:platform platform
         :original-id (second parts)
         :chat-id (nth parts 2)}
        {:platform platform
         :original-id (str/join "_" (rest parts))}))))

(defn platform-from-id
  "Extract platform keyword from internal ID.

   Args:
     internal-id - string like \"telegram_123\" or \"max_456_789\"

   Returns:
     Keyword :telegram, :max, or nil if invalid

   Example:
     (platform-from-id \"telegram_123\") => :telegram
     (platform-from-id \"max_456_789\") => :max"
  [internal-id]
  (when internal-id
    (let [prefix (first (str/split internal-id #"_"))]
      (when (contains? #{"telegram" "max"} prefix)
        (keyword prefix)))))

(defn telegram-id?
  "Check if internal ID belongs to Telegram platform.

   Example:
     (telegram-id? \"telegram_123\") => true
     (telegram-id? \"max_456\") => false"
  [internal-id]
  (and (string? internal-id)
       (str/starts-with? internal-id "telegram_")))

(defn max-id?
  "Check if internal ID belongs to MAX platform.

   Example:
     (max-id? \"max_456_789\") => true
     (max-id? \"telegram_123\") => false"
  [internal-id]
  (and (string? internal-id)
       (str/starts-with? internal-id "max_")))

;; ============================================================
;; State Validation
;; ============================================================

(defn valid-state?
  "Check if a state is valid FSM state.

   Args:
     state - keyword or string state name

   Returns:
     true if valid, false otherwise"
  [state]
  (contains? valid-states (keyword state)))

;; ============================================================
;; Message Coercion
;; ============================================================

(defn coerce-message
  "Coerce and validate a map to UnifiedMessage schema.

   Args:
     data - map with message data

   Returns:
     Coerced and validated UnifiedMessage"
  [data]
  (m/coerce UnifiedMessage data mt/string-transformer))
