;; jeff.foster@acm.org
(ns uk.co.fatvat.icfp
  (:use clojure.contrib.trace); :only (trace)])
  (:import [java.lang.reflect Array])
  (:import [java.io FileInputStream File]))

;;; Location of relevant files
(def bin1 "/home/jfoster/clojureprojects/icfp/uk/co/fatvat/bin1.obf")

;;; Virtual machine specification
(defstruct virtualmachine :mem :counter :inport :outport :status :firstrun)

(defn get-val
  [vm x]
  @((:mem vm) x))

(defn numeric-op
  "D-type General numeric op"
  [vm [x y] f]
  (let [m (:mem vm)]
    (swap! (m @(:counter vm)) (constantly (f @(m x) @(m y))))))

(defn phi
  "D-type"
  [vm [x y]]
  (let [m (:mem vm)]
    (trace 'Phi (format "%s ? %s : %s --> %s" @(:status vm) @(m x) @(m y) (if @(:status vm) @(m x) @(m y))))
    (swap! (m @(:counter vm))
	   (constantly
	     (if @(:status vm)
	       @(m x)
	       @(m y))))))

(defn print-args
  [vm op x y]
  (format "%s %s // %s %s %s" x y (get-val vm x) op (get-val vm y)))
    
(defn add
  "D-type Add instruction"
  [vm [x y]]
  (trace 'Add (print-args vm '+ x y))
  (numeric-op vm [x y] +))

(defn sub
  "D-type Sub instruction"
  [vm [x y]]
  (trace 'Sub (print-args vm '- x y))
  (numeric-op vm [x y] -))

(defn mult
  "D-type Multiply instruction"
  [vm [x y]]
  (trace  'Mult (print-args vm '* x y))
  (numeric-op vm [x y] *))

(defn div
  "D-type Divide"
  [vm args]
  (trace 'Div)
  (numeric-op vm args (fn [x y] (if (zero? y) 0 (/ x y)))))

(defn noop
  "S-type Noop instruction"
  [vm args]
  (trace 'Noop)
  vm)

(defn copy
  "S-Type: Copy instruction"
  [vm [x]]
  (trace 'Copy (format "%s // %s" x (get-val vm x)))
  (swap! ((:mem vm) @(:counter vm)) (constantly (get-val vm x))))

(defn sqrt
  "S-Type: Square root instruction: undefined for negative values"
  [vm [x]]
  (trace 'Sqrt)
  (assert (not (neg? (get-val vm x))))
  (swap! ((:mem vm) @(:counter vm)) (constantly (Math/sqrt (get-val vm x)))))

(defn input
  "S-Type: Set the memory from the inport"
  [vm [x]]
  (trace 'Input)
  (swap! ((:mem vm) @(:counter vm)) (constantly @((:inport vm) x))))

(defn output
  "Output instruction: Set the memory on the outport"
  [vm [x y]]
  (trace 'Output (format "%s %s // %s" x y (get-val vm y)))
  (swap! ((:outport vm) x) (constantly (get-val vm y))))

(defn cmpz
  "Comparison function"
  [vm [cmp y]]
  (let [val @((:mem vm) y)
	status (cond ;; TODO replace this with functions so it becomes (apply cmp val)
		 (= cmp 'LTZ) (< val 0)
		 (= cmp 'LEZ) (<= val 0)
		 (= cmp 'EQZ) (zero? val)
		 (= cmp 'GEZ) (> val 0)
		 (= cmp 'GTZ) (>= val 0)
		 :else (assert false))]
    (trace 'Cmpz (format "%s %s --> %s" cmp y status))
    (swap! (:status vm) (constantly status))))

(def d-type-instructions {1 add, 2 sub, 3 mult, 4 div, 5 output, 6 phi})
(def s-type-instructions {0 noop, 1 cmpz, 2 sqrt, 3 copy, 4 input})
(def comparison {0 'LTZ, 1 'LEZ, 2 'EQZ, 3 'GEZ, 4 'GTZ})

;;; Reading in the file from disk and bit manipulation fu
(defn get-bytes
  "Read the bytes for the given file, stored as a sequence of bytes"
  [filename]
  (let [file (File. filename)]
    (assert (.exists file))
    (with-open [x (FileInputStream. file)]
      (doall
       (into [] (take-while (partial not= -1) (repeatedly #(.read x))))))))

(defn to-double
  "Convert the given series of 8 bytes into an IEEE 754 number"
  [bytes]
  (Double/longBitsToDouble 
   (long
    (reduce bit-or 
	    (map (fn [x shift] (bit-shift-left (bit-and (int x) 0xFF) shift))
		 bytes 
		 (range 0 64 8))))))

(defn to-int
  "Convert the given series of bytes into an integer"
  [bytes]
  (int (reduce bit-or
	       (map (fn [x shift] (bit-shift-left (bit-and (int x) 0xFF) shift))
		    bytes
		    (range 0 32 8)))))

(defn- d-args
  "Convert a D-type instruction into a vector of op-code + args"
  [ins]
  (let [x (to-int ins)]
    [(bit-shift-right (bit-and x 0xFFFC000) 14) (bit-and x 0x00003FFF)]))

(defn- s-args
  "Convert an S-type instruction into a vector of op-code and args"
  [op ins]
  (if (= cmpz op)
    [(comparison (bit-shift-right (bit-and (to-int ins) 0x700000) 21)) (bit-and (to-int ins) 0x00003FFF)]
    [(bit-and (to-int ins) 0x00003FFF) (bit-shift-right (bit-and (last (butlast ins)) 0xF0) 4)]))

(defn get-op
  "Decode the 4 bytes as an op code, complete with arguments"
  [ins]
  (let [d-opcode (bit-shift-right (bit-and 0xF0 (last ins)) 4)
	s-opcode (bit-and 0x0F (last ins))]
    (if (zero? d-opcode)
      (let [sins (s-type-instructions s-opcode)]
	[sins (s-args sins ins)])
      [(d-type-instructions d-opcode) (d-args ins)])))

(defn get-instruction-data
  "Decode the data at the given address (in 96 bit multiplies)"
  [image address]
  (if (even? (/ address 12))
    [(get-op (subvec image (+ address 8) (+ address 12)))
     (to-double (subvec image address (+ address 8)))]
    [(get-op (subvec image address (+ address 4)))
     (to-double (subvec image (+ address 4) (+ address 12)))]))
	  
(defn read-data
  "Read in the data from the image and return a series of decoded instructions"
  [image]
  (map (fn [x] (get-instruction-data image x)) (range 0 (count image) 12)))

;;; Virtual machine executing instructions
(defn vector-atoms
  "Create a vector of atoms, initialized to zero"
  [n]
  (into [] (take n (repeatedly #(atom 0)))))

(defn init-vm
  [data]
  (let [memory (vector-atoms (count data))]
    (dosync
     (doall (map (fn [a d] (swap! a (constantly d))) memory data)))
    (struct virtualmachine memory (atom 0) (vector-atoms 16384) (vector-atoms 16384) (atom false) (atom true))))

(defn hohmann-score
  [vm]
  (let [x (:outport vm)
	pc @(:counter vm)
	score @(x 0)
	fuel-remaining @(x 1)
	sx-relative @(x 2)
	sy-relative @(x 3)
	target-radius @(x 4)]
    [pc score fuel-remaining sx-relative sy-relative target-radius]))
    
(defn hohmann-updater 
  [vm]
  (when @(:firstrun vm)
    (swap! ((:inport vm) 0x3E80) (constantly 1001))))

(defn create-vm
  [instructions]
  (init-vm (map second instructions)))

(def bin1 (read-data (get-bytes bin1)))

;; TODO This could be purely functional, if I just returned
;; a copy of the entire VM after each operation was applied.
(defn run-machine
  "Run the virtual machine with the decoded instructions.  
   Reset the program counter when complete"
  [vm ops update-input]
  (update-input vm)
  (doseq [[op args] ops]     
    (apply op (list vm args)) ;; dodgy side effect
    (swap! (:counter vm) inc))
  (swap! (:counter vm) (constantly 0))
  (swap! (:firstrun vm) (constantly false))
  vm)

(defn run []
  (let [x (create-vm bin1)
	ops (map first bin1)]
    (time (count (take 1000 (repeatedly #(run-machine x ops hohmann-updater)))))))
