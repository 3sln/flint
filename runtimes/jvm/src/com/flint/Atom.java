package com.flint;

import java.util.concurrent.atomic.AtomicReference;

/// An `atom`: the one mutable cell flint has.
///
/// `AtomicReference` rather than a field, because several threads may run one
/// program (`doc/decisions/0029`).
///
/// `swap!` is NOT here. It lives in `lib/clojure/core.cljc` as a retry loop
/// over `compare-and-set!`, so every host gets it from the same source rather
/// than from three implementations that agree by inspection. What this has to
/// provide is the primitive underneath.
public final class Atom {
    private final AtomicReference<Object> cell;

    public Atom(Object initial) { this.cell = new AtomicReference<>(initial); }

    public Object deref() { return cell.get(); }
    public Object reset(Object v) { cell.set(v); return v; }

    /// Set to `next` only if the atom still holds `expect`.
    ///
    /// By IDENTITY, as Clojure's is: a value-equal but distinct object means
    /// someone else has been here, and a retry is cheaper than a deep
    /// comparison on every attempt.
    public boolean compareAndSet(Object expect, Object next) {
        return cell.compareAndSet(expect, next);
    }

    @Override public String toString() { return "#atom[" + Builtins.prStr(deref()) + "]"; }
}
