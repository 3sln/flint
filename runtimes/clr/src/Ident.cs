using System.Collections.Concurrent;

namespace Flint;

/// A keyword: `:foo` or `:my.ns/foo`.
///
/// Interned, because `=` on keywords has to be cheap and a map keyed by one is
/// the commonest shape in Clojure. A `ConcurrentDictionary` rather than a lock:
/// several threads may intern at once, and `GetOrAdd` gives the "one text, one
/// object" property the wasm runtime spends a lock on
/// (`doc/decisions/0028`).
///
/// Deliberately NOT the same class as `Sym`. A keyword and a symbol with the
/// same text are not equal, and sharing a class would make that an accident
/// waiting to be relied upon.
public sealed class Kw {
    public readonly string Ns;    // null when unqualified
    public readonly string Name;
    private readonly int _hash;

    private static readonly ConcurrentDictionary<string, Kw> Table = new();

    private Kw(string ns, string name) {
        Ns = ns;
        Name = name;
        _hash = Hash.HashKeyword(ns, name);
    }

    public static Kw Of(string ns, string name) {
        string key = ns == null ? name : ns + "/" + name;
        return Table.GetOrAdd(key, _ => new Kw(ns, name));
    }

    public override int GetHashCode() => _hash;
    public override bool Equals(object o) => ReferenceEquals(this, o); // interned
    public override string ToString() => Ns == null ? ":" + Name : ":" + Ns + "/" + Name;
}

/// A symbol: `foo` or `my.ns/foo`. Interned for the same reasons as `Kw`.
public sealed class Sym {
    public readonly string Ns;
    public readonly string Name;
    private readonly int _hash;

    private static readonly ConcurrentDictionary<string, Sym> Table = new();

    private Sym(string ns, string name) {
        Ns = ns;
        Name = name;
        _hash = Hash.HashSymbol(ns, name);
    }

    public static Sym Of(string ns, string name) {
        string key = ns == null ? name : ns + "/" + name;
        return Table.GetOrAdd(key, _ => new Sym(ns, name));
    }

    public override int GetHashCode() => _hash;
    public override bool Equals(object o) => ReferenceEquals(this, o);
    public override string ToString() => Ns == null ? Name : Ns + "/" + Name;
}
