(ns etlp.connector-test
  (:require [clojure.test :refer :all]
            [etlp.core-test :refer [hl7-xform]]
            [clojure.pprint :refer [pprint]]
            [etlp.utils :refer [wrap-log wrap-record]]
            [etlp.s3 :refer [create-s3-source! create-s3-list-source!]]
            [etlp.connector :refer [create-stdout-destination! create-connection etlp-source etlp-destination]]
            [etlp.async :refer [save-into-database]]
            [cognitect.aws.client.api :as aws]
            [clojure.core.async :as a]
            [clojure.java.io :as io])
  (:import [clojure.core.async.impl.channels ManyToManyChannel]))

(def not-nill (comp (partial not) nil?))


(def test-data [[[4 4 1 1] [1 2 3 4] [2 3 4 5 6 4] [1321 3214 241234 66234] [232 4214 281234 88234]]
                [[2 2 2 2] [3 4 5 6] [3 4 5 6 7 8] [2432 4325 352345 77345] [343 5325 392345 98345]]])


(def s3-config {:region "us-east-1"
                :credentials {:access-key-id (System/getenv "ACCESS_KEY_ID")
                              :secret-access-key (System/getenv "SECRET_ACCESS_KEY_ID")}})



(def remove-me-before-commit "hl710M/ADT10MSplit_10")

(def etlp-s3-source {:s3-config s3-config
                     :bucket (System/getenv "ETLP_TEST_BUCKET")
                     :prefix "hl710M/ADT10MSplit_10"
                     :reducers {:hl7-reducer
                                (comp
                                 (hl7-xform {})
                                 (map (fn [segments]
                                        (clojure.string/join "\r" segments))))}
                     :reducer :hl7-reducer})

(def connect-etlp {:xform       (comp (map wrap-record))
                   :source      (create-s3-list-source! etlp-s3-source)
                   :destination (create-stdout-destination! {})})

(defn th [opts]
  (-> (create-connection opts)
     .start))

(defn ffuture [](future (.start (Thread.  #(th connect-etlp))) (.getId (Thread/currentThread))))

(deftest test-etlp-connection
  (is (= nil (ffuture))))


(def mock-topo {:workflow [[:processor-1 :processor-2]
                           [:processor-2 :processor-3]
                           [:processor-3 :processor-4]
                           [:processor-4 :processor-5]]
                :entities {:processor-1 {:channel (a/chan 1)
                                         :meta    {:entity-type :processor
                                                   :processor   (fn [ch]
                                                                 (if (instance? ManyToManyChannel ch)
                                                                   (a/onto-chan ch test-data)
                                                                   (a/to-chan test-data)))}}
                           :processor-2 {:channel (a/chan 1)
                                         :meta    {:entity-type :processor
                                                   :processor   (fn [ch]
                                                                 (a/pipe (ch :channel)
                                                                         (a/chan 1 (comp
                                                                                    (mapcat (fn [l] l))
                                                                                    (filter not-nill)
                                                                                    (map (fn [lst] (reduce + lst)))
                                                                                    (map #(* 2 %))
                                                                                    (map #(* 3 %))))))}}
                           :processor-3 {:channel (a/chan 1)
                                         :meta    {:processor   (fn [ch] (a/pipe (ch :channel)
                                                                               (a/chan 1 (comp
                                                                                          (filter not-nill)
                                                                                          (filter number?)
                                                                                          (map #(* 2 %))))))
                                                   :entity-type :processor}}

                           :processor-4 {:channel (a/chan 1)
                                         :meta    {:entity-type :processor
                                                   :processor   (fn [ch]
                                                                 (a/pipe (ch :channel)
                                                                         (a/chan 1 (comp
                                                                                    (filter not-nill)
                                                                                    (filter number?)
                                                                                    (map #(* 3 %))))))}}
                           :processor-5 {:channel (a/chan 1)
                                         :meta    {:entity-type :processor
                                                   :processor   (fn [ch]
                                                                 (if (instance? ManyToManyChannel ch)
                                                                   ch
                                                                   (ch :channel)))}}}})


;; (deftest test-connect-processors
;;   (let [topology (atom mock-topo)
;;         entities (@topology :entities)
;;         etlp-line (connector/connect @topology)]
;;     (clojure.pprint/pprint "Case One Begins")
;;     (a/<!!
;;      (a/pipeline 1 (doto (a/chan) (a/close!))
;;                  (map (fn [d] (println "Invoked from pipeline" d) d))
;;                  (get-in etlp-line [:processor-5 :channel]) (fn [ex]
;;                                                               (println (str "Execetion Caught" ex)))))))



(def simple-topo {:workflow [[:processor-1 :xform-1]
                             [:xform-1 :processor-2]
                             [:processor-2 :xform-2]
                             [:xform-2 :processor-3]
                             [:processor-3 :xform-3]
                             [:xform-3 :processor-4]]
                  :entities {:processor-1 {:meta {:entity-type :processor
                                                  :processor   (fn [ch]
                                                                (if (instance? ManyToManyChannel ch)
                                                                  (a/onto-chan ch test-data)
                                                                  (a/to-chan test-data)))}}
                             :processor-2 {:meta {:entity-type :processor
                                                  :processor   (fn [ch]
                                                                (if (instance? ManyToManyChannel ch)
                                                                  ch
                                                                  (ch :channel)))}}

                             :processor-3 {:meta {:entity-type :processor
                                                  :processor   (fn [ch]
                                                                (if (instance? ManyToManyChannel ch)
                                                                  ch
                                                                  (ch :channel)))}}
                             :processor-4 {:meta {:entity-type :processor
                                                  :processor   (fn [ch]
                                                                (if (instance? ManyToManyChannel ch)
                                                                  ch
                                                                  (ch :channel)))}}

                             :xform-1 {:meta {:entity-type :xform-provider
                                              :xform       (comp
                                                            (mapcat (fn [l] l))
                                                            (filter not-nill)
                                                            (map (fn [lst] (reduce + lst)))
                                                            (map #(* 2 %))
                                                            (map #(* 3 %)))}}
                             :xform-2 {:meta {:xform       (comp
                                                            (filter not-nill)
                                                            (filter number?)
                                                            (map #(* 2 %)))
                                              :entity-type :xform-provider}}
                             :xform-3 {:meta {:xform       (comp
                                                            (filter not-nill)
                                                            (filter number?)
                                                            (map #(* 3 %)))
                                              :entity-type :xform-provider}}}})


;; (deftest test-connect-xform-and-processors
;;   (let [topology (atom simple-topo)
;;         etlp-line (connector/connect @topology)]
;;     (clojure.pprint/pprint "Case 2 begins")
;;     (a/<!!
;;      (a/pipeline 1 (doto (a/chan) (a/close!))
;;                  (map (fn [d] (println "Invoked from ETLP pipeline ::\n" d) d))
;;                  (get-in etlp-line [:processor-4 :channel]) (fn [ex]
;;                                                               (println (str "Execetion Caught" ex)))))))

(comment
  (doseq [path (:workflow {:topology mock-topo})]
    (let [[input-key output-key] path
          input-chan (get-in {} [input-key :channel])
          output-chan (get-in {} [output-key :channel])]
        ;; (pprint (nil? input-chan))
        ;; (pprint (nil? output-chan))
      (if (and (not-nill input-chan) (not-nill output-chan))
        (a/pipe input-chan output-chan)))))
