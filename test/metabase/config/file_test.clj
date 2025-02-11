(ns metabase.config.file-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [clojure.walk :as walk]
   [metabase.config.file :as config.file]
   [metabase.test :as mt]
   [metabase.util :as u]
   [yaml.core :as yaml]))

(use-fixtures :each (fn [thunk]
                      (binding [config.file/*supported-versions* {:min 1.0, :max 1.999}]
                        (thunk))))

(defn- re-quote [^String s]
  (re-pattern (java.util.regex.Pattern/quote s)))

(def ^:private mock-yaml
  {:version 1
   :config  {:settings {:my-setting "abc123"}}})

(deftest ^:parallel config-test
  (testing "Specify a custom path and read from YAML"
    (mt/with-temp-file [filename "temp-config-file.yml"]
      (spit filename (yaml/generate-string mock-yaml))
      (binding [config.file/*env* (assoc @#'config.file/*env* :mb-config-file-path filename)]
        (is (= {:version 1
                :config  {:settings {:my-setting "abc123"}}}
               (#'config.file/config))))))
  (testing "Support overriding config with dynamic var for mocking purposes"
    (binding [config.file/*config* mock-yaml]
      (is (= {:version 1
              :config  {:settings {:my-setting "abc123"}}}
             (#'config.file/config))))))

(deftest ^:parallel validate-config-test
  (testing "Config should throw an error"
    (testing "if it is not a map"
      (binding [config.file/*config* [1 2 3 4]]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             (re-quote "failed: map?")
             (#'config.file/config)))))
    (testing "if version"
      (testing "is not included"
        (binding [config.file/*config* {:config {:settings {}}}]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               (re-quote "failed: (contains? % :version)")
               (#'config.file/config)))))
      (testing "is unsupported"
        (testing "because it is too old"
          (binding [config.file/*supported-versions* {:min 2.0, :max 3.0}
                    config.file/*config*             {:version 1.0, :config {}}]
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 (re-quote "failed: supported-version?")
                 (#'config.file/config)))))
        (testing "because it is too new"
          (binding [config.file/*supported-versions* {:min 2.0, :max 3.0}
                    config.file/*config*             {:version 4.0, :config {}}]
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 (re-quote "failed: supported-version?")
                 (#'config.file/config)))))))))

(defn- mock-config-with-setting [s]
  {:version 1.0, :config {:settings {:my-setting s}}})

(deftest ^:parallel expand-template-forms-test
  (testing "Ignore single curly brackets, or brackets with spaces between them"
    (are [s] (= (mock-config-with-setting s)
                (binding [config.file/*config* (mock-config-with-setting s)]
                  (#'config.file/config)))
      "{}}"
      "{}}"
      "{ {}}"))
  (testing "Invalid template forms"
    (are [template error-pattern] (thrown-with-msg?
                                   clojure.lang.ExceptionInfo
                                   error-pattern
                                   (binding [config.file/*config* (mock-config-with-setting template)]
                                     (#'config.file/config)))
      ;; {{ without a corresponding }}
      "{{}"                        (re-quote "Invalid query: found [[ or {{ with no matching ]] or }}")
      "{{} }"                      (re-quote "Invalid query: found [[ or {{ with no matching ]] or }}")
      ;; raw token, not a list
      "{{CONFIG_FILE_BIRD_NAME}}"  (re-quote "CONFIG_FILE_BIRD_NAME - failed: valid-template-type?")
      ;; unbalanced parens
      "{{(env MY_ENV_VAR}}"        (re-quote "Error parsing template string \"(env MY_ENV_VAR\": EOF while reading")
      ;; unknown template type
      "{{bird \"Parrot Hilton\"}}" (re-quote "bird - failed: valid-template-type?"))))

(deftest ^:parallel recursive-template-form-expansion-test
  (testing "Recursive expansion is unsupported, for now."
    (binding [config.file/*env*    (assoc @#'config.file/*env* :x "{{env Y}}", :y "Y")
              config.file/*config* (mock-config-with-setting "{{env X}}")]
      (is (= (mock-config-with-setting "{{env Y}}")
                 (#'config.file/config))))))

(deftest ^:parallel expand-template-env-var-values-test
  (testing "env var values"
    (binding [config.file/*env* (assoc @#'config.file/*env* :config-file-bird-name "Parrot Hilton")]
      (testing "Nothing weird"
        (binding [config.file/*config* (mock-config-with-setting "{{env CONFIG_FILE_BIRD_NAME}}")]
          (is (= (mock-config-with-setting "Parrot Hilton")
                 (#'config.file/config)))))
      (testing "Should handle multiple templates in one string"
        (binding [config.file/*config* (mock-config-with-setting "{{env CONFIG_FILE_BIRD_NAME}}-{{env CONFIG_FILE_BIRD_NAME}}")]
          (is (= (mock-config-with-setting "Parrot Hilton-Parrot Hilton")
                 (#'config.file/config)))))
      (testing "Ignore whitespace inside the template brackets"
        (binding [config.file/*config* (mock-config-with-setting "{{  env CONFIG_FILE_BIRD_NAME  }}")]
          (is (= (mock-config-with-setting "Parrot Hilton")
                 (#'config.file/config)))))
      (testing "Ignore excess brackets"
        (are [template expected] (= (mock-config-with-setting expected)
                                    (binding [config.file/*config* (mock-config-with-setting template)]
                                      (#'config.file/config)))
          "{{{env CONFIG_FILE_BIRD_NAME}}" "{Parrot Hilton"
          "{{env CONFIG_FILE_BIRD_NAME}}}" "Parrot Hilton}"))
      (testing "handle lisp-case/snake-case and case variations"
        (binding [config.file/*config* (mock-config-with-setting "{{env config-file-bird-name}}")]
          (is (= (mock-config-with-setting "Parrot Hilton")
                 (#'config.file/config))))))))

(deftest ^:parallel expand-template-env-var-values-validation-test
  (testing "(config) should walk the config map and expand {{template}} forms"
    (testing "env var values"
      (testing "validation"
        (are [template error-pattern] (thrown-with-msg?
                                       clojure.lang.ExceptionInfo
                                       error-pattern
                                       (binding [config.file/*config* (mock-config-with-setting template)]
                                         (#'config.file/config)))
          ;; missing env var name
          "{{env}}"                           #"Insufficient input"
          ;; too many args
          "{{env SOME_ENV_VAR SOME_ENV_VAR}}" #"failed: Extra input"
          ;; wrong env var type
          "{{env :SOME_ENV_VAR}}"             (re-quote "SOME_ENV_VAR - failed: symbol?"))))))

(deftest ^:parallel optional-template-test
  (testing "[[optional {{template}}]] values"
    (binding [config.file/*env* (assoc @#'config.file/*env* :my-sensitive-password "~~~SeCrEt123~~~")]
      (testing "env var exists"
        (binding [config.file/*config* (mock-config-with-setting "[[{{env MY_SENSITIVE_PASSWORD}}]]")]
          (is (= (mock-config-with-setting "~~~SeCrEt123~~~")
                 (#'config.file/config))))
        (binding [config.file/*config* (mock-config-with-setting "password__[[{{env MY_SENSITIVE_PASSWORD}}]]")]
          (is (= (mock-config-with-setting "password__~~~SeCrEt123~~~")
                 (#'config.file/config))))
        (testing "with text inside optional brackets before/after the templated part"
          (binding [config.file/*config* (mock-config-with-setting "[[before__{{env MY_SENSITIVE_PASSWORD}}__after]]")]
            (is (= (mock-config-with-setting "before__~~~SeCrEt123~~~__after")
                   (#'config.file/config))))))
      (testing "env var does not exist"
        (binding [config.file/*config* (mock-config-with-setting "[[{{env MY_OTHER_SENSITIVE_PASSWORD}}]]")]
          (is (= (mock-config-with-setting "")
                 (#'config.file/config))))
        (binding [config.file/*config* (mock-config-with-setting "password__[[{{env MY_OTHER_SENSITIVE_PASSWORD}}]]")]
          (is (= (mock-config-with-setting "password__")
                 (#'config.file/config))))
        (testing "with text inside optional brackets before/after the templated part"
          (binding [config.file/*config* (mock-config-with-setting "[[before__{{env MY_OTHER_SENSITIVE_PASSWORD}}__after]]")]
            (is (= (mock-config-with-setting "")
                   (#'config.file/config)))))))))

(deftest initialize-section-test
  (testing "Ignore unknown sections"
    (binding [config.file/*config* {:version 1.0, :config {:unknown-section {}}}]
      (let [log-messages (mt/with-log-messages-for-level [metabase.config.file :warn]
                           (is (= :ok
                                  (config.file/initialize!))))]
        (is (= [[:warn nil (u/colorize :yellow "Ignoring unknown config section :unknown-section.")]]
               log-messages))))))

(deftest ^:parallel error-validation-do-not-leak-env-vars-test
  (testing "spec errors should not include contents of env vars -- expand templates after spec validation."
    (binding [config.file/*env*    (assoc @#'config.file/*env* :my-sensitive-password "~~~SeCrEt123~~~")
              config.file/*config* {:version 1
                                    :config  {:users [{:first_name "Cam"
                                                       :last_name  "Era"
                                                       :password   "{{env MY_SENSITIVE_PASSWORD}}"}]}}]
      (is (thrown?
           clojure.lang.ExceptionInfo
           (#'config.file/config)))
      (try
        (#'config.file/config)
        (catch Throwable e
          (letfn [(contains-password? [form]
                    (let [seen-password? (atom false)]
                      (walk/postwalk
                       (fn [form]
                         (when (and (string? form)
                                    (str/includes? form "~~~SeCrEt123~~~"))
                           (reset! seen-password? true))
                         form)
                       form)
                      @seen-password?))]
            (is (not (contains-password? (ex-message e))))
            (is (not (contains-password? (ex-data e))))))))))
