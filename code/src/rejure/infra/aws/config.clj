(ns rejure.infra.aws.config "Configure AWS Cloudformation templates with EDN."
  (:require [clojure.edn :as edn]
            [clojure.string :as string]))

;; # Config Helpers
;; Useful for retrieving serialized config info.
  
(defn eid "Creates environment unique id based on identifier key `k` and `env` keyword."
  [k env]
  (str (name k) "-" (name env)))

(defn get-ssm-param-keys "Gets all System Manager Parameter keys declared in config's `cfg` templates."
  [cfg]
  (reduce (fn [acc [_ tplate]]
            (if (map? (:TemplateBody tplate))
              (let [ks (reduce (fn [acc [_ v]]
                              (if (= (:Type v) "AWS::SSM::Parameter")
                                (conj acc (:Name (:Properties v)))
                                acc))
                            []
                            (:Resources (:TemplateBody tplate)))]
                (into acc ks))
              acc))
          []
          cfg))

;; # Config Reading
;; Provides shorthand declarations and reader literal utilities.

;; ## Shorthands Serializer

(defn- url-tplate? "Checks whether value `v` is a url template declaration."
  [v]
  (vector? v))

(defn- k->aws-resource-type
  "Coverts key `k` to a AWS physical resource identifier type.
   The key should be in ':<Service>.<Module>' format, i.e, :Service.Module -> AWS::Service::Module"
  [k]
  (string/join "::" (cons "AWS" (string/split (name k) #"\."))))

(defn- mk-aws-url-tplate "Makes AWS template from stack `name` and template `url`."
  [name url]
  {:StackName name :TemplateURL url})

(defn- mk-aws-opts-tplate
  "Makes AWS template from stack `name` and options `opts`.
   Allows declaring resources as type+properties tuples that are converted into their map form."
  [name opts]
  {:StackName    name
   :TemplateBody (assoc opts 
                        :Resources 
                        (reduce-kv (fn [m k v]
                                     (assoc m  k (if (vector? v)
                                                     {:Type (k->aws-resource-type (first v))
                                                      :Properties (second v)}
                                                     v)))
                                   {}
                                   (:Resources opts)))})


(defn serialize-config
  "Expects config `cfg` to be a mapping of resource names to template options.
   A template options can either be a vector, if template is dervied from a url, or a map.
   For vector, first arg is url string and second is aws options.
   For map, can declare AWS options directly but we provide a tuple shorthand for declararing 
   a resources, see `->aws-tplate-body` for details."
  [cfg]
  (reduce-kv
   (fn [m k v]
     (let [template   (if (url-tplate? v)
                        (let [[url clf-opts] v]
                          (merge (mk-aws-url-tplate k url)
                                 clf-opts))
                        (mk-aws-opts-tplate k v))]
       (assoc m k template)))
   {}
   cfg))

;; ## Reader Literals Factory

(defn- kv-params->aws-params "Converts key-value map `m` to AWS parameter array specfication."
  [m]
  (into [] (for [[k v] m] {:ParameterKey (name k) :ParameterValue v})))

(defn- k->aws-resource-ref "Coverts key `k` to AWS logical resource reference."
  [k]
  {:Ref (name k)})

(defn- with-aws-ssm-params 
  "Auto-includes a Sytem Manager Parameter for each declared resource identifier in map `m`."
  [m]
  (reduce-kv
   (fn [acc k _]
     (assoc acc
            (keyword (str (name k) "Param"))
            [:SSM.Parameter {:Name (name k) :Value (k->aws-resource-ref k) :Type "String"}]))
   m
   m))

(defn create-readers 
  "Create edn reader literals using `env` keyword and `param` map.
   Where appropriate we match the AWS function utilities provided for YAML/JSON templates."
  [env params]
  {'eid (fn [k] (eid k env))
   'kvp kv-params->aws-params
   'ref k->aws-resource-ref
   'sub (fn [k] (get params k))
   'with-ssm-params with-aws-ssm-params})

;; ## Config Reader

(defn read-edn
  "Reads config edn string `s` based on environment keyword `env` and an optional `params` map.
   See `serialize-config` for templating details."
  ([s env] (read-edn s env {}))
  ([s env params]
   (serialize-config (edn/read-string {:readers (create-readers env params)}
                                      s))))
