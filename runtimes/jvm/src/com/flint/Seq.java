package com.flint;

import java.util.AbstractList;
import java.util.List;

/// A sequence, as distinct from a vector.
///
/// They hold the same elements and are `=` to each other, and they PRINT
/// differently: `(2 3)` against `[2 3]`. That is not cosmetic. `pr-str` is how
/// a program's answer is compared, so a port that returned a vector where flint
/// returns a seq gives a different answer to every test that prints one -- and
/// gives it silently, because the elements are right.
///
/// It really is a `List`, so equality, iteration and indexing all work without
/// a special case; only printing and `vector?` look at the class.
public final class Seq extends AbstractList<Object> {
    private final List<Object> items;

    public Seq(List<Object> items) { this.items = items; }

    public static Seq of(List<Object> items) { return new Seq(items); }

    @Override public Object get(int i) { return items.get(i); }
    @Override public int size() { return items.size(); }
    @Override public String toString() { return Builtins.prStr(this); }
}
