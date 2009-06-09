(ns uk.co.fatvat.mlperceptron
  (:use clojure.contrib.test-is)
  (:use [uk.co.fatvat.utils]))

(def activation-function (fn [x] (Math/tanh x)))
(def activation-function-derivation (fn [y] (- 1.0 (* y y))))

(def num-hidden 2)
(def learning-rate 0.5)
(def momentum 0.1)

(defstruct bp-nn :weight-input :weight-output :change-input :change-output)

(defn make-matrix
  [width height]
  "Create a matrix (list of lists)"
  (repeat width (repeat height 0)))

(defn matrix-map
  [m func]
  "Apply a function to every element in a matrix"
  (map (fn [x] (map func x)) m))

(defn rand-range 
  [l h]
  "Return a real number within the given range"
  (+ (rand (- h l)) l))

(defn create-network
  ([input hidden output]
     (create-network input hidden output true))
  ([input hidden output use-random-weights]
  "Create a network with the given number of input, hidden and output nodes"
  (let [i (inc input)
	w-func (if use-random-weights (fn [_] (rand-range -0.2 0.2)) (fn [_] 0.2))
	o-func (if use-random-weights (fn [_] (rand-range -2.0 2.0)) (fn [_] 2.0))]
    (struct bp-nn
	    (matrix-map (make-matrix i hidden) w-func)
	    (matrix-map (make-matrix hidden output) o-func)
	    (make-matrix i hidden)
	    (make-matrix hidden output)))))

(defn calculate-hidden-deltas
  [wo ah od]
  "Calculate the error terms for the hidden"
  (let [errors (map (partial reduce +) (map (fn [x] (map * x od)) wo))] ;; Sick.
    (map (fn [h e] (* e (activation-function-derivation h))) ah errors)))
    
(defn update-weights
  [w deltas co ah]
  (let [x (map 
	   (fn [wcol ccol h] 
	     (map (fn [wrow crow od] 
		    (let [change (* od h)]
		      [(+ wrow (* learning-rate change) (* momentum crow)) change]))
		  wcol ccol deltas))
	   w co ah)]
    [(matrix-map x first) (matrix-map x second)]))

(defn apply-activation-function
  [w i]
  "Calculate the hidden activations"
  (apply map (comp activation-function +) (map (fn [col p] (map (partial * p) col)) w i)))

(defn run-network
  [pattern network]
  "Run the network with the given pattern and return the output and the hidden values"
  (assert (= (count pattern) (dec (count (network :weight-input)))))
  (let [p (cons 1 pattern)] ;; ensure bias term added
    (let [wi (network :weight-input)
	  wo (network :weight-output)
	  ah (apply-activation-function wi p)
	  ao (apply-activation-function wo ah)]
      [ao ah])))

(defn back-propagate
  [target p results network]
  "Back propagate the results to adjust the rates"
  (assert (= (count target) (count (first (get network :weight-output)))))
  (let [pattern (cons 1 p) ;; ensure bias term added
	ao (first results)
	ah (second results)
	error (map - target ao)
	wi (network :weight-input)
	wo (network :weight-output)
	ci (network :change-input)
	co (network :change-output)
	output-deltas (map (fn [o e] (* e (activation-function-derivation o))) ao error)
	hidden-deltas (calculate-hidden-deltas wo ah output-deltas)
	updated-output-weights (update-weights wo output-deltas co ah)
	updated-input-weights (update-weights wi hidden-deltas ci pattern)]
    (struct bp-nn
	    (first  updated-input-weights)
	    (first  updated-output-weights)
	    (second updated-input-weights)
	    (second updated-output-weights))
  ))

(defn run-patterns
  [network samples expecteds]
  (reduce 
   (fn [n expectations] 
     (let [[sample expected] expectations
	   [ah ao] (run-network sample n)]
       (back-propagate expected sample [ah ao] n)))   
   network ;; initial value
   (map list samples expecteds)))  

(defn train-network
  ([samples expected]
     (train-network (create-network (count (first samples)) 
				    num-hidden (count (first expected))) 
		    samples expected))
  ([network samples expected]
     (iterate (fn [n] (run-patterns n samples expected)) network)))

(defn example[]
  (let [x (nth (apply train-network xor-test-data) 100)]
    (println (first (run-network [0 0] x)) "-->" 0)
    (println (first (run-network [0 1] x)) "-->" 1)
    (println (first (run-network [1 0] x)) "-->" 1)
    (println (first (run-network [1 1] x)) "-->" 0)))

(deftest test-npp
  
  ; Basics of creating a network
  (is (= 3 (count ((create-network 2 2 1) :weight-input))))
  (is (= 2 (count (first ((create-network 2 2 1) :weight-input)))))
  
  ; Basics of running a neural network
  (is (= '(0.5370495669980354 0.5370495669980354) (apply-activation-function [[0.2 0.2] [0.2 0.2] [0.2 0.2]] [1 1 1]))) ;; TODO tolerant equals
  (is (= '(0.8617231593133063) (apply-activation-function [[2.0][2.0]] [0.5 0.15])))
  
  ; Back propagation algorithm
  (let [ao [0.5]
	target [1.0]
	error (map - target ao)
	wo [[2.0][2.0]]
	ah [0.5 0.75]
	od [0.375]
	wi [[0.2 0.2][0.2 0.2][0.2 0.2]]
	pattern [1.0 1.0 1.0]
	ci [[0.8 0.8][0.8 0.8][0.8 0.8]]
	co [[0.6][0.6]]
	hidden-deltas [0.5625 0.328125]]
	    
    (is (= [0.375] (map (fn [o e] (* e (activation-function-derivation o))) ao error))) ;; output-deltas
    (is (= [0.5625, 0.328125] (calculate-hidden-deltas wo ah od))) ;;hidden deltas
    (is (= '((0.56125 0.4440625) (0.56125 0.4440625) (0.56125 0.4440625)) ;; update input
	   (first (update-weights wi hidden-deltas ci pattern))))
    (is (= [[0.5625 0.328125] [0.5625 0.328125] [0.5625 0.328125]] ;; update input
	   (second (update-weights wi hidden-deltas ci pattern))))
    (is (= [[2.15375][2.200625]]
	   (first (update-weights wo od co ah))))
    (is (= [[0.1875][0.28125]]
	   (second (update-weights wo od co ah))))))
			     