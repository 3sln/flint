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
    private Transient(List<object> list, List<KeyValuePair<object, object>> map) {
        List = list; Map = map;
    }
    public static Transient Of(object coll) {
        switch (coll) {
            case null: return new Transient(new List<object>(), null);
            case Vec v: return new Transient(new List<object>(v), null);
            case Seq q: return new Transient(new List<object>(q), null);
            case FlintMap m: {
                var pairs = new List<KeyValuePair<object, object>>();
                foreach (var e in m) pairs.Add(e);
                return new Transient(null, pairs);
            }
            default: throw new FlintThrow(Builtins.PrStr(coll) + " has no transient form");
        }
    }
    public object Persistent() {
        if (List != null) return new Vec(List);
        var m = FlintMap.Empty;
        foreach (var e in Map) m = m.Assoc(e.Key, e.Value);
        return m;
    }
}

/// A sequence that has not been produced yet. Forced ONCE and cached: a thunk
/// with a side effect that ran twice would make a program say something
/// different here than on the wasm runtime.
public sealed class LazySeq : IReadOnlyList<object> {
    private object _thunk;
    private List<object> _value;
    private readonly Vm _vm;
    private readonly object _lock = new();

    public LazySeq(Vm vm, object thunk) { _vm = vm; _thunk = thunk; }

    public List<object> Force() {
        lock (_lock) {
            if (_thunk != null) {
                object outv = _vm.Call(_thunk, Array.Empty<object>());
                var xs = new List<object>();
                if (outv != null) foreach (var o in Builtins.Iterate(outv)) xs.Add(o);
                _value = xs;
                _thunk = null;
            }
            return _value;
        }
    }
    public object this[int i] => Force()[i];
    public int Count => Force().Count;
    public IEnumerator<object> GetEnumerator() => Force().GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    public override string ToString() => Builtins.PrStr(this);
}

/// A flint `throw` on its way out, carrying the thrown VALUE rather than a
/// message: `catch` binds what was thrown.
public sealed class FlintThrow : Exception {
    public readonly object Value;
    public FlintThrow(object value) : base(Builtins.Str(value)) => Value = value;
}
