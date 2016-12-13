(ns metabase.api.collection
  "/api/collection endpoints."
  (:require [compojure.core :refer [GET POST DELETE PUT]]
            [schema.core :as s]
            [metabase.api.common :as api]
            [metabase.db :as db]
            (metabase.models [card :refer [Card]]
                             [collection :refer [Collection], :as collection]
                             [interface :as models])
            [metabase.util.schema :as su]))


(api/defendpoint GET "/"
  "Fetch a list of all (non-archived) Collections that the current user has read permissions for."
  []
  (filterv models/can-read? (db/select Collection :archived false {:order-by [[:%lower.name :asc]]})))

(api/defendpoint GET "/:id"
  "Fetch a specific (non-archived) Collection, including cards that belong to it."
  [id]
  ;; TODO - hydrate the `:cards` that belong to this Collection
  (assoc (api/read-check Collection id, :archived false)
    :cards (db/select Card, :collection_id id, :archived false)))

(api/defendpoint POST "/"
  "Create a new Collection."
  [:as {{:keys [name color description]} :body}]
  {name su/NonBlankString, color collection/hex-color-regex, description (s/maybe su/NonBlankString)}
  (api/check-superuser)
  (db/insert! Collection
    :name  name
    :color color))

(api/defendpoint PUT "/:id"
  "Modify an existing Collection, including archiving or unarchiving it."
  [id, :as {{:keys [name color description archived]} :body}]
  {name su/NonBlankString, color collection/hex-color-regex, description (s/maybe su/NonBlankString), archived (s/maybe s/Bool)}
  ;; you have to be a superuser to modify a Collection itself, but `/collection/:id/` perms are sufficient for adding/removing Cards
  (api/check-superuser)
  (api/check-exists? Collection id)
  (db/update! Collection id
    :name        name
    :color       color
    :description description
    :archived    (if (nil? archived)
                   false
                   archived))
  ;; return the updated object
  (Collection id))


;;; ------------------------------------------------------------ GRAPH ENDPOINTS ------------------------------------------------------------

(api/defendpoint GET "/graph"
  "Fetch a graph of all Collection Permissions."
  []
  (api/check-superuser)
  (collection/graph))


(defn- ->int [id] (Integer/parseInt (name id)))

(defn- dejsonify-collections [collections]
  (into {} (for [[collection-id perms] collections]
             {(->int collection-id) (keyword perms)})))

(defn- dejsonify-groups [groups]
  (into {} (for [[group-id collections] groups]
             {(->int group-id) (dejsonify-collections collections)})))

(defn- dejsonify-graph
  "Fix the types in the graph when it comes in from the API, e.g. converting things like `\"none\"` to `:none` and parsing object keys as integers."
  [graph]
  (update graph :groups dejsonify-groups))

(api/defendpoint PUT "/graph"
  "Do a batch update of Collections Permissions by passing in a modified graph."
  [:as {body :body}]
  {body su/Map}
  (api/check-superuser)
  (collection/update-graph! (dejsonify-graph body))
  (collection/graph))


(api/define-routes)