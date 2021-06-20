(ns nanopass-test
  (:require  [clojure.test :refer :all]
             [matches.nanopass :refer [scope tag type-rules]]
             [matches.match.predicator :refer [with-predicates on-each]]
             [matches.nanopass.dialect :refer [def-dialect def-derived => ==>]]
             [matches.nanopass.pass :refer [defpass]]
             [matches.r3.combinators :refer [rule-simplifier directed rule-list]]
             [matches.r3.core :refer [rule success]]
             [matches.r3.rewrite :refer [sub]]
             [matches.match.core :refer [compile-pattern* matcher]]
             [uncomplicate.fluokitten.core :as f]))

(def-dialect Lssa
  (terminals [l symbol]
             [x symbol]
             [i int]
             [tr symbol])
  (Triv [triv]
        ?x
        ?i
        ?l)
  (Program [prog]
           (letrec ((?:* [?l ?f])) (?:+ ?c)))
  (Body [b]
        (locals (??x) ?blocks))
  (Blocks [blocks]
          (labels ((?:* [?l ?t])) ?l))
  (Effect [ef]
          (set! ?x ?rhs)
          (mset! ?tr0 ?tr1 ?tr2)
          (call (?:+ tr)))
  (Tail [t]
        (if (relop ?tr0 ?tr1) (?l0) (?l1))
        (goto ?l)
        (return ?tr)
        (begin ??ef ?t))
  (Lambda [f]
          (lambda (??x) ?b)))

(def-derived Lflat-funcs Lssa
  (Program [prog]
           - (letrec ((?:* [?l ?f])) ?b)
           + (letrec ((?:* [?l ?f])) (?:+ ?c)))
  (Body [b]
        - (locals (??x) ?blocks))
  (Blocks [blocks]
          - (labels ((?:* [?l ?t])) ?l))
  - Effect
  - Tail
  (Lambda [f]
          - (lambda (??x) ?b)
          + (lambda (??x) (?:+ c)))
  (Code [c]
        (label ?l)
        (set! ?x ?rhs)
        (mset! ?tr0 ?tr1 ?tr2)
        (call (?:+ tr))
        (goto ?l)
        (return ?tr)
        (if (relop ?tr0 ?tr1) (?l0) (?l1))))

#_
(defpass eliminate-simple-moves (=> Lflat-funcs Lflat-funcs) [x]
  (let [var-slot-set! (fn [a b])
        identify-move
        ;; using ?:as to grab the whole form works only if there is no recursion, since it
        ;; will not get the updated form. Safe here but a better solution is required.
        (rule '(?:as ir (set! ?x1 (| ?x2 ?int2)))
              (do (var-slot-set! x1 (or x2 int2)) nil))
        nil-if-move (rule '(set! ?x1 (| ?x2 ?int2))
                          (success nil))
        var (fn var [x]
              (loop [target-x x]
                (let [next-target-x (:slot target-x)]
                  (cond
                    (not next-target-x)
                    (do (when-not (= target-x x)
                          (var-slot-set! x target-x))
                        target-x)
                    (int? next-target-x)
                    (do
                      (var-slot-set! x next-target-x)
                      next-target-x)
                    :else (recur next-target-x)))))]
    [(=> Program Program)
     (directed
      (rule-list [(rule '(letrec ((?:* [?l* ?->f*])) (?:+ ?c))
                        (sub
                         (letrec ((?:+ [?l* ?f*]))
                                 ~@(vec (keep nil-if-move
                                              (map identify-move c))))))]))

     (=> Lambda Lambda)
     (rule '(lambda (??x) ??c)
           (sub
            (lambda (??x)
                    ~@(vec (keep nil-if-move
                                 (map identify-move c))))))

     (=> Triv Triv)
     (rule '?x '(var ?x))]))


(def make-explicit
  (letfn [(f [x] false)
          (expr? [x] (= :expr (:tag (meta x))))]
    (with-predicates {'x (on-each symbol?)
                      'pr f 'c f 'd f}
      (directed
       ;; There are several rules that seem only exist in order to descend
       ;; into marked subexpressions. I'm not 100% clear on the behaviour of
       ;; the auto-generated nanopass clauses.
       (rule-list [(rule '?x x)
                   (rule '?pr pr)
                   (rule '?c `'~c)
                   (rule '?d `'~d)
                   (rule '(begin ??->e*) (sub (begin ??e*)))
                   (rule '(if ?->e0 ?->e1) (sub (if ?e0 ?e1 (void))))
                   (rule '(if ?->e0 ?->e1 ?->e2) (sub (if ?e0 ?e1 ?e2)))

                   (rule '(lambda (??x*) ??->body*)
                         (sub (lambda (??x*) (begin ??body*))))

                   (rule '(let ((?:* [?x* ?->b*])) ??->body*)
                         (sub (let ((?:* [?x* ?b*])) (begin ??body*))))

                   (rule '(letrec ((?:* [?x* ?->e*])) ??->body*)
                         (sub (letrec ((?:* [?x* ?e*])) (begin ??body*))))
                   (rule '((?:+ ?->e)) (sub (??e)))])))))

#_
(group-by #(apply = %)
        (map (juxt :orig-pattern :pattern)
             (map :rule
                  (get-in (meta (:combinator (meta make-explicit)))
                          [:rule :rules]))))

(deftest make-explicit-example
  (is (= '((let ([a (lambda (a b)
                            (begin (letrec ([a 0]) (begin 0))
                                   (letrec ([a 1]) (begin 1))))]
                 [x (if (if a b (void)) b c)])
             (begin 1)))
         (make-explicit
          '((let ([a (lambda (a b)
                             (letrec ([a 0]) 0)
                             (letrec ([a 1]) 1))]
                  [x (if (if a b) b c)])
              1))))))
