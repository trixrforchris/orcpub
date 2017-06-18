(ns orcpub.routes
  (:require [io.pedestal.http :as http]          
            [io.pedestal.http.route :as route]
            [io.pedestal.test :as test]
            [io.pedestal.http.ring-middlewares :as ring]
            [ring.middleware.cookies :only [wrap-cookies]]
            [ring.util.response :as ring-resp]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.interceptor.error :as error-int]
            [io.pedestal.interceptor.chain :refer [terminate]]
            #_[com.stuartsierra.component :as component]
            [buddy.auth.protocols :as proto]
            [buddy.auth.backends :as backends]
            [buddy.sign.jwt :as jwt]
            [buddy.hashers :as hashers]
            [buddy.auth.middleware :refer [authentication-request]]
            [clojure.java.io :as io]
            [clj-time.core :as t :refer [hours from-now ago]]
            [clj-time.coerce :as tc :refer [from-date]]
            [clojure.string :as s]
            [clojure.spec :as spec]
            [orcpub.dnd.e5.skills :as skill5e]
            [orcpub.dnd.e5.character :as char5e]
            [orcpub.dnd.e5.spells :as spells]
            [datomic.api :as d]
            [bidi.bidi :as bidi]
            [orcpub.route-map :as route-map]
            [orcpub.errors :as errors]
            [orcpub.privacy :as privacy]
            [orcpub.email :as email]
            [orcpub.pdf :as pdf]
            [orcpub.registration :as registration]
            [orcpub.entity.strict :as se]
            [orcpub.entity :as entity]
            [orcpub.routes.party :as party]
            [hiccup.page :as page]
            [environ.core :as environ]
            [clojure.set :as sets])
  (:import (org.apache.pdfbox.pdmodel.interactive.form PDCheckBox PDComboBox PDListBox PDRadioButton PDTextField)
           
           (org.apache.pdfbox.pdmodel PDDocument PDPage PDPageContentStream)
           (org.apache.pdfbox.pdmodel.graphics.image PDImageXObject)
           (java.io ByteArrayOutputStream ByteArrayInputStream)
           (org.apache.pdfbox.pdmodel.graphics.image JPEGFactory LosslessFactory)
           (org.eclipse.jetty.server.handler.gzip GzipHandler)
           (javax.imageio ImageIO)
           (java.net URL))
  (:gen-class))

(def backend (backends/jws {:secret (environ/env :signature)}))

(defn first-user-by [db query value]
  (let [result (d/q query
                    db
                    value)
        user-id (ffirst result)]
    (d/pull db '[*] user-id)))

(def username-query
  '[:find ?e
    :in $ ?username
    :where [?e :orcpub.user/username ?username]])

(def email-query
  '[:find ?e
    :in $ ?email
    :where [?e :orcpub.user/email ?email]])

(defn find-user-by-username-or-email [db username-or-email]
  (first-user-by db
                 '[:find ?e
                   :in $ ?user-or-email
                   :where (or [?e :orcpub.user/username ?user-or-email]
                              [?e :orcpub.user/email ?user-or-email])]
                 username-or-email))

(defn find-user-by-username [db username]
  (first-user-by db
                 '[:find ?e
                   :in $ ?username
                   :where [?e :orcpub.user/username ?username]]
                 username))

(defn lookup-user [db username password]
  (let [user (first-user-by db
                         '{:find [?e]
                           :in [$ [?username ?password]]
                           :where [(or [?e :orcpub.user/username ?username]
                                       [?e :orcpub.user/email ?username])
                                   [?e :orcpub.user/password ?enc]
                                   [(buddy.hashers/check ?password ?enc)]]}
                         [username password])]
    user))

(def check-auth
  {:name :check-auth
   :enter (fn [context]
            (let [request (:request context)
                  updated-request (authentication-request request backend)]
              (if (:identity updated-request)
                (assoc context :request updated-request)
                (-> context
                    terminate
                    (assoc :response {:status 401 :body {:message "Unauthorized"}})))))})

(defn party-owner [db id]
  (d/q '[:find ?owner .
         :in $ ?id
         :where [?id :orcpub.dnd.e5.party/owner ?owner]]
       db
       id))

(def parse-id
  {:name :parse-id
   :enter (fn [context]
            (update-in context
                       [:request :path-params :id]
                       (fn [id] (if id (Long/parseLong id)))))})


(def check-party-owner
  {:name :check-party-owner
   :enter (fn [context]
            (let [{:keys [identity db] {:keys [id]} :path-params} (:request context)
                  party-owner (party-owner db id)]
              (if (= (:user identity) party-owner)
                context
                (-> context
                    terminate
                    (assoc :response {:status 401 :body {:message "You don't own the party"}})))))})

(defn redirect [route-key]
  (ring-resp/redirect (route-map/path-for route-key)))


(defn verification-expired? [verification-sent]
  (t/before? (from-date verification-sent) (-> 24 hours ago)))

(defn login-error [error-key & [data]]
  {:status 401 :body (merge
                      data
                      {:error error-key})})

(defn create-token [username exp]
  (jwt/sign {:user username
             :exp exp}
            (environ/env :signature)))

(defn following-usernames [db ids]
  (prn "IDS" ids)
  (map :orcpub.user/username
       (d/pull-many db '[:orcpub.user/username] ids)))

(defn user-body [db user]
  {:username (:orcpub.user/username user)
   :email (:orcpub.user/email user)
   :following (following-usernames db (map :db/id (:orcpub.user/following user)))})

(defn login-response
  [{:keys [json-params db] :as request}]
  (let [{raw-username :username raw-password :password} json-params]
    (cond
      (s/blank? raw-username) (login-error errors/username-required)
      (s/blank? raw-password) (login-error errors/password-required)
      :else (let [username (s/trim raw-username)
                  password (s/trim raw-password)
                  {:keys [:orcpub.user/verified?
                          :orcpub.user/verification-sent
                          :orcpub.user/email
                          :db/id] :as user} (lookup-user db username password)
                  unverified? (not verified?)
                  expired? (and verification-sent (verification-expired? verification-sent))]
              (cond
                (nil? id) (let [user-for-username (find-user-by-username-or-email db username)]
                            (login-error (if (:db/id user-for-username)
                                           errors/bad-credentials
                                           errors/no-account)))
                (and unverified? expired?) (login-error errors/unverified-expired)
                unverified? (login-error errors/unverified {:email email})
                :else (let [token (create-token (:orcpub.user/username user)
                                                (-> 24 hours from-now))]
                        {:status 200 :body {:user-data (user-body db user)
                                            :token token}}))))))

(defn login [{:keys [json-params db] :as request}]
  (try
    (login-response request)
    (catch Throwable e (do (prn "E" e) (throw e)))))

(defn base-url [{:keys [scheme headers]}]
  (str (name scheme) "://" (headers "host")))

(defn send-verification-email [request params verification-key]
  (email/send-verification-email
   (base-url request)
   params
   verification-key))

(defn do-verification [request params conn & [tx-data]]
  (let [verification-key (str (java.util.UUID/randomUUID))
        now (java.util.Date.)]
    (do @(d/transact
          conn
          [(merge
            tx-data
            {:orcpub.user/verified? false
             :orcpub.user/verification-key verification-key
             :orcpub.user/verification-sent now})])
        (send-verification-email request params verification-key)
        {:status 200})))

(defn register [{:keys [json-params db conn] :as request}]
  (let [{:keys [username email password first-and-last-name send-updates?]} json-params
        username (s/trim username)
        email (s/trim email)
        password (s/trim password)
        validation (registration/validate-registration
                    json-params
                    (seq (d/q email-query db email))
                    (seq (d/q username-query db username)))]
    (try
      (if (seq validation)
        {:status 400
         :body validation}
        (do-verification
         request
         json-params
         conn
         {:orcpub.user/email email
          :orcpub.user/username username
          :orcpub.user/password (hashers/encrypt password)
          :orcpub.user/first-and-last-name first-and-last-name
          :orcpub.user/send-updates? send-updates?
          :orcpub.user/created (java.util.Date.)}))
      (catch Throwable e (do (prn e) (throw e))))))

(def user-for-verification-key-query
  '[:find ?e
    :in $ ?key
    :where [?e :orcpub.user/verification-key ?key]])

(def user-for-email-query
  '[:find ?e
    :in $ ?email
    :where [?e :orcpub.user/email ?email]])

(defn user-for-verification-key [db key]
  (first-user-by db user-for-verification-key-query key))

(defn user-for-email [db email]
  (first-user-by db user-for-email-query email))

(defn user-id-for-username [db username]
  (d/q
   '[:find ?e .
     :in $ ?username
     :where [?e :orcpub.user/username ?username]]
   db
   username))

(defn verify [{:keys [query-params db conn] :as request}]
  (let [key (:key query-params)
        {:keys [:orcpub.user/verification-sent
                :orcpub.user/verified?
                :db/id] :as user} (user-for-verification-key (d/db conn) key)]
    (if verified?
      (redirect route-map/verify-success-route)
      (if (or (nil? verification-sent)
              (verification-expired? verification-sent))
        (redirect route-map/verify-failed-route)
        (do (d/transact conn [{:db/id id
                               :orcpub.user/verified? true}])
            (redirect route-map/verify-success-route))))))

(defn re-verify [{:keys [query-params db conn] :as request}]
  (let [email (:email query-params)
        {:keys [:orcpub.user/verification-sent
                :orcpub.user/verified?
                :orcpub.user/first-and-last-name
                :db/id] :as user} (user-for-email db email)]
    (if verified?
      (redirect route-map/verify-success-route)
      (do-verification request
                       (merge query-params
                              {:first-and-last-name first-and-last-name})
                       conn
                       {:db/id id}))))

(defn do-send-password-reset [user-id first-and-last-name email conn request]
  (let [key (str (java.util.UUID/randomUUID))]
    @(d/transact
      conn
      [{:db/id user-id
        :orcpub.user/password-reset-key key
        :orcpub.user/password-reset-sent (java.util.Date.)}])
    (email/send-reset-email
     (base-url request)
     {:first-and-last-name first-and-last-name
      :email email}
     key)
    {:status 200}))

(defn password-reset-expired? [password-reset-sent]
  (and password-reset-sent (t/before? (tc/from-date password-reset-sent) (-> 24 hours ago))))

(defn password-already-reset? [password-reset password-reset-sent]
  (and password-reset (t/before? (tc/from-date password-reset-sent) (tc/from-date password-reset))))

(defn send-password-reset [{:keys [query-params db conn scheme headers] :as request}]
  (try
    (let [email (:email query-params)
          {:keys [:orcpub.user/first-and-last-name
                  :orcpub.user/password-reset-sent
                  :orcpub.user/password-reset
                  :db/id] :as user} (user-for-email db email)
          expired? (password-reset-expired? password-reset-sent)
          already-reset? (password-already-reset? password-reset password-reset-sent)]
      (if id
        (do-send-password-reset id first-and-last-name email conn request)
        {:status 400 :body {:error :no-account}}))
    (catch Throwable e (do (prn e) (throw e)))))

(defn do-password-reset [conn user-id password]
  @(d/transact
    conn
    [{:db/id user-id
      :orcpub.user/password (hashers/encrypt password)
      :orcpub.user/password-reset (java.util.Date.)}])
  {:status 200})

(defn reset-password [{:keys [json-params db conn cookies identity] :as request}]
  (try
    (let [{:keys [password verify-password]} json-params
          username (:user identity)
          {:keys [:db/id] :as user} (first-user-by db username-query username)]
      (cond
        (not= password verify-password) {:status 400 :message "Passwords do not match"}
        (seq (registration/validate-password password)) {:status 400 :message "New password is invalid"}
        :else (do-password-reset conn id password)))
    (catch Throwable t (do (prn t) (throw t)))))

(def font-sizes
  (merge
   (zipmap (map :key skill5e/skills) (repeat 8))
   (zipmap (map (fn [k] (keyword (str (name k) "-save"))) char5e/ability-keys) (repeat 8))
   {:personality-traits 8
    :ideals 8
    :bonds 8
    :flaws 8
    :features-and-traits 8
    :features-and-traits-2 8
    :attacks-and-spellcasting 8
    :backstory 8
    :other-profs 8
    :equipment 8
    :weapon-name-1 8
    :weapon-name-2 8
    :weapon-name-3 8}))

(defn add-spell-cards! [doc spells-known spell-save-dcs spell-attack-mods]
  (let [flat-spells (flatten (vals spells-known))
        sorted-spells (sort-by
                       (fn [{:keys [class key]}]
                         [class key])
                       flat-spells)
        parts (vec (partition-all 9 sorted-spells))]
    (doseq [i (range (count parts))
            :let [part (parts i)]]
      (let [page (PDPage.)
            cs (PDPageContentStream. doc page)]
        (.addPage doc page)
        (let [spells (map
                      (fn [{:keys [key class]}]
                        {:spell (spells/spell-map key)
                         :class-nm class
                         :dc (spell-save-dcs class)
                         :attack-bonus (spell-attack-mods class)})
                      part)
              remaining-desc-lines (vec
                                    (pdf/print-spells
                                     cs
                                     doc
                                     2.5
                                     3.5
                                     spells
                                     i))
              back-page (PDPage.)
              _ (.close cs)
              back-page-cs (PDPageContentStream. doc back-page)]
          (.addPage doc back-page)
          (pdf/print-backs back-page-cs doc 2.5 3.5 remaining-desc-lines i)
          (.close back-page-cs))))))

(defn character-pdf-2 [req]
  (let [fields (-> req :form-params :body clojure.edn/read-string)
        {:keys [image-url image-url-failed faction-image-url faction-image-url-failed spells-known spell-save-dcs spell-attack-mods]} fields
        input (.openStream (io/resource (cond
                                          (find fields :spellcasting-class-6) "fillable-char-sheet-6-spells.pdf"
                                          (find fields :spellcasting-class-5) "fillable-char-sheet-5-spells.pdf"
                                          (find fields :spellcasting-class-4) "fillable-char-sheet-4-spells.pdf"
                                          (find fields :spellcasting-class-3) "fillable-char-sheet-3-spells.pdf"
                                          (find fields :spellcasting-class-2) "fillable-char-sheet-2-spells.pdf"
                                          (find fields :spellcasting-class-1) "fillable-char-sheet-1-spells.pdf"
                                          :else "fillable-char-sheet-0-spells.pdf")))
        output (ByteArrayOutputStream.)
        user-agent (get-in req [:headers "user-agent"])
        chrome? (re-matches #".*Chrome.*" user-agent)]
    (with-open [doc (PDDocument/load input)]
      (pdf/write-fields! doc fields (not chrome?) font-sizes)
      (if (seq spells-known)
        (add-spell-cards! doc spells-known spell-save-dcs spell-attack-mods))
      (if (and image-url
               (re-matches #"^(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]" image-url)
               (not image-url-failed))
        (pdf/draw-image! doc (pdf/get-page doc 1) image-url 0.45 1.75 2.35 3.15))
      (if (and faction-image-url
               (re-matches #"^(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]" faction-image-url)
               (not faction-image-url-failed))
        (pdf/draw-image! doc (pdf/get-page doc 1) faction-image-url 5.88 2.4 1.905 1.52))
      (.save doc output))
    (let [a (.toByteArray output)]
      {:status 200 :body (ByteArrayInputStream. a)})))

(defn html-response
  [html & [response]]
  (let [merged (merge
                response
                {:status 200
                 :body html
                 :headers {"Content-Type" "text/html"
                           "Cache-Control" "no-cache, no-store, must-revalidate"
                           "Pragma" "no-cache"
                           "Expires" "0"}})]
    merged))

(defn index [{:keys [headers scheme uri server-name]} & [response]]
  (html-response
   (slurp (io/resource "public/index.html"))
   response))

(defn empty-index [req & [response]]
  (html-response
   (slurp (io/resource "public/blank.html"))
   response))

(defn verification-expired [req]
  (index req))

(defn verification-successful [req]
  (index req))

(defn verify-sent [req]
  (index req))

(defn registration-page [req]
  (index req))

(defn login-page [req]
  (index req))

(defn character-list-page [req]
  (index req))

(defn parties-page [req]
  (index req))

(defn monster-list-page [req]
  (index req))

(defn character-page [req]
  (index req))

(def user-by-password-reset-key-query
  '[:find ?e
    :in $ ?key
    :where [?e :orcpub.user/password-reset-key ?key]])

(defn reset-password-page [{:keys [query-params db conn] :as req}]
  (let [key (:key query-params)
        {:keys [:db/id
                :orcpub.user/username
                :orcpub.user/password-reset-key
                :orcpub.user/password-reset-sent
                :orcpub.user/password-reset] :as user} (first-user-by db user-by-password-reset-key-query key)
        expired? (password-reset-expired? password-reset-sent) 
        already-reset? (password-already-reset? password-reset password-reset-sent)]
    (cond
      expired? (redirect route-map/password-reset-expired-route)
      already-reset? (redirect route-map/password-reset-used-route)
      :else (let [token (create-token username (-> 1 hours from-now))]
              (index req {:cookies {"token" token}})))))

(defn password-reset-sent-page [req]
  (index req))

(defn password-reset-expired-page [req]
  (index req))

(defn password-reset-used-page [req]
  (index req))

(defn send-password-reset-page [req]
  (index req))

(defn character-builder-page [req]
  (index req))

(defn check-field [query value db]
  {:status 200
   :body (-> (d/q query db value)
             seq
             boolean
             str)})

(defn check-username [{:keys [db query-params]}]
  (check-field username-query (:username query-params) db))

(defn check-email [{:keys [db query-params]}]
  (check-field email-query (:email query-params) db))

(defn character-for-id [db id]
  (d/pull db '[*] id))

(defn children [n]
  (cond
    (map? n) (filter
              (fn [v]
                (or (map? v)
                    (sequential? v)))
              (vals n))
    (sequential? n) n
    :else nil))

(defn diff-branch [ids]
  (fn [n]
    (or
     (and (map? n)
          (ids (:db/id n)))
     (sequential? n))))

(defn db-ids [entity & [branch-fn]]
  (disj
   (set
    (map
     :db/id
     (tree-seq
      (or branch-fn children)
      children
      entity))) nil))

(defn remove-orphan-ids-aux [remove-ids? entity]
  (cond
    (map? entity)
    (into {}
          (map
           (fn [[k v]]
             [k (remove-orphan-ids-aux (or remove-ids?
                                           (-> entity :db/id nil?))
                                       v)])
           (if remove-ids?
             (dissoc entity :db/id)
             entity)))

    (sequential? entity)
    (map (partial remove-orphan-ids-aux remove-ids?) entity)

    :else
    entity))

(defn remove-orphan-ids [entity]
  (remove-orphan-ids-aux false entity))

(defn remove-ids [entity]
  (remove-orphan-ids-aux true entity))

(defn owns-entity? [db username entity-id]
  (let [{:keys [:orcpub.user/username :orcpub.user/email]} (find-user-by-username db username)
        {:keys [:orcpub.entity.strict/owner]} (d/pull db '[:orcpub.entity.strict/owner] entity-id)]
    (#{username email} owner)))

(defn entity-problem [desc actual expected]
  (str desc ", expected: " expected ", actual: " actual))

(defn entity-type-problems [expected-game expected-version expected-type {:keys [::se/type ::se/game ::se/game-version]}]
  (cond-> nil
    (not= expected-game game) (conj (entity-problem "Entity is from the wrong game" game expected-game))
    (not= expected-version game-version) (conj (entity-problem "Entity is from the wrong game version" game-version expected-version))
    (not= expected-type type) (conj (entity-problem "Entity is wrong type" type expected-type))))

(def dnd-e5-char-type-problems (partial entity-type-problems :dnd :e5 :character))

(defn update-character [db conn character username]
  (let [id (:db/id character)]
    (if (owns-entity? db username id)
      (let [current-character (d/pull db '[*] id)
            _ (prn "CURRENT CHARACTER" current-character)
            problems [] #_(dnd-e5-char-type-problems current-character)
            current-valid? (spec/valid? ::se/entity current-character)]
        (if (not current-valid?)
          (do (prn "INVALID CHARACTER FOUND, REPLACING" #_current-character)
              (prn "INVALID CHARACTER EXPLANATION" #_(spec/explain-data ::se/entity current-character))))
        (if (seq problems)
          {:status 400 :body problems}
          (if (not current-valid?)
            (let [new-character (remove-ids character)
                  tx [[:db/retractEntity (:db/id current-character)]
                      (assoc new-character
                             :db/id "tempid"
                             :orcpub.entity.strict/owner username)]
                  result @(d/transact conn tx)]
              {:status 200
               :body (d/pull (d/db conn) '[*] (-> result :tempids (get "tempid")))})
            (let [new-character (remove-orphan-ids character)
                  current-ids (db-ids current-character)
                  new-ids (db-ids new-character)
                  retract-ids (sets/difference current-ids new-ids)
                  retractions (map
                               (fn [retract-id]
                                 [:db/retractEntity retract-id])
                               retract-ids)
                  tx (conj retractions
                           (assoc new-character :orcpub.entity.strict/owner username))]
              @(d/transact conn tx)
              {:status 200
               :body (d/pull (d/db conn) '[*] id)}))))
      {:status 401 :body "You do not own this character"})))

(defn create-new-character [conn character username]
  (let [result @(d/transact conn
                            [(assoc character
                                    :db/id "tempid"
                                    ::se/owner username
                                    ::se/game :dnd
                                    ::se/game-version :e5
                                    ::se/type :character)])
        new-id (-> result :tempids (get "tempid"))]
    {:status 200
     :body (d/pull (d/db conn) '[*] new-id)}))

(defn do-save-character [db conn transit-params identity]
  (let [character (entity/remove-empty-fields transit-params)
        username (:user identity)
        current-id (:db/id character)]
    (prn "USER" username)
    (prn "CHARACTER" character)
    (try
      (if-let [data (spec/explain-data ::se/entity character)]
        {:status 400 :body data}
        (if (:db/id character)
          (update-character db conn character username)
          (create-new-character conn character username)))
      (catch Exception e (do (prn "ERROR" e) (throw e))))))

(defn save-character [{:keys [db transit-params body conn identity] :as request}]
  (do-save-character db conn transit-params identity))

(defn character-list [{:keys [db transit-params body conn identity] :as request}]
  (let [username (:user identity)
        user (find-user-by-username-or-email db username)
        ids (d/q '[:find ?e
                   :in $ [?idents ...]
                   :where
                   [?e ::se/owner ?idents]
                   ;; uncomment these once all characters have the data
                   #_[?e ::se/type :character]
                   #_[?e ::se/game :dnd]
                   #_[?e ::se/game-version :e5]]
                 db
                 [(:orcpub.user/username user)
                  (:orcpub.user/email user)])
        characters (d/pull-many db '[*] (map first ids))]
    {:status 200 :body characters}))

(defn character-summary-list [{:keys [db body conn identity] :as request}]
  (let [username (:user identity)
        user (find-user-by-username-or-email db username)
        following-ids (map :db/id (:orcpub.user/following user))
        following-usernames (following-usernames db following-ids)
        results (d/q '[:find (pull ?s [*]) ?e ?owner
                       :in $ [?idents ...]
                       :where
                       [?e ::se/owner ?owner]
                       [?e ::se/owner ?idents]
                       [?e ::se/summary ?s]
                       ;; uncomment these once all characters have the data
                       #_[?e ::se/type :character]
                       #_[?e ::se/game :dnd]
                       #_[?e ::se/game-version :e5]]
                     db
                     (concat
                      [(:orcpub.user/username user)
                       (:orcpub.user/email user)]
                      following-usernames))
        characters (map (fn [[summary id owner]]
                          (assoc summary
                                 :db/id id
                                 :orcpub.entity.strict/owner owner))
                        results)]
    {:status 200 :body characters}))

(defn follow-user [{:keys [db conn identity] {:keys [user]} :path-params}]
  (let [other-user-id (user-id-for-username db user)
        username (:user identity)
        user-id (user-id-for-username db username)]
    @(d/transact conn [{:db/id user-id
                        :orcpub.user/following other-user-id}])
    {:status 200}))

(defn unfollow-user [{:keys [db conn identity] {:keys [user]} :path-params}]
  (let [other-user-id (user-id-for-username db user)
        username (:user identity)
        user-id (user-id-for-username db username)]
    @(d/transact conn [[:db/retract user-id :orcpub.user/following other-user-id]])
    {:status 200}))

(defn delete-character [{:keys [db conn identity] {:keys [id]} :path-params}]
  (let [parsed-id (Long/parseLong id)
        username (:user identity)
        character (d/pull db '[*] parsed-id)
        problems [] #_(dnd-e5-char-type-problems character)]
    (if (owns-entity? db username parsed-id)
      (if (empty? problems)
        (do
          @(d/transact conn [[:db/retractEntity parsed-id]])
          {:status 200})
        {:status 400 :body problems})
      {:status 401 :body "You do not own this character"})))

(defn get-character-for-id [db id]
  (let [{:keys [::se/type ::se/game ::se/game-version] :as character} (d/pull db '[*] id)
        problems [] #_(dnd-e5-char-type-problems character)]
    (if (seq problems)
      {:status 400 :body problems}
      character)))

(defn get-character [{:keys [db] {:keys [:id]} :path-params}]
  (let [parsed-id (Long/parseLong id)]
    {:status 200 :body (get-character-for-id db parsed-id)}))

(defn get-user [{:keys [db identity]}]
  (let [username (:user identity)
        user (find-user-by-username-or-email db username)]
    {:status 200 :body (user-body db user)}))

(def header-style
  {:style "color:#2c3445"})

(defn terms-page [body-fn]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (body-fn)})

(defn privacy-policy-page [req]
  (terms-page privacy/privacy-policy))

(defn terms-of-use-page [req]
  (terms-page privacy/terms-of-use))

(defn community-guidelines-page [_]
  (terms-page privacy/community-guidelines))

(defn cookie-policy-page [_]
  (terms-page privacy/cookie-policy))

(defn health-check [_]
  {:status 200 :body "OK"})

(def routes
  (route/expand-routes
   [[["/" {:get `index}]
     [(route-map/path-for route-map/register-route) ^:interceptors [(body-params/body-params)]
      {:post `register}]
     [(route-map/path-for route-map/user-route) ^:interceptors [check-auth]
      {:get `get-user}]
     [(route-map/path-for route-map/follow-user-route :user ":user") ^:interceptors [check-auth]
      {:post `follow-user
       :delete `unfollow-user}]
     [(route-map/path-for route-map/dnd-e5-char-list-route) ^:interceptors [(body-params/body-params) check-auth]
      {:post `save-character
       :get `character-list}]
     [(route-map/path-for route-map/dnd-e5-char-summary-list-route) ^:interceptors [(body-params/body-params) check-auth]
      {:get `character-summary-list}]
     [(route-map/path-for route-map/dnd-e5-char-route :id ":id") ^:interceptors [check-auth]
      {:delete `delete-character}]
     [(route-map/path-for route-map/dnd-e5-char-route :id ":id")
      {:get `get-character}]
     [(route-map/path-for route-map/dnd-e5-char-parties-route) ^:interceptors [(body-params/body-params) check-auth]
      {:post `party/create-party
       :get `party/parties}]
     [(route-map/path-for route-map/dnd-e5-char-party-route :id ":id") ^:interceptors [(body-params/body-params) check-auth parse-id check-party-owner]
      {:delete `party/delete-party}]
     
     [(route-map/path-for route-map/dnd-e5-char-party-name-route :id ":id") ^:interceptors [(body-params/body-params) check-auth parse-id check-party-owner]
      {:put `party/update-party-name}]
     [(route-map/path-for route-map/dnd-e5-char-party-characters-route :id ":id") ^:interceptors [(body-params/body-params) check-auth parse-id check-party-owner]
      {:post `party/add-character}]
     [(route-map/path-for route-map/dnd-e5-char-party-character-route :id ":id" :character-id ":character-id") ^:interceptors [(body-params/body-params) check-auth parse-id check-party-owner]
      {:delete `party/remove-character}]
     [(route-map/path-for route-map/dnd-e5-char-list-page-route) ^:interceptors [(body-params/body-params)]
      {:get `character-list-page}]
     [(route-map/path-for route-map/dnd-e5-char-parties-page-route)
      {:get `parties-page}]
     [(route-map/path-for route-map/dnd-e5-monster-list-page-route) ^:interceptors [(body-params/body-params)]
      {:get `monster-list-page}]
     [(route-map/path-for route-map/dnd-e5-char-page-route :id ":id")
      {:get `character-page}]
     
     [(route-map/path-for route-map/dnd-e5-char-builder-route) ^:interceptors [(body-params/body-params)]
      {:get `character-builder-page}]
     [(route-map/path-for route-map/login-route) ^:interceptors [(body-params/body-params)]
      {:post `login}]
     [(route-map/path-for route-map/character-pdf-route) ^:interceptors [(body-params/body-params)]
      {:post `character-pdf-2}]
     [(route-map/path-for route-map/verify-route)
      {:get `verify}]
     [(route-map/path-for route-map/verify-sent-route)
      {:get `verify-sent}]
     [(route-map/path-for route-map/reset-password-page-route) ^:interceptors [ring/cookies]
      {:get `reset-password-page}]
     [(route-map/path-for route-map/send-password-reset-page-route)
      {:get `send-password-reset-page}]
     [(route-map/path-for route-map/password-reset-sent-route)
      {:get `password-reset-sent-page}]
     [(route-map/path-for route-map/password-reset-expired-route)
      {:get `password-reset-expired-page}]
     [(route-map/path-for route-map/password-reset-used-route)
      {:get `password-reset-used-page}]
     [(route-map/path-for route-map/re-verify-route) ^:interceptors [(body-params/body-params)]
      {:get `re-verify}]
     [(route-map/path-for route-map/reset-password-route) ^:interceptors [(body-params/body-params) ring/cookies check-auth]
      {:post `reset-password}]
     [(route-map/path-for route-map/send-password-reset-route) ^:interceptors [(body-params/body-params)]
      {:get `send-password-reset}]
     [(route-map/path-for route-map/verify-failed-route)
      {:get `verification-expired}]
     [(route-map/path-for route-map/verify-success-route)
      {:get `verification-successful}]
     [(route-map/path-for route-map/register-page-route)
      {:get `registration-page}]
     [(route-map/path-for route-map/login-page-route)
      {:get `login-page}]
     [(route-map/path-for route-map/privacy-policy-route)
      {:get `privacy-policy-page}]
     [(route-map/path-for route-map/terms-of-use-route)
      {:get `terms-of-use-page}]
     [(route-map/path-for route-map/community-guidelines-route)
      {:get `community-guidelines-page}]
     [(route-map/path-for route-map/cookies-policy-route)
      {:get `cookie-policy-page}]
     [(route-map/path-for route-map/check-email-route)
      {:get `check-email}]
     [(route-map/path-for route-map/check-username-route)
      {:get `check-username}]
     ["/health"
      {:get `health-check}]]]))
