package com.flint;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// A keyword: `:foo` or `:my.ns/foo`.
///
/// Interned, because `=` on keywords has to be cheap and because a map keyed by
/// one is the commonest shape in Clojure. A `ConcurrentHashMap` rather than a
/// lock: several executors may intern at once, and `computeIfAbsent` gives the
/// "one text, one object" property the Rust runtime spends a lock on
/// (`doc/decisions/0028`).
///
/// Deliberately NOT the same class as `Sym`. A keyword and a symbol with the
/// same text are not equal, and sharing a class would make that an accident
/// waiting to be relied upon.
public final class Kw {
    public final String ns;   // null when unqualified
    public final String name;
    private final int hash;

    private static final Map<String, Kw> TABLE = new ConcurrentHashMap<>();

    private Kw(String ns, String name) {
        this.ns = ns;
        this.name = name;
        // Mixed with a constant so `:foo` and the string "foo" do not collide
        // in a map that holds both. The Rust runtime does the same thing for
        // the same reason.
        this.hash = (ns == null ? 0 : ns.hashCode() * 31) ^ name.hashCode() ^ 0x9E3779B9;
    }

    public static Kw of(String ns, String name) {
        String key = ns == null ? name : ns + "/" + name;
        return TABLE.computeIfAbsent(key, k -> new Kw(ns, name));
    }

    /// `"a"` is `:a`; `"my.ns/a"` is `:my.ns/a`.
    public static Kw parse(String text) {
        int slash = text.indexOf('/');
        return slash <= 0 ? of(null, text) : of(text.substring(0, slash), text.substring(slash + 1));
    }

    @Override public int hashCode() { return hash; }
    @Override public boolean equals(Object o) { return this == o; } // interned
    @Override public String toString() { return ns == null ? ":" + name : ":" + ns + "/" + name; }
}
