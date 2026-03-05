(ns chatbot.web.server
  "HTTP server for webhooks and health checks.
   Uses http-kit (already a project dependency)."
  (:require [org.httpkit.server :as http-server]
            [compojure.core :refer [defroutes GET POST]]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [clojure.tools.logging :as log]))

(defn- health-handler
  "Health check endpoint."
  [_req]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body {:status "ok"}})

(defn create-app
  "Create Ring application with routes.
   webhook-handler: Ring handler for POST /webhooks/chatwoot (or nil)"
  [webhook-handler]
  (let [routes (compojure.core/routes
                 (GET "/health" [] health-handler)
                 (when webhook-handler
                   (POST "/webhooks/chatwoot" req (webhook-handler req))))]
    (-> routes
        (wrap-json-body {:keywords? true})
        wrap-json-response)))

(defn start-server!
  "Start HTTP server.
   Returns stop function."
  [app host port]
  (log/info "Starting HTTP server on" (str host ":" port))
  (let [stop-fn (http-server/run-server app {:ip host :port port})]
    (log/info "HTTP server started on" (str host ":" port))
    stop-fn))

(defn stop-server!
  "Stop HTTP server."
  [stop-fn]
  (when stop-fn
    (log/info "Stopping HTTP server...")
    (stop-fn :timeout 5000)
    (log/info "HTTP server stopped")))
