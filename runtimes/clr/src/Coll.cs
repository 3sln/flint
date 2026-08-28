using System.Collections;
using System.Collections.Generic;

namespace Flint;

/// A sequence, as distinct from a vector.
///
/// They hold the same elements, are `=` to each other, and PRINT differently:
/// `(2 3)` against `[2 3]`. Not cosmetic -- `pr-str` is how a program's answer
/// is compared, so a port returning a vector where flint returns a seq gives a
/// different answer to every test that prints one, silently, with the right
/// elements in it. The JVM port made exactly that mistake.
public sealed class Seq : IReadOnlyList<object> {
    private readonly IReadOnlyList<object> _items;
    public Seq(IReadOnlyList<object> items) => _items = items;
    public object this[int i] => _items[i];
    public int Count => _items.Count;
    public IEnumerator<object> GetEnumerator() => _items.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    public override string ToString() => Builtins.PrStr(this);
}

/// A vector.
public sealed class Vec : IReadOnlyList<object> {
    private readonly List<object> _items;
    public Vec() => _items = new List<object>();
    public Vec(IEnumerable<object> xs) => _items = new List<object>(xs);
    public object this[int i] => _items[i];
    public int Count => _items.Count;
    public Vec Conj(object x) { var v = new Vec(_items); v._items.Add(x); return v; }
    internal List<object> Raw => _items;
    public IEnumerator<object> GetEnumerator() => _items.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    public override string ToString() => Builtins.PrStr(this);
}

/// A set, keeping flint's equality rather than .NET's.
public sealed class FlintSet : IReadOnlyCollection<object> {
    private readonly List<object> _items = new();
    public FlintSet() {}
    public FlintSet(IEnumerable<object> xs) { foreach (var x in xs) Add(x); }
    private void Add(object x) { if (!Contains(x)) _items.Add(x); }
    public bool Contains(object x) {
        foreach (var y in _items) if (Builtins.Eq(x, y)) return true;
        return false;
    }
    public FlintSet Conj(object x) { var s = new FlintSet(_items); s.Add(x); return s; }
    public FlintSet Disj(object x) {
        var s = new FlintSet();
        foreach (var y in _items) if (!Builtins.Eq(x, y)) s.Add(y);
        return s;
    }
    public int Count => _items.Count;
    public IEnumerator<object> GetEnumerator() => _items.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    public override string ToString() => Builtins.PrStr(this);
}

/// An `atom`: the one mutable cell flint has.
public sealed class Atom {
    private object _cell;
    private readonly object _lock = new();
    public Atom(object initial) => _cell = initial;
    public object Deref() { lock (_lock) return _cell; }
    public object Reset(object v) { lock (_lock) { _cell = v; return v; } }
    /// By REFERENCE, as Clojure's is: a value-equal but distinct object means
    /// someone else has been here, and a retry is cheaper than a deep compare
    /// on every attempt.
    public bool CompareAndSet(object expect, object next) {
        lock (_lock) {
            if (!ReferenceEquals(_cell, expect) && !Builtins.Identical(_cell, expect)) return false;
            _cell = next;
            return true;
        }
    }
    public override string ToString() => "#atom[" + Builtins.PrStr(Deref()) + "]";
}

/// A collection being built. flint's transients exist so that building a vector
/// or a map is not N copies (`doc/decisions/0024`).
public sealed class Transient {
    internal readonly List<object> List;
    internal readonly List<KeyValuePair<object, object>> Map;
    internal bool IsSet;
    private Transient(List<object> list, List<KeyValuePair<object, object>> map) {
        List = list; Map = map;
    }
    public static Transient Of(object coll) {
        switch (coll) {
            case null: return new Transient(new List<object>(), null);
            case Vec v: return new Transient(new List<object>(v), null);
            case Seq q: return new Transient(new List<object>(q), null);
            case FlintSet fs: {
                var xs = new List<object>();
                foreach (var o in fs) xs.Add(o);
                return new Transient(xs, null) { IsSet = true };
            }
            case FlintMap m: {
                var pairs = new List<KeyValuePair<object, object>>();
                foreach (var e in m) pairs.Add(e);
                return new Transient(null, pairs);
            }
            default: throw new FlintThrow(Builtins.PrStr(coll) + " has no transient form");
        }
    }
    public object Persistent() {
        if (IsSet) return new FlintSet(List);
        if (List != null) return new Vec(List);
        var m = FlintMap.Empty;
        foreach (var e in Map) m = m.Assoc(e.Key, e.Value);
        return m;
    }
}

/// A cons cell: a head and a tail that may not exist yet.
///
/// This is what makes laziness lazy. `cons` used to copy its tail into a flat
/// list, so `(cons x (lazy-seq ...))` forced the whole sequence -- not merely
/// slow but unbounded, because flint has INFINITE lazy sequences and walking
/// one to build a list does not end.
public sealed class Cons {
    public readonly object Head;
    /// A `Cons`, a `LazySeq`, a list, or null. Not touched until asked for.
    public readonly object Tail;
    public Cons(object head, object tail) { Head = head; Tail = tail; }
    public override string ToString() => Builtins.PrStr(this);
}

/// A sequence that has not been produced yet. Stepped ONCE and cached: a thunk
/// with a side effect that ran twice would make a program say something
/// different here than on the wasm runtime.
public sealed class LazySeq {
    private object _thunk;
    private object _stepped;
    private readonly Vm _vm;
    private readonly object _lock = new();

    public LazySeq(Vm vm, object thunk) { _vm = vm; _thunk = thunk; }

    /// ONE step: the thunk's own answer, cached. NOT the whole sequence --
    /// materialising here is what a chain of lazy seqs turns into a stack
    /// overflow, one frame per element.
    public object Step() {
        lock (_lock) {
            if (_thunk != null) {
                _stepped = _vm.Call(_thunk, Array.Empty<object>());
                _thunk = null;
            }
            return _stepped;
        }
    }

    public List<object> Force() {
        var outl = new List<object>();
        foreach (var o in Builtins.Iterate(this)) outl.Add(o);
        return outl;
    }
    public override string ToString() => Builtins.PrStr(this);
}

/// A flint `throw` on its way out, carrying the thrown VALUE rather than a
/// message: `catch` binds what was thrown.
/// A thrown error: kind, message, data.
///
/// A DISTINCT type, not a map, matching `runtime/src/err.rs` where an exception
/// is its own object with kind, message and data slots. The JVM port has the
/// same class for the same reason.
///
/// The difference is not cosmetic, and it cost a debugging session on each
/// port. Builtins here threw a bare STRING, so `ex-message` on one answered
/// nil -- and the flint compiler, which catches an error and re-throws it with
/// the form it happened in, produced `"\n  in clojure.core/identity"`: a
/// location with no message in front of it. The failure was real and the
/// report said nothing, which is the worst combination.
public sealed class Ex {
    public readonly string Kind;
    public readonly object Message;
    public readonly object Data;
    public Ex(string kind, object message, object data) { Kind = kind; Message = message; Data = data; }
    public override string ToString() => Kind + ": " + Builtins.Str(Message);
}

public sealed class FlintThrow : Exception {
    public readonly object Value;
    public FlintThrow(object value) : base(Builtins.Str(value)) => Value = value;

    /// A message thrown by a builtin. Wrapped, so `ex-message` can find it:
    /// a bare string is a value with no message, and the compiler's own error
    /// wrapper reads the message off what it caught.
    public FlintThrow(string message) : this(new Ex("Error", message, null)) { }
}
