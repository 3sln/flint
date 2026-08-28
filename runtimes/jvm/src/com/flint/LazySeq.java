package com.flint;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/// A sequence that has not been produced yet.
///
/// `flint/lazy-seq` takes a thunk and this holds it until someone asks. Forced
/// ONCE and cached, which is the part that matters for meaning rather than
/// speed: a thunk with a side effect that ran twice would make a program say
/// something different here than on the wasm runtime.
///
/// Nothing here roots anything. The thunk and the forced value are ordinary
/// Java references, so the JVM's collector keeps them alive exactly as long as
/// something can still reach them -- which is the whole reason tier 2 is
/// cheaper than the wasm runtime (`doc/decisions/0010`).
public final class LazySeq implements Iterable<Object> {
    private Object thunk;      // null once forced
    private List<Object> value;
    private final Vm vm;

    public LazySeq(Vm vm, Object thunk) {
        this.vm = vm;
        this.thunk = thunk;
    }

    /// The realised sequence, or an empty list. Idempotent.
    public synchronized List<Object> force() {
        if (thunk != null) {
            Object out = vm.call(thunk, new Object[0]);
            List<Object> xs = new ArrayList<>();
            if (out != null) for (Object o : Builtins.iterate(out)) xs.add(o);
            value = xs;
            thunk = null;
        }
        return value;
    }

    @Override public Iterator<Object> iterator() { return force().iterator(); }
    @Override public String toString() { return Builtins.prStr(force()); }
}
