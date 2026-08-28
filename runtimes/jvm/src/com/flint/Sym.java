package com.flint;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// A symbol: `foo` or `my.ns/foo`. Interned for the same reasons as `Kw`.
public final class Sym {
    public final String ns;
    public final String name;
    private final int hash;

    private static final Map<String, Sym> TABLE = new ConcurrentHashMap<>();

    private Sym(String ns, String name) {
        this.ns = ns;
        this.name = name;
        this.hash = (ns == null ? 0 : ns.hashCode() * 31) ^ name.hashCode();
    }

    public static Sym of(String ns, String name) {
        String key = ns == null ? name : ns + "/" + name;
        return TABLE.computeIfAbsent(key, k -> new Sym(ns, name));
    }

    @Override public int hashCode() { return hash; }
    @Override public boolean equals(Object o) { return this == o; }
    @Override public String toString() { return ns == null ? name : ns + "/" + name; }
}
