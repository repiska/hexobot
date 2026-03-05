(ns chatbot.utils.errors
  "Utilities for comprehensive error handling and resilience"
  (:require [clojure.tools.logging :as log]
            [cheshire.core :as json]))

;; ============================================================
;; Core Error Handling Functions
;; ============================================================

(defn safe-execute
  "Execute function with error boundary. Returns [result error].

  Usage:
    (let [[result error] (safe-execute \"DB query\" fetch-user 123)]
      (if error
        (handle-error error)
        (process result)))"
  [context-msg f & args]
  (try
    [(apply f args) nil]
    (catch Exception e
      (log/error e context-msg)
      [nil {:error (ex-message e)
            :type (type e)
            :context context-msg}])))

(defn safe-execute-with-default
  "Execute function with error boundary, returning default value on error.

  Usage:
    (safe-execute-with-default [] \"Get users\" fetch-users)"
  [default-value context-msg f & args]
  (try
    (apply f args)
    (catch Exception e
      (log/error e context-msg)
      default-value)))

(defmacro with-error-boundary
  "Execute body with automatic error logging and default value on error.

  Usage:
    (with-error-boundary \"Fetching user\" nil
      (db/query-one ds query))"
  [context default-value & body]
  `(try
     ~@body
     (catch Exception e#
       (log/error e# ~context)
       ~default-value)))

;; ============================================================
;; JSON Parsing
;; ============================================================

(defn safe-parse-json
  "Parse JSON with error handling, returning default value on failure.

  Usage:
    (safe-parse-json response-body {})"
  [json-str default-value]
  (try
    (json/parse-string json-str true)
    (catch Exception e
      (log/warn e "Failed to parse JSON, using default")
      default-value)))

(defn safe-generate-json
  "Generate JSON with error handling, returning empty string on failure.

  Usage:
    (safe-generate-json {:key \"value\"})"
  [data]
  (try
    (json/generate-string data)
    (catch Exception e
      (log/error e "Failed to generate JSON from data:" data)
      "{}")))

;; ============================================================
;; Repository Operations
;; ============================================================

(defn wrap-repository-op
  "Wrap a repository operation with error handling.
  Returns nil for queries, false for commands on error.

  Usage:
    (wrap-repository-op :query \"find-user\" #(db/query-one ds query))"
  [op-type context-msg op-fn]
  (try
    (op-fn)
    (catch Exception e
      (log/error e "Repository error:" context-msg)
      (case op-type
        :query nil      ; Return nil for failed queries
        :command false  ; Return false for failed commands
        nil))))

(defmacro def-safe-query
  "Define a safe repository query function that returns nil on error.

  Usage:
    (def-safe-query find-user [ds id]
      (db/query-one ds (build-query id)))"
  [fn-name args & body]
  `(defn ~fn-name ~args
     (wrap-repository-op :query
                         ~(str "Query: " fn-name)
                         (fn [] ~@body))))

(defmacro def-safe-command
  "Define a safe repository command function that returns false on error.

  Usage:
    (def-safe-command create-user! [ds user-data]
      (db/execute-one! ds (build-insert user-data)))"
  [fn-name args & body]
  `(defn ~fn-name ~args
     (wrap-repository-op :command
                         ~(str "Command: " fn-name)
                         (fn [] ~@body))))

;; ============================================================
;; API Call Handling
;; ============================================================

(defn wrap-api-call
  "Wrap an API call with comprehensive error handling.
  Returns map with :ok, :result/:error, and :retry? keys.

  Usage:
    (wrap-api-call \"Telegram sendMessage\"
      (fn [] (http/post url options)))"
  [context-msg api-fn]
  (try
    (let [result (api-fn)]
      (assoc result :ok true))
    (catch java.net.SocketTimeoutException e
      (log/warn e "API timeout:" context-msg)
      {:ok false
       :error "Connection timeout"
       :retry? true
       :type :timeout})
    (catch java.net.ConnectException e
      (log/error e "API connection failed:" context-msg)
      {:ok false
       :error "Connection failed"
       :retry? true
       :type :connection-error})
    (catch Exception e
      (log/error e "API error:" context-msg)
      {:ok false
       :error (ex-message e)
       :retry? false
       :type :unknown})))

;; ============================================================
;; Error Response Helpers
;; ============================================================

(defn error-response
  "Create a standardized error response map.

  Usage:
    (error-response :validation \"Invalid input\" {:field \"username\"})"
  ([error-type message]
   (error-response error-type message {}))
  ([error-type message details]
   {:error true
    :error-type error-type
    :message message
    :details details
    :timestamp (System/currentTimeMillis)}))

(defn success-response
  "Create a standardized success response map.

  Usage:
    (success-response {:user-id 123})"
  ([data]
   {:error false
    :data data
    :timestamp (System/currentTimeMillis)}))

;; ============================================================
;; Retry Logic
;; ============================================================

(defn retry-with-backoff
  "Retry operation with exponential backoff.

  Options:
    :max-attempts - Maximum retry attempts (default 3)
    :initial-delay-ms - Initial delay in milliseconds (default 100)
    :max-delay-ms - Maximum delay in milliseconds (default 5000)
    :backoff-multiplier - Backoff multiplier (default 2)

  Usage:
    (retry-with-backoff
      (fn [] (api-call))
      {:max-attempts 3 :initial-delay-ms 100})"
  [operation & [{:keys [max-attempts initial-delay-ms max-delay-ms backoff-multiplier]
                 :or {max-attempts 3
                      initial-delay-ms 100
                      max-delay-ms 5000
                      backoff-multiplier 2}}]]
  (loop [attempt 1
         delay initial-delay-ms]
    (let [[result error] (safe-execute "Retry operation" operation)]
      (cond
        ;; Success
        (nil? error)
        result

        ;; Max attempts reached
        (>= attempt max-attempts)
        (do
          (log/error "Max retry attempts reached:" max-attempts)
          (throw (ex-info "Max retries exceeded"
                          {:attempts attempt
                           :last-error error})))

        ;; Retry with backoff
        :else
        (do
          (log/warn "Attempt" attempt "failed, retrying in" delay "ms")
          (Thread/sleep delay)
          (recur (inc attempt)
                 (min max-delay-ms (* delay backoff-multiplier))))))))

;; ============================================================
;; Logging Helpers
;; ============================================================

(defn log-error-with-context
  "Log error with full context information.

  Usage:
    (log-error-with-context e \"Processing message\"
      {:user-id \"123\" :message-id \"456\"})"
  [exception context-msg context-data]
  (log/error exception
             (str context-msg " - Context: " (pr-str context-data))))

(defn log-and-throw
  "Log error and re-throw with additional context.

  Usage:
    (log-and-throw e \"Failed to create user\" {:username \"test\"})"
  [exception context-msg context-data]
  (log-error-with-context exception context-msg context-data)
  (throw (ex-info context-msg
                  (merge context-data {:cause exception}))))
