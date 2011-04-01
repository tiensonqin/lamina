;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:author "Zachary Tellman"}
  lamina.connections
  (:use
    [lamina core]
    [lamina.core.channel :only (dequeue)]
    [lamina.core.pipeline :only (success-result error-result)])
  (:require
    [clojure.contrib.logging :as log])
  (:import
    [java.util.concurrent TimeoutException]
    [lamina.core.pipeline ResultChannel]))

;;

(defn- incr-delay [delay]
  (if (zero? delay)
    500
    (min 64000 (* 2 delay))))

(defn- wait-for-close [ch description]
  (let [ch (fork ch)]
    (if (drained? ch)
      (constant-channel nil)
      (let [signal (constant-channel)]
	(receive-all ch
	  (fn [msg]
	    (when (drained? ch)
	      (log/warn (str "Connection to " description " lost."))
	      (enqueue signal nil))))
	(read-channel signal)))))

(defn- connect-loop [halt-signal connection-generator connection-callback description]
  (let [delay (atom 0)
	result (atom (error-result [nil nil]))
	latch (atom true)]
    (receive halt-signal
      (fn [_]
	(run-pipeline @result
	  #(do
	     (close %)
	     (reset! result ::close)))
	(reset! latch false)))
    (run-pipeline nil
      :error-handler (fn [ex]
		       (swap! delay incr-delay)
		       (restart))
      (do*
      	(when (pos? @delay)
      	  (log/warn
      	    (str "Failed to connect to " description ". Waiting " @delay "ms to try again."))))
      (wait @delay)
      (fn [_] (connection-generator))
      (fn [ch]
	(when connection-callback
	  (connection-callback ch))
	(log/info (str "Connected to " description "."))
	(reset! delay 0)
	(reset! result (success-result ch))
	(wait-for-close ch description))
      (fn [_]
	(when @latch
	  (restart))))
    result))

(defn persistent-connection
  ([connection-generator]
     (persistent-connection connection-generator "unknown"))
  ([connection-generator description]
     (persistent-connection connection-generator description nil))
  ([connection-generator description connection-callback]
     (let [close-signal (constant-channel)
	   result (connect-loop close-signal connection-generator connection-callback description)]
       (fn
	 ([]
	    @result)
	 ([signal]
	    (when (= ::close signal)
	      (enqueue close-signal nil)))))))

(defn close-connection
  "Takes a client function, and closes the connection."
  [f]
  (f ::close))

;;

(defn- has-completed? [result-ch]
  (wait-for-message
   (poll {:success (.success result-ch)
          :error (.error result-ch)}
         0)))

(defn client
  ([connection-generator]
     (client connection-generator "unknown"))
  ([connection-generator description]
     (let [connection (persistent-connection connection-generator description)
	   requests (channel)]
       ;; request loop
       (receive-in-order requests
	 (fn [[request ^ResultChannel result-channel timeout]]

	   (if (= ::close request)
	     (close-connection connection)
	     (do
	       ;; set up timeout
	       (when-not (neg? timeout)
		 (run-pipeline nil
		   (wait timeout)
		   (fn [_]
		     (enqueue (.error result-channel) (TimeoutException.)))))

	       ;; make request
	       (siphon-result
                (run-pipeline nil
                  :error-handler (fn [_]
                                   (when-not (has-completed? result-channel)
                                     (restart)))
                  (fn [_]
                    (if (has-completed? result-channel)

                      ;; if timeout has already elapsed, exit
                      (complete nil)

                      ;; send the request
                      (run-pipeline (connection)
                        (fn [ch]
                          (if (= ::close ch)

                            ;; (close-connection ...) has already been called
                            (complete (Exception. "Client has been deactivated."))

                            ;; send request, and wait for response
                            (do
                              (enqueue ch request)
                              [ch (read-channel ch)]))))))
                  (fn [[ch response]]
                    (if-not (and (nil? response) (drained? ch))
                      (if (instance? Exception response)
                        (throw response)
                        response)
                      (restart))))
                result-channel)))))

       ;; request function
       (fn this
	 ([request]
	    (this request -1))
	 ([request timeout]
	    (let [result (result-channel)]
	      (enqueue requests [request result timeout])
	      result))))))

(defn pipelined-client
  ([connection-generator]
     (pipelined-client connection-generator "unknown"))
  ([connection-generator description]
     (let [connection (persistent-connection connection-generator description)
	   requests (channel)
	   responses (channel)]

       ;; handle requests
       (receive-in-order requests
	 (fn [[request ^ResultChannel result timeout]]
	   (if (= ::close request)
	     (close-connection connection)
	     (do
	       ;; setup timeout
	       (when-not (neg? timeout)
		 (run-pipeline nil
		   (wait timeout)
                   (fn [_]
                     (enqueue (.error result) (TimeoutException.)))))

	       ;; send requests
	       (run-pipeline 0
                 :error-handler (fn [_]
                                  (if-not (has-completed? result)
                                    ;;try again after 100 ms
                                    (restart 100)
                                    (complete nil)))
                 (fn [retry-wait-time]
                   (if (zero? retry-wait-time)
                     (connection)
                     (run-pipeline nil
                       (wait retry-wait-time)
                       (fn [_] (connection)))))
		 (fn [ch]
                   (when-not (has-completed? result)
                     (enqueue ch request)
                     (enqueue responses [request result ch]))))))))

       ;; handle responses
       (receive-in-order responses
	 (fn [[request ^ResultChannel result ch]]
	   (run-pipeline ch
	     :error-handler (fn [_]
			      ;; re-send request
			      (when-not (has-completed? result)
				(enqueue requests [request result -1]))
			      (complete nil))
	     read-channel
	     (fn [response]
	       (if (and (nil? response) (drained? ch))
		 (throw (Exception. "Connection closed"))
		 (if (instance? Exception response)
		   (enqueue (.error result) response)
		   (enqueue (.success result) response)))))))

       ;; request function
       (fn this
	 ([request]
	    (this request -1))
	 ([request timeout]
	    (let [result (result-channel)]
	      (enqueue requests [request result timeout])
	      result))))))

;;

(defn server
  [ch handler]
  (run-pipeline ch
    read-channel
    #(let [c (constant-channel)]
       (handler c %)
       (read-channel c))
    #(enqueue ch %)
    (fn [_]
      (when-not (drained? ch)
	(restart))))
  (fn []
    (close ch)))

(defn pipelined-server
  [ch handler]
  (let [requests (channel)
	responses (channel)]
    (run-pipeline responses
      read-channel
      #(read-channel %)
      #(enqueue ch %)
      (fn [_] (restart)))
    (run-pipeline ch
      read-channel
      #(let [c (constant-channel)]
	 (handler c %)
	 (enqueue responses c))
      (fn [_]
	(when-not (drained? ch)
	  (restart)))))
  (fn []
    (close ch)))
