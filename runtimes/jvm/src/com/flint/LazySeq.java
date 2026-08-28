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
    private Object thunk;      // null once stepped
    private Object stepped;
    private final Vm vm;

    public LazySeq(Vm vm, Object thunk) {
        this.vm = vm;
        this.thunk = thunk;
    }

    /// ONE step: the thunk's own answer, cached. NOT the whole sequence.
    ///
    /// Materialising here is what overflowed the stack: a chain of lazy seqs
    /// forced one another all the way down, one JVM frame per element. The
    /// caller walks the result iteratively instead.
    public synchronized Object step() {
        if (thunk != null) {
            stepped = vm.call(thunk, new Object[0]);
            thunk = null;
        }
        return stepped;
    }

    /// The whole sequence, walked iteratively. Only for callers that genuinely
    /// need every element.
    public List<Object> force() {
        List<Object> out = new ArrayList<>();
        for (Object o : Builtins.iterate(this)) out.add(o);
        return out;
    }

    @Override public Iterator<Object> iterator() { return force().iterator(); }
    @Override public String toString() { return Builtins.prStr(this); }
}
