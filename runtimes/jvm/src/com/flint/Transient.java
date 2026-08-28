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

    private Transient(List<Object> list, Map<Object, Object> map) {
        this.list = list;
        this.map = map;
    }

    public static Transient of(Object coll) {
        if (coll == null || coll instanceof List<?>) {
            List<Object> xs = new ArrayList<>();
            if (coll != null) xs.addAll((List<?>) coll);
            return new Transient(xs, null);
        }
        if (coll instanceof Map<?, ?> m) return new Transient(null, new LinkedHashMap<>(m));
        throw new Vm.Thrown(Builtins.prStr(coll) + " has no transient form");
    }

    public Object persistent() {
        return list != null ? List.copyOf(nonNull(list)) : FlintMap.of(map);
    }

    /// `List.copyOf` rejects nulls and `nil` is a perfectly good element, so a
    /// list holding one stays the mutable list rather than losing the element.
    private static List<Object> nonNull(List<Object> xs) {
        for (Object x : xs) if (x == null) return new ArrayList<>(xs);
        return xs;
    }
}
