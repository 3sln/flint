(ns conform.runner
  (:require [conform.basics :as basics]))

(defn- run-one [x]
  (let [actual (try ((:thunk x))
                    (catch Throwable e (str "threw " (ex-message e))))]
    (assoc x :actual actual :ok (= actual (:expected x)))))

(defn main [_args]
  (let [results (map run-one (basics/cases))
        failures (filter (fn [r] (not (:ok r))) results)]
    (if (empty? failures)
      (str "PASS " (count results))
      (reduce (fn [acc r]
                (str acc "FAIL " (:label r)
                     "\n  expected " (pr-str (:expected r))
                     "\n  actual   " (pr-str (:actual r)) "\n"))
              (str "FAILED " (count failures) "/" (count results) "\n")
              failures))))
