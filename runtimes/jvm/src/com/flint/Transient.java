package com.flint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// A collection being built.
///
/// flint's transients exist so that building a vector or a map is not N copies
/// (`doc/decisions/0024`). On the JVM the same win comes from mutating a plain
/// `ArrayList` or `LinkedHashMap` and handing back an immutable view at
/// `persistent!`.
///
/// **Single-owner, and not enforced here.** flint's rule is that a transient
/// belongs to whoever made it; two threads sharing one is a program error
/// rather than something this defends against, exactly as on the wasm runtime.
public final class Transient {
    final List<Object> list;
    final Map<Object, Object> map;
    final java.util.LinkedHashSet<Object> set;

    private Transient(List<Object> list, Map<Object, Object> map,
                      java.util.LinkedHashSet<Object> set) {
        this.list = list;
        this.map = map;
        this.set = set;
    }

    public static Transient of(Object coll) {
        if (coll == null || coll instanceof List<?>) {
            List<Object> xs = new ArrayList<>();
            if (coll != null) xs.addAll((List<?>) coll);
            return new Transient(xs, null, null);
        }
        if (coll instanceof Map<?, ?> m) return new Transient(null, new LinkedHashMap<>(m), null);
        if (coll instanceof java.util.Set<?> s) {
            return new Transient(null, null, new java.util.LinkedHashSet<>(s));
        }
        throw Vm.err(Builtins.prStr(coll) + " has no transient form");
    }

    public Object persistent() {
        if (set != null) return set;
        if (map != null) return FlintMap.of(map);
        // NOT `List.copyOf`: it rejects nulls, and `nil` is a perfectly good
        // element of a flint vector. The guard that used to be here returned
        // the same list on both branches, so it guarded nothing -- and the
        // failure was a NullPointerException from inside the JDK, four frames
        // below any flint code, while the compiler built a vector with a nil
        // in it.
        return java.util.Collections.unmodifiableList(new ArrayList<>(list));
    }
}
