(ns test.reader.cond-map)
(def m {#?(:flint :k :default :j) 1 :z #?@(:flint [2])})
(def s #{#?(:flint :a) #?(:default :b)})
