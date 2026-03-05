(ns chatbot.config
  "Application configuration from environment variables and .env file.

   Environment variable naming convention:
   - TG_*   - Telegram platform settings
   - MAX_*  - MAX platform settings
   - APP_*  - Application-wide settings

   Example:
     TG_BOT_TOKEN, MAX_CHANNEL_ID, APP_DATABASE_URL"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ============================================================
;; Environment Parsing
;; ============================================================

(defn- parse-env-line
  "Parse a single line from .env file.

   Args:
     line - a string line from .env file

   Returns:
     [keyword value] tuple or nil if line is invalid

   Example:
     (parse-env-line \"TG_BOT_TOKEN=abc123\")
     => [:tg-bot-token \"abc123\"]"
  [line]
  (when-let [[_ key value] (re-matches #"^([A-Z_]+)=(.*)$" (str/trim line))]
    [(keyword (str/lower-case (str/replace key "_" "-")))
     (str/replace value #"^[\"']|[\"']$" "")]))

(defn- load-dotenv
  "Load environment variables from .env file.

   Returns:
     Map of keyword->value from .env file, or empty map if file not found"
  []
  (let [env-file (io/file ".env")]
    (if (.exists env-file)
      (do
        (log/info "Loading configuration from .env file")
        (->> (slurp env-file)
             (str/split-lines)
             (remove #(or (str/blank? %) (str/starts-with? (str/trim %) "#")))
             (map parse-env-line)
             (remove nil?)
             (into {})))
      (do
        (log/warn ".env file not found, using system environment")
        {}))))

(defn- get-env
  "Get environment variable from dotenv map or system environment.

   Args:
     dotenv - map from load-dotenv
     key    - keyword like :tg-bot-token

   Returns:
     String value or nil"
  [dotenv key]
  (or (get dotenv key)
      (System/getenv (str/upper-case (str/replace (name key) "-" "_")))))

(defn- parse-bool
  "Parse boolean string with default value.

   Args:
     value   - string \"true\" or \"false\"
     default - default boolean if value is nil"
  [value default]
  (if (nil? value)
    default
    (= "true" value)))

(defn- parse-int
  "Parse integer string with default value.

   Args:
     value   - string number
     default - default int if value is nil or invalid"
  [value default]
  (if (str/blank? value)
    default
    (try
      (Integer/parseInt value)
      (catch NumberFormatException _
        (log/warn "Invalid integer value:" value ", using default:" default)
        default))))

;; ============================================================
;; Configuration Loading
;; ============================================================

(defn load-config
  "Load configuration from .env file and environment variables.

   Returns config map with structure:
     {:platforms
       {:telegram {:token, :enabled, :admin-id, :promo, :support-link}
        :max      {:token, :enabled, :admin-id, :channel, :support-link, :webhook}}
      :database {:url}
      :server   {:port, :host}
      :features {:echo-mode}}"
  []
  (let [dotenv (load-dotenv)]
    {:platforms
     {:telegram {:token        (get-env dotenv :tg-bot-token)
                 :enabled      (parse-bool (get-env dotenv :tg-enabled) true)
                 :admin-id     (get-env dotenv :tg-admin-id)
                 :promo        {:channel-username (get-env dotenv :tg-promo-channel-username)
                                :channel-link     (get-env dotenv :tg-promo-channel-link)
                                :welcome-code     (get-env dotenv :tg-welcome-promo-code)}
                 :support-link (get-env dotenv :tg-support-link)}

      :max      {:token        (get-env dotenv :max-bot-token)
                 :enabled      (parse-bool (get-env dotenv :max-enabled) false)
                 :admin-id     (get-env dotenv :max-admin-id)
                 :channel      {:id   (get-env dotenv :max-channel-id)
                                :link (get-env dotenv :max-channel-link)}
                 :chat-id      (get-env dotenv :max-chat-id)
                 :support-link (get-env dotenv :max-support-link)
                 :webhook      {:enabled (parse-bool (get-env dotenv :max-use-webhook) false)
                                :url     (get-env dotenv :max-webhook-url)}}}

     :database {:url (get-env dotenv :app-database-url)}

     :server   {:port (parse-int (get-env dotenv :app-port) 3000)
                :host (or (get-env dotenv :app-host) "0.0.0.0")}

     :features {:echo-mode        (parse-bool (get-env dotenv :app-echo-mode) false)
                :email-promo-code (get-env dotenv :app-email-promo-code)}

     :chatwoot {:enabled        (parse-bool (get-env dotenv :chatwoot-enabled) false)
                :base-url       (get-env dotenv :chatwoot-base-url)
                :api-token      (get-env dotenv :chatwoot-api-token)
                :account-id     (get-env dotenv :chatwoot-account-id)
                :inbox-id       (get-env dotenv :chatwoot-inbox-id)
                :webhook-secret (get-env dotenv :chatwoot-webhook-secret)}}))

;; ============================================================
;; Configuration Validation
;; ============================================================

(defn validate-config
  "Validate that all required configuration is present.

   Args:
     config - config map from load-config

   Returns:
     {:valid? bool :errors [string...]}"
  [config]
  (let [telegram-enabled (get-in config [:platforms :telegram :enabled])
        max-enabled      (get-in config [:platforms :max :enabled])

        errors (cond-> []
                 ;; Telegram validation
                 (and telegram-enabled
                      (str/blank? (get-in config [:platforms :telegram :token])))
                 (conj "TG_BOT_TOKEN is required when Telegram is enabled")

                 ;; MAX validation
                 (and max-enabled
                      (str/blank? (get-in config [:platforms :max :token])))
                 (conj "MAX_BOT_TOKEN is required when MAX is enabled")

                 ;; MAX webhook validation
                 (and max-enabled
                      (get-in config [:platforms :max :webhook :enabled])
                      (str/blank? (get-in config [:platforms :max :webhook :url])))
                 (conj "MAX_WEBHOOK_URL is required when webhook mode is enabled")

                 ;; At least one platform must be enabled
                 (not (or telegram-enabled max-enabled))
                 (conj "At least one messaging platform (Telegram or MAX) must be enabled")

                 ;; Chatwoot validation
                 (and (get-in config [:chatwoot :enabled])
                      (str/blank? (get-in config [:chatwoot :base-url])))
                 (conj "CHATWOOT_BASE_URL is required when Chatwoot is enabled")

                 (and (get-in config [:chatwoot :enabled])
                      (str/blank? (get-in config [:chatwoot :api-token])))
                 (conj "CHATWOOT_API_TOKEN is required when Chatwoot is enabled")

                 ;; Database is required
                 (str/blank? (get-in config [:database :url]))
                 (conj "APP_DATABASE_URL is required"))]

    {:valid? (empty? errors)
     :errors errors}))

;; ============================================================
;; Public API
;; ============================================================

(defn load-and-validate!
  "Load config and validate. Throws if invalid.

   Returns:
     Valid config map

   Throws:
     ExceptionInfo with {:errors [...]} if validation fails"
  []
  (let [config (load-config)
        {:keys [valid? errors]} (validate-config config)]
    (if valid?
      (do
        (log/info "Configuration loaded successfully")

        ;; Log Telegram status
        (if (get-in config [:platforms :telegram :enabled])
          (log/info "Telegram platform: enabled")
          (log/info "Telegram platform: disabled"))

        ;; Log MAX status
        (if (get-in config [:platforms :max :enabled])
          (do
            (log/info "MAX platform: enabled")
            (if (get-in config [:platforms :max :webhook :enabled])
              (log/info "MAX mode: webhook")
              (log/info "MAX mode: long polling")))
          (log/info "MAX platform: disabled"))

        ;; Log Chatwoot status
        (if (get-in config [:chatwoot :enabled])
          (log/info "Chatwoot CRM: enabled, URL:" (get-in config [:chatwoot :base-url]))
          (log/info "Chatwoot CRM: disabled"))

        config)
      (do
        (log/error "Configuration errors:" errors)
        (throw (ex-info "Invalid configuration" {:errors errors}))))))

;; ============================================================
;; Helper Accessors (for cleaner code in other namespaces)
;; ============================================================

(defn telegram-config
  "Get Telegram platform config section."
  [config]
  (get-in config [:platforms :telegram]))

(defn max-config
  "Get MAX platform config section."
  [config]
  (get-in config [:platforms :max]))

(defn telegram-enabled?
  "Check if Telegram platform is enabled."
  [config]
  (get-in config [:platforms :telegram :enabled]))

(defn max-enabled?
  "Check if MAX platform is enabled."
  [config]
  (get-in config [:platforms :max :enabled]))

(defn chatwoot-enabled?
  "Check if Chatwoot CRM integration is enabled."
  [config]
  (get-in config [:chatwoot :enabled]))
