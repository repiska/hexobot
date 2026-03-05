(ns chatbot.utils.circuit-breaker
  "Circuit breaker pattern implementation for preventing cascading failures"
  (:require [clojure.tools.logging :as log]))

;; ============================================================
;; Circuit Breaker States
;; ============================================================

;; States:
;; :closed - Normal operation, requests pass through
;; :open - Failure threshold exceeded, requests are rejected
;; :half-open - Testing if service recovered

;; ============================================================
;; Core Functions
;; ============================================================

(defn create-breaker
  "Create a circuit breaker with specified parameters.

  Parameters:
    name - Identifier for logging
    failure-threshold - Number of failures before opening circuit
    timeout-ms - Time to wait before attempting recovery (half-open)
    success-threshold - Number of successes needed in half-open to close

  Usage:
    (def telegram-breaker
      (create-breaker \"telegram-api\" 5 30000 2))"
  [name failure-threshold timeout-ms & [success-threshold]]
  (atom {:state :closed
         :failures 0
         :successes 0
         :last-failure-time nil
         :last-success-time nil
         :name name
         :failure-threshold failure-threshold
         :success-threshold (or success-threshold 2)
         :timeout-ms timeout-ms
         :total-calls 0
         :total-failures 0
         :total-successes 0}))

(defn- should-attempt-reset?
  "Check if enough time has passed to attempt recovery"
  [breaker-state]
  (let [{:keys [state last-failure-time timeout-ms]} breaker-state
        now (System/currentTimeMillis)]
    (and (= state :open)
         last-failure-time
         (> (- now last-failure-time) timeout-ms))))

(defn- record-success!
  "Record a successful operation"
  [breaker]
  (swap! breaker
         (fn [state]
           (let [new-state (-> state
                               (update :total-calls inc)
                               (update :total-successes inc)
                               (assoc :last-success-time (System/currentTimeMillis)))]
             (case (:state state)
               :half-open
               (let [successes (inc (:successes state))
                     success-threshold (:success-threshold state)]
                 (if (>= successes success-threshold)
                   (do
                     (log/info "Circuit breaker" (:name state) "CLOSING after" successes "successes")
                     (assoc new-state
                            :state :closed
                            :failures 0
                            :successes 0))
                   (assoc new-state :successes successes)))

               :closed
               (assoc new-state :failures 0)

               new-state)))))

(defn- record-failure!
  "Record a failed operation"
  [breaker]
  (swap! breaker
         (fn [state]
           (let [now (System/currentTimeMillis)
                 failures (inc (:failures state))
                 new-state (-> state
                               (update :total-calls inc)
                               (update :total-failures inc)
                               (assoc :failures failures)
                               (assoc :last-failure-time now))]
             (if (>= failures (:failure-threshold state))
               (do
                 (log/error "Circuit breaker" (:name state)
                            "OPENING after" failures "consecutive failures")
                 (assoc new-state
                        :state :open
                        :successes 0))
               new-state)))))

(defn get-state
  "Get current circuit breaker state"
  [breaker]
  (:state @breaker))

(defn get-stats
  "Get circuit breaker statistics"
  [breaker]
  (let [state @breaker]
    {:name (:name state)
     :state (:state state)
     :total-calls (:total-calls state)
     :total-successes (:total-successes state)
     :total-failures (:total-failures state)
     :current-failures (:failures state)
     :failure-threshold (:failure-threshold state)
     :last-failure-time (:last-failure-time state)
     :last-success-time (:last-success-time state)}))

(defn reset!
  "Manually reset circuit breaker to closed state"
  [breaker]
  (log/info "Manually resetting circuit breaker" (:name @breaker))
  (swap! breaker
         (fn [state]
           (assoc state
                  :state :closed
                  :failures 0
                  :successes 0))))

;; ============================================================
;; Execution Functions
;; ============================================================

(defn execute
  "Execute function with circuit breaker protection.

  Returns:
    {:ok true/false :result value :circuit-open? true/false}

  Usage:
    (execute telegram-breaker
      (fn [] (http/post url options)))"
  [breaker f]
  (let [breaker-state @breaker
        {:keys [state name timeout-ms]} breaker-state]

    (cond
      ;; Circuit is open - check if we should attempt recovery
      (= state :open)
      (if (should-attempt-reset? breaker-state)
        (do
          (log/info "Circuit breaker" name "transitioning to HALF-OPEN")
          (swap! breaker assoc :state :half-open :successes 0)
          (execute breaker f))
        (do
          (log/warn "Circuit breaker" name "is OPEN, rejecting request")
          {:ok false
           :error "Service temporarily unavailable"
           :circuit-open? true
           :retry-after-ms timeout-ms}))

      ;; Circuit is closed or half-open - attempt operation
      :else
      (try
        (let [result (f)]
          (record-success! breaker)
          {:ok true
           :result result
           :circuit-open? false})
        (catch Exception e
          (record-failure! breaker)
          (log/error e "Circuit breaker" name "recorded failure")
          {:ok false
           :error (ex-message e)
           :exception e
           :circuit-open? false})))))

(defn execute-with-fallback
  "Execute function with circuit breaker protection and fallback.

  Usage:
    (execute-with-fallback telegram-breaker
      (fn [] (send-to-telegram msg))
      (fn [] (queue-for-later msg)))"
  [breaker primary-fn fallback-fn]
  (let [result (execute breaker primary-fn)]
    (if (:ok result)
      result
      (do
        (log/info "Circuit breaker" (:name @breaker) "using fallback")
        (try
          {:ok true
           :result (fallback-fn)
           :used-fallback? true}
          (catch Exception e
            (log/error e "Fallback also failed for" (:name @breaker))
            {:ok false
             :error "Both primary and fallback failed"
             :fallback-error (ex-message e)}))))))

;; ============================================================
;; Macros for Convenience
;; ============================================================

(defmacro with-breaker
  "Execute body with circuit breaker protection.

  Usage:
    (with-breaker telegram-breaker
      (http/post url options))"
  [breaker & body]
  `(execute ~breaker (fn [] ~@body)))

;; ============================================================
;; Monitoring and Management
;; ============================================================

(defonce breakers (atom {}))

(defn register-breaker!
  "Register a circuit breaker for monitoring"
  [key breaker]
  (swap! breakers assoc key breaker)
  breaker)

(defn get-breaker
  "Get a registered circuit breaker"
  [key]
  (get @breakers key))

(defn get-all-stats
  "Get statistics for all registered circuit breakers"
  []
  (into {}
        (map (fn [[k breaker]]
               [k (get-stats breaker)])
             @breakers)))

(defn reset-all!
  "Reset all registered circuit breakers"
  []
  (log/warn "Resetting ALL circuit breakers")
  (doseq [[_ breaker] @breakers]
    (reset! breaker)))

;; ============================================================
;; Health Check
;; ============================================================

(defn health-check
  "Check health of a circuit breaker"
  [breaker]
  (let [stats (get-stats breaker)
        state (:state stats)]
    {:healthy? (not= state :open)
     :state state
     :name (:name stats)
     :stats stats}))

(defn health-check-all
  "Check health of all registered circuit breakers"
  []
  (let [all-stats (get-all-stats)]
    {:healthy? (every? #(not= :open (:state %)) (vals all-stats))
     :breakers all-stats
     :open-count (count (filter #(= :open (:state %)) (vals all-stats)))
     :total-count (count all-stats)}))
