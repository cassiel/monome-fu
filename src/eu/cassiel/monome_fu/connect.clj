(ns eu.cassiel.monome-fu.connect
  (:require (eu.cassiel.monome-fu [network :as net]
                                  [tools :as t]))
  (:import (net.loadbang.osc.data Message)))

(defn list-devices
  "Open a channel to list devices, calling `callback` with argument map of
   `:id`, `:name`, `:host`, `:port`."
  [& {:keys [host me port callback]}]
  (letfn [(dispatch
            [_ address [id name port]]
            (when (= address "/serialosc/device")
              (callback :id id
                        :name name
                        :host host
                        :port port)))]
    (let [transmitter (net/start-transmitter host port)
          receiver (net/start-receiver dispatch)
          message (-> (Message. "/serialosc/list")
                      (.addString me)
                      (.addInteger (.getPort receiver)))]
      (.transmit transmitter message)
      (t/do-after 1000 (fn []
                         (.close transmitter)
                         (.close receiver))))))

(defn list-properties
  "Open a channel to list properties, calling `callback` with argument map of
   `:key`, `:value`. `:key` is keyword-converted. `:value` can be a vector
   (e.g. for `/sys/size`). I don't think `/sys/host` is very useful: as far as
   I know, it'll be the same as the host we're interrogating - or, at worst,
   incorrectly hardwired to `localhost`."
  [& {:keys [host me port callback]}]
  (letfn [(dispatch
            [_ address args]
            (callback :key address
                      :value args))]
    (let [transmitter (net/start-transmitter host port)
          receiver (net/start-receiver dispatch)
          message (-> (Message. "/sys/info")
                      (.addString me)
                      (.addInteger (.getPort receiver)))]
      (.transmit transmitter message)
      (t/do-after 1000 (fn []
                         (.close transmitter)
                         (.close receiver))))))

(defprotocol CONNECTION-CLIENT
  "A client matching this protocol must be provided for each device."
  (get-initial-state [this]
    "Pass in a value for a state variable.")
  (handle-grid-key [this state x y how]
    "Handle a button press.")
  (handle-enc-key [this state enc how]
    "Handle encoder press. Return new state.")
  (handle-enc-delta [this state enc delta]
    "Handle encoder turn.")
  (shutdown [state this] "Shut down any other connections or activity."))

(defprotocol CONNECTION-INFO
  "Information and callbacks passed to the handler."
  (get-transmitter [this] "The transmitter")
  (get-prefix [this] "Get the prefix (without waiting for driver interrogation).")
  (get-keys [this] "A set of keys: /sys properties.")
  (get-key [this k] "Retrieve a key.")
  (swap-state [this f] "Apply `f` to the current state."))

(defprotocol CONNECTION-SET
  "All the current connections."
  (get-state [this] "Get the current state of devices.")
  (shutdown-all [this] "Shut down all handlers and channels."))

(defn connect-all
  "Look for and connect up devices. For each device found, call back to
   deliver a handler. The handlers are held in a map from device ID (and/or name)
   to a function which takes [transmitter properties] - where return
   host and post have already been reassigned - and delivers
   a `CONNECTION-CLIENT`.

   TODO: how to establish the properties? We don't know when they've all
   arrived. (Answer: wrap it into a function.)"
  [& {:keys [host me handlers]}]
  (let
      [*state* (atom {})
       *shutdown-fns* (atom [])
       PREFIX "/-"]
    (list-devices
     :host host
     :me me
     :port 12002
     :callback
     (fn [& {:keys [id name port]}]
       (when-let [hfn (or (get handlers id)
                          (get handlers name))]
         (let [tx (net/start-transmitter host port)
               *hstate* (atom nil)
               info (reify CONNECTION-INFO
                      (get-prefix [this] PREFIX)
                      (get-transmitter [this] tx)
                      (get-keys [this] (set (keys (get @*state* id))))
                      (get-key [this k] (get-in @*state* [id k]))
                      ;; Possible race condition when establishing state, so:
                      (swap-state [this f]
                        (swap! *hstate* #(when % (f %)))))

               handler (hfn info)
               _ (reset! *hstate* (get-initial-state handler))
               dispatch (fn
                          [_ address args]

                          (case (t/strip-prefix address)
                            "/grid/key"
                            (let [[x y how] args]
                              (swap! *hstate*
                                     #(handle-grid-key handler % x y how)))

                            "/enc/key"
                            (let [[enc how] args]
                              (swap! *hstate*
                                     #(handle-enc-key handler % enc how)))

                            "/enc/delta"
                            (let [[enc delta] args]
                              (swap! *hstate*
                                     #(handle-enc-delta handler % enc delta)))

                            (println "other message" address args)))

               rx (net/start-receiver dispatch)
               host-message (-> (Message. "/sys/host")
                                (.addString me))
               port-message (-> (Message. "/sys/port")
                                (.addInteger (.getPort rx)))
               prefix-message (-> (Message. "/sys/prefix")
                                  (.addString PREFIX))]

           (doseq
               [x [host-message port-message prefix-message]]
             (.transmit tx x))

           (swap! *state* assoc-in [id :handler-state] (fn [] @*hstate*))

           (swap! *shutdown-fns* conj (fn []
                                        (shutdown handler @*hstate*)
                                        (.close tx)
                                        (.close rx)))))

       (list-properties
        :host host
        :me me
        :port port
        :callback (fn [& {:keys [key value]}]
                    (swap! *state* assoc-in [id :driver key] value)))))

    (reify CONNECTION-SET
         (get-state [this] @*state*)
         (shutdown-all [this]
           (doseq [f @*shutdown-fns*] (f))))))
