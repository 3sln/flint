package com.flint;

/// A cons cell: a head and a tail that may not exist yet.
///
/// This is what makes laziness actually lazy. `cons` used to copy its tail into
/// a flat list, so `(cons x (lazy-seq ...))` forced the whole sequence -- which
/// is not merely slow, it is unbounded: forcing a chain of lazy seqs recursed
/// once per element and overflowed the stack on any real program.
///
/// So the tail is held, not walked. `Builtins.iterate` walks it ITERATIVELY,
/// which is the other half: a loop cannot overflow where recursion did.
public final class Cons {
    public final Object head;
    /// A `Cons`, a `LazySeq`, a list, or null. Whatever it is, it is not
    /// touched until someone asks.
    public final Object tail;

    public Cons(Object head, Object tail) {
        this.head = head;
        this.tail = tail;
    }

    @Override public String toString() { return Builtins.prStr(this); }
}
