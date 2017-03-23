(ns pdok.featured-to-geoserver.database
  (:require [clojure.set :as set]
            [pdok.featured-to-geoserver.util :refer :all]
            [clojure.string :as str]))

(defprotocol Transaction
  (batch-insert [this schema table columns batch] "Writes a record set to a table.")
  (batch-delete [this schema table columns batch] "Deletes records from a table.")
  (commit [this] "Commits transaction.")
  (rollback [this reason] "Performs a rollback.")
  (reducer [this] "Provides the reducer ultimately producing a feedback value."))

(defprotocol Buffering
  (append-record [this schema table record] "Appends a single record.")
  (remove-record [this schema table record] "Removes a single record.")
  (error [this error] "Raises an error.")
  (finish [this] "Flushes buffers and commits transaction."))

(defn- do-batch-insert
  "Performs a batch insert by first determining which columns are used in the batch
  and subsequently builds a value sequence for each individual record."
  [tx schema table records]
  (let [columns (->> (->> records
                         (map keys)
                         (map set))
                    (reduce set/union)
                    (sort)) ; ensure predictable column order
        batch (map #(map % columns) records)]
    [(batch-insert tx schema table columns batch)]))

(defn- do-batch-delete
  [tx schema table records]
  (->> records
    (group-by #(-> % (keys) (sort)))
    (map
      (fn [[columns records]]
        (let [values (map #(map % columns) records)]
          (batch-delete tx schema table columns values))))))

(defn- update-buffer [ks x batch-size flush]
  (fn [state]
    (let [buffer (-> (get-in state ks) (or []) (conj x))]
      (if (= (count buffer) batch-size)
        (flush (dissoc-in state ks) buffer)
        [(assoc-in state ks buffer)]))))

(defn- flush-buffer [state k f]
  (mapcat
    (fn [[schema tables]]
      (mapcat
        (fn [[table records]]
          (f schema table records))
        tables))
    (k state)))

(deftype DefaultBuffering [tx batch-size]
  Buffering
  (append-record [this schema table record]
    (update-buffer
      [:append schema table]
      record
      batch-size
      (fn [state updated-append-buffer]
        [state (do-batch-insert tx schema table updated-append-buffer)])))
  (remove-record [this schema table record]
    (update-buffer
      [:remove schema table]
      record
      batch-size
      (fn [state updated-remove-buffer]
        (let [delete-operations (do-batch-delete tx schema table updated-remove-buffer)]
          (if-let [append-buffer (get-in state [:append schema table])]
            [(dissoc-in state [:append schema table])
             (concat
               (do-batch-insert tx schema table append-buffer)
               delete-operations)]
            [state delete-operations])))))
  (error [this error]
    (fn [state]
      [{:error error} [(rollback tx error)]]))
  (finish [this]
    (fn [state]
      [{} (concat
            (flush-buffer 
              state 
              :append
              (partial do-batch-insert tx))
            (flush-buffer 
              state 
              :remove
              (partial do-batch-delete tx))
            [(commit tx)])])))

(defn process-buffer-operations
  "Processes a sequence of buffer operations and returns a sequence of the resulting transaction operations."
  ([buffer-operations] (process-buffer-operations {} buffer-operations))
  ([state buffer-operations]
    (when (not (:error state))
      (when-let [buffer-operation (first buffer-operations)]
        (let [[state database-operations] (buffer-operation state)]
          (lazy-seq (concat database-operations (process-buffer-operations state (next buffer-operations)))))))))

(defn generate-tx-summary
  ([] {})
  ([agg i]
    (if-let [error (:error i)]
      error
      (reduce
        (fn [agg [key value]]
          (update-in agg [key] #(+ value (or % 0))))
        agg
        (seq i)))))

(defn- quote-escape [s]
  (str "\"" (str/replace s #"\"" "\"\"") "\""))

(defn- execute-query [^java.sql.Connection c ^String query batch]
  (with-open [stmt (.prepareStatement c query)]
    (doseq [values batch]
      (doseq [value (map-indexed vector values)]
        (.setObject
          ^java.sql.PreparedStatement stmt
          ^Integer (-> value first inc)
          ^Object (second value)))
      (.addBatch ^java.sql.PreparedStatement stmt))
    (.executeBatch ^java.sql.PreparedStatement stmt)))

(deftype DefaultTransaction [^java.sql.Connection c]
  Transaction
  (batch-insert [this schema table columns batch]
    (fn []
      (let [query (str 
                    "insert into "
                    (-> schema (name) (quote-escape))
                    "."
                    (-> table (name) (quote-escape))                    
                    "("
                    (->> columns
                      (map name)
                      (map quote-escape)
                      (str/join ", "))
                    ") values ("
                    (->> columns
                      (map (constantly "?"))
                      (str/join ", "))
                    ")"
                    )]
        (execute-query c query batch))
      {:insert (count batch)}))
  (batch-delete [this schema table columns batch]
    (fn []
      (let [query (str 
                    "delete from "
                    (-> schema (name) (quote-escape))
                    "."
                    (-> table (name) (quote-escape))
                    " where "
                    (->> columns
                      (map name)
                      (map quote-escape)
                      (map #(str % " = ?"))
                      (str/join " and ")))]
        (execute-query c query batch)) 
      {:delete (count batch)}))
  (commit [this]
    (fn []
      (.commit c)
      (.close c)
      {}))
  (rollback [this reason]
    (fn []
      (.rollback c)
      (.close c)
      {:error reason}))
  (reducer [this] generate-tx-summary))

(defn- pg-connect [db]
  (do
    (java.lang.Class/forName "org.postgresql.Driver")
    (doto
      (java.sql.DriverManager/getConnection
        ^String (str "jdbc:postgresql://" (:host db) ":" (or (:port db) 5432) "/" (:dbname db))
        ^String (:user db)
        ^String (:password db))
      (.setAutoCommit false))))

(defn- result-seq [^java.sql.ResultSet rs & keys]
  (when (.next rs)
    (lazy-seq 
      (cons 
        (->> keys
          (map
            (fn [idx key]
              (let [value (.getObject rs ^Integer idx)]
                {key (if 
                       (instance? java.sql.Array value)
                       (seq (.getArray ^java.sql.Array value)) 
                       value)}))
            (map inc (range)))
          (reduce merge))
        (apply
          (partial result-seq rs)
          keys)))))

(defn fetch-related-tables [^java.sql.Connection c]
  (let [^String query (str
                        "select "
                        "main_tables.table_schema::text, "
                        "main_tables.table_name::text, "
                        "array_agg(related_tables.table_name::text) "
                        "from information_schema.tables main_tables, information_schema.tables related_tables "
                        "where main_tables.table_schema = related_tables.table_schema "
                        "and related_tables.table_name like main_tables.table_name || '$%' "
                        "group by 1, 2")]
    (with-open [^java.sql.Statement stmt (.createStatement c)
                ^java.sql.ResultSet rs (.executeQuery stmt query)]
      (reduce
        #(assoc-in 
           %1 
           [(-> %2 :schema keyword) (-> %2 :table keyword)] 
           (->> %2 :related-tables (map keyword)))
        {}
        (result-seq rs :schema :table :related-tables)))))

(defn connect [db]
  (condp = (:dbtype db)
    "postgresql" (pg-connect db)
    (throw (java.sql.SQLException. (str "Unsupported database type: " (:dbtype db)))))) 
