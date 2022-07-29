# Pattern

Pattern lets you transform data structures in amazing ways.

The focus is on simplicity and expressivity.
It works on immutable persistent data, greatly simplifying development and debugging of transformations.

``` clojure
pangloss/pattern {:git/url "https://github.com/pangloss/pattern"
                  :sha "<use the current commit hash>"}
```

## How can this be used?

Here are a few examples of how it's been used already:

* Create an infix math macro in a few lines of code in about 30 LOC
* Define the simplification rules of a full computer algebra system (see a very similar engine in use in SICMUtils)
* Create a Python to Clojure source-to-source converter in under 400 LOC
* Compile Scheme to X86 assembly in about 1500 LOC

## How does it work?

Pattern is an collection of tools for pattern matching and substitution.
Those tools can be extended and combined in flexible ways.

The match pattern looks similar to the data structure it's matching on.
That enables a very intuitive understanding of how the pattern will apply to the data being matched.

A transformation is a match pattern with a substitution pattern.
Combined, that is called a rule.

The substitution pattern uses exactly the same syntax as the match pattern.
Substitution supports all of core functionality of the matcher.
That makes it much simpler to understand the behavior of a rule.

In the spirit of Clojure, pattern maintains a high degree of simplicity in the interface.

## Pattern Matching

``` clojure
(require '[pattern :refer [compile-pattern]])
```

The core of Pattern is the pattern matcher, which can be used to match any kind of Clojure data structure.
To use the pattern matcher, call `(compile-pattern my-pattern)`. 
That returns a function that you can call with the data you want to extract matches from. 
The return value is a map from match names to matching data.

Here is a simple example that matches the first and last elements of a vector if the central elements are `1 2 3`.

``` clojure
(def m (compile-pattern '[?before 1 2 3 ?after])

(m [0 1 2 3 4]) ; => {:before 0 :after 4}
```

Even a simple pattern encodes sophisticated matching logic.
For instance you could replicate the above matcher with this code. 
As you can see, it's more complex, less clear in intention, and easier to make a mistake:

``` clojure
(def m #(when (vector? %)
          (if (and (= 5 (count %))
                (= [1 2 3] (subvec % 1 4)))
            {:before (first %)
             :after (last %)})))

(m [0 1 2 3 4]) ; => {:before 0 :after 4}
```

The advantages of these pattern matchers become even more apparent as the complexity of pattern increases.

### Unification Within a Pattern

If multiple matchers in a pattern have the same name, the values they match must unify.

Unification increases the sophistication of patterns that can be defined.

``` clojure
(def m (compile-pattern '[?fn-name (fn ?fn-name [??args] ??body)]))

(m ['abc '(fn abc [x] x)]) ; => {:fn-name 'abc ...}
(m ['xyz '(fn abc [x] x)]) ; => nil
```

Unification works across different matcher types.
The pattern `[?list [??list 3]]`, could match `[[1 2] [1 2 3]]` or `[[:x] [:x 3]]`, etc. 


### Pattern Matchers Available

Above I showed 2 matchers: `match-list` and `match-element` matching against a fixed-length vector. 
Much more flexibility is available.

The pattern could be changed to `[??before 1 2 3 ??after]`, for instance.
Now it would match a vector of any length as long as it contains the sequence `1 2 3`.

``` clojure
(def m* (compile-pattern '[??before 1 2 3 ??after]))

(m* [1 2 3])       ; => {:before [] :after []}
(m* [0 1 2 3 4 5]) ; => {:before [0] :after [4 5]}
```

Patterns also do not need to be flat.
For instance, I can match against Clojure S-Expressions. 
Here I'll introduce a three more matcher types:

``` clojure
(def let-bindings (compile-pattern '(let [(?:* (? binding* symbol?) ?_)] ??_)))

(let-bindings '(let [datum (first data)
                     c (count datum)]
                 ...))
;; => {:binding* [datum c]}
```

`?_` and `??_` are special cases of unnamed matchers.
These matchers do not unify with each other, and the value matched by them is not returned in the match results.
They are useful for ignoring parts of the input data that are not relevant to what you are trying to do.
Anywhere you can provide a matcher name, using `_` as the name has the same behavior.

The `?:*` matcher is `match-many`.
It matches a repeated subsequence within the sequence it is placed in.
In this case, that allows it to handle the characteristic flattened Clojure binding pattern.

The `(? ...)` matcher is the full form of `match-element` that we saw earlier. 
In this case, it is equivalent to `?binding*`, except that here I gave it a predicate function.
The matcher will only succeed if the predicate returns a truthy value when called with the matched value.

That means the following does not match:

``` clojure
(let-bindings '(let [42 (first data)
                     c (count datum)]
                 ...))
;; => nil
```

There are a lot more matchers and the list is gradually expanding with ever more weird and wonderful behaviors.

Each matcher in the list has a matcher implementation function with detailed documentation.

``` clojure
(register-matcher :value match-value)
(register-matcher :list #'match-list)
(register-matcher '?:= match-literal {:aliases ['?:literal]})
(register-matcher :compiled-matcher match-compiled)
(register-matcher :compiled*-matcher match-compiled*)
(register-matcher :plain-function #'match-plain-function)
(register-matcher '? #'match-element {:named? true})
(register-matcher '?? #'match-segment {:named? true})
(register-matcher '?:map match-map)
(register-matcher '?:+map #'match-+map)
(register-matcher '?:*map #'match-*map)
(register-matcher '?:as match-as {:named? true :restriction-position 3})
(register-matcher '?:? #'match-optional {:aliases ['?:optional]})
(register-matcher '?:1 #'match-one {:aliases ['?:one]})
(register-matcher '?:* #'match-many {:aliases ['?:many]})
(register-matcher '?:+ #'match-at-least-one {:aliases ['?:at-least-one]})
(register-matcher '?:chain match-chain {:aliases ['??:chain]})
(register-matcher '| #'match-or {:aliases ['?:or]})
(register-matcher '& match-and {:aliases ['?:and]})
(register-matcher '?:not match-not)
(register-matcher '?:if #'match-if)
(register-matcher '?:when #'match-when)
(register-matcher '?:letrec match-letrec)
(register-matcher '?:ref match-ref {:named? true})
(register-matcher '?:fresh #'match-fresh)
(register-matcher '?:all-fresh #'match-all-fresh)
(register-matcher '?:restartable match-restartable)
(register-matcher '?:re-matches #'match-regex)
(register-matcher '?:re-seq #'match-regex)

```

## Substitution

The core substitution function is `sub`. 
It is actually a macro that expands to be equivalent to just writing the substitution pattern as a literal value.

``` clojure
(let [x 2
      y '[a b c]]
  (sub (* (+ ??y) ?x)))
;; => (* (+ a b c) 2)
```

Macroexpanded, you can see that the substitution overhead is effectively zero:

``` clojure
(let [x 2
      y '[a b c]] 
  (list '* (list* '+ y) x))
```

You may notice that instead of passing in a map of values to `sub`, it effectively looks in the current evaluation context for replacement values.
This tends to be very convenient. 
In practice, 99% of the time I use this method.

For that last 1%, though, a more traditional data-driven substitution method is required.
For that, we can use `substitute`:

``` clojure
(substitute 
  '(* (+ ??y) ?x)
  {'x 2 'y '[a b c]})
;; => (* (+ a b c) 2)
```

## Rules

Rules combine pattern matching and substitution.
In a rule, the matched symbols are let-bound, enabling very convenient use of `sub` to rebuild the matched data.

...

## Rule Combinators

## Compilation Dialects and Tools

and nanopass-style compilation

## Acknowledgements

Pattern is based upon the core ideas described in the excellent book [Software Design for Flexibilty](https://mitpress.mit.edu/books/software-design-flexibility) by Chris Hanson and Gerald Jay Sussman.

The compilation tools aspect is heavily inspired by the [Nanopass Framework](https://nanopass.org/) which introduces the idea of dialects and was the inspiration for some of the more powerful rule combinators that this library introduces.

## License

Copyright © 2022 Darrick Wiebe

_Distributed under the Eclipse Public License version 1.0._
