(require '[cljs.build.api :as api]
         '[clojure.java.shell :as shell]
         '[clojure.string :as string])

;;; Configuration.

(def source-dir "src")

(def test-dir "test")

(def compiler-config {:main          'my-app.core
                      :output-to     "target/my-app/main.js"
                      :output-dir    "target/my-app/main"
                      :target        :nodejs
                      :optimizations :simple
                      :source-map    "target/my-app/main.js.map"})

(def test-config {:main          'my-app.test-runner
                  :output-to     "target/test.js"
                  :output-dir    "target/test"
                  :target        :nodejs
                  :optimizations :none
                  :source-map    true})

(def test-environment {:SOME_ENV_VAR "some-env-value"})

(def dev-config (merge compiler-config
                       {:optimizations :none
                        :source-map    true}))


;;; Tasks mechanism.

(defmulti task first)

(defmethod task :default
  [args]
  (let [all-tasks (-> task methods (dissoc :default) keys sort (->> (interpose ", ") (apply str)))]
    (println "unknown or missing task argument. Choose one of:" all-tasks)
    (System/exit 1)))


;;; Helper functions.

(defn run-node-tests []
  (let [{:keys [out err exit]} (shell/sh "node" "target/test.js" :env test-environment)]
    (println out err)
    (= exit 0)))

(defn try-require [ns-sym]
  (try (require ns-sym) true (catch Exception e false)))

(defmacro with-namespaces
  [namespaces & body]
  (if (every? try-require namespaces)
    `(do ~@body)
    `(do (println "task not available - required dependencies not found")
         (System/exit 1))))


;;; Compiling task.

(defn compile-once []
  (api/build source-dir compiler-config))

(defn compile-refresh []
  (api/watch source-dir compiler-config))

(defmethod task "compile" [[_ type]]
  (case type
    (nil "once") (compile-once)
    "watch"      (compile-refresh)
    (do (println "Unknown argument to compile task:" type)
        (System/exit 1))))


;;; Testing task

(defn test-once []
  (api/build (api/inputs source-dir test-dir) test-config)
  (let [success? (run-node-tests)]
    (System/exit (if success? 0 1))))

(defn test-refresh []
  (api/watch (api/inputs source-dir test-dir)
             (assoc test-config :watch-fn run-node-tests)))

(defmethod task "test" [[_ type]]
  (case type
    (nil "once") (test-once)
    "watch"      (test-refresh)
    (do (println "Unknown argument to test task:" type)
        (System/exit 1))))


;;; Figwheeling task

(defmethod task "figwheel" [[_ port]]
  (with-namespaces [figwheel-sidecar.repl-api]
    (figwheel-sidecar.repl-api/start-figwheel!
     {:figwheel-options (cond-> {}
                          port (merge {:nrepl-port       (some-> port Long/parseLong)
                                       :nrepl-middleware ["cider.nrepl/cider-middleware"
                                                          "refactor-nrepl.middleware/wrap-refactor"
                                                          "cemerick.piggieback/wrap-cljs-repl"]}))
      :all-builds       [{:id           "dev"
                          :figwheel     true
                          :source-paths [source-dir]
                          :compiler     dev-config}]})
    (when-not port
      (figwheel-sidecar.repl-api/cljs-repl))))


;;; Build script entrypoint.

(task *command-line-args*)
