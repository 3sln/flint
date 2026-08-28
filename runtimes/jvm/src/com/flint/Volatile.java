package com.flint;

/// A `volatile!`: an atom without the atomicity, for a value only one thread
/// ever touches. flint's core library uses them where a transient would be
/// overkill and an atom's retry would be waste.
public final class Volatile {
    private Object value;
    public Volatile(Object v) { value = v; }
    public Object deref() { return value; }
    public Object reset(Object v) { value = v; return v; }
    @Override public String toString() { return "#volatile[" + Builtins.prStr(value) + "]"; }
}
