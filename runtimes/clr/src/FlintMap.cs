using System.Collections;
using System.Collections.Generic;

namespace Flint;

/// A flint map, iterating in the order flint iterates.
///
/// Not a performance concern: `pr-str` of a map is how an answer is compared,
/// so a map holding the right pairs in a different order gives a DIFFERENT
/// ANSWER to every test that prints one. `doc/decisions/0010` singles this out
/// as the divergence that is not cosmetic, because content-addressed artifacts
/// would hash differently per host.
///
/// The shape mirrors `runtime/src/map.rs` and the JVM port:
///
/// * up to `ArrayMapMax` (8) entries, a flat list in INSERTION order;
/// * past that it promotes to a CHAMP and iterates in HASH order -- every entry
///   stored inline at a node, in bit order, then each sub-node.
public sealed class FlintMap : IReadOnlyCollection<KeyValuePair<object, object>> {
    private const int ArrayMapMax = 8;
    private const int Bits = 5;

    private readonly List<KeyValuePair<object, object>> _small; // null once promoted
    private readonly Node _root;                                // null while small
    private readonly int _count;

    public static readonly FlintMap Empty =
        new FlintMap(new List<KeyValuePair<object, object>>(), null, 0);

    private FlintMap(List<KeyValuePair<object, object>> small, Node root, int count) {
        _small = small; _root = root; _count = count;
    }

    public int Count => _count;

    public FlintMap Assoc(object k, object v) {
        if (_small != null) {
            var next = new List<KeyValuePair<object, object>>(_small);
            for (int i = 0; i < next.Count; i++) {
                if (Builtins.Eq(next[i].Key, k)) {
                    next[i] = new KeyValuePair<object, object>(next[i].Key, v);
                    return new FlintMap(next, null, next.Count);
                }
            }
            next.Add(new KeyValuePair<object, object>(k, v));
            if (next.Count <= ArrayMapMax) return new FlintMap(next, null, next.Count);
            // Promote, exactly where flint promotes.
            Node r = null;
            int n = 0;
            foreach (var e in next) {
                var ins = Node.Assoc(r, e.Key, e.Value, Hash.Of(e.Key), 0);
                r = ins.Node;
                if (ins.Added) n++;
            }
            return new FlintMap(null, r, n);
        }
        var it = Node.Assoc(_root, k, v, Hash.Of(k), 0);
        return new FlintMap(null, it.Node, _count + (it.Added ? 1 : 0));
    }

    public FlintMap Dissoc(object k) {
        var outm = Empty;
        foreach (var e in this) if (!Builtins.Eq(e.Key, k)) outm = outm.Assoc(e.Key, e.Value);
        return outm;
    }

    public object Get(object k, object dflt) {
        if (_small != null) {
            foreach (var e in _small) if (Builtins.Eq(e.Key, k)) return e.Value;
            return dflt;
        }
        return Node.Get(_root, k, Hash.Of(k), 0, dflt);
    }

    public bool ContainsKey(object k) => !ReferenceEquals(Get(k, Missing), Missing);
    private static readonly object Missing = new();

    public IEnumerator<KeyValuePair<object, object>> GetEnumerator() {
        var outl = new List<KeyValuePair<object, object>>(_count);
        if (_small != null) outl.AddRange(_small);
        else Node.Walk(_root, outl);
        return outl.GetEnumerator();
    }
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    public override string ToString() => Builtins.PrStr(this);

    /// A CHAMP bitmap node: entries inline, then sub-nodes.
    private sealed class Node {
        private readonly int _datamap, _nodemap;
        private readonly object[] _entries;   // 2 * popcount(datamap)
        private readonly Node[] _nodes;       // popcount(nodemap)
        /// Keys whose hashes collided all the way down. Rare, and the reason a
        /// trie keyed on a 32-bit hash needs a fallback at all.
        private readonly List<KeyValuePair<object, object>> _collisions;

        private Node(int datamap, int nodemap, object[] entries, Node[] nodes,
                     List<KeyValuePair<object, object>> collisions) {
            _datamap = datamap; _nodemap = nodemap;
            _entries = entries; _nodes = nodes; _collisions = collisions;
        }

        private static Node EmptyNode() =>
            new Node(0, 0, Array.Empty<object>(), Array.Empty<Node>(), null);

        private static int Mask(int h, int shift) => (int) (((uint) h >> shift) & 0x1f);
        private static int Bitpos(int h, int shift) => 1 << Mask(h, shift);
        private static int IndexOf(int bitmap, int bit) =>
            System.Numerics.BitOperations.PopCount((uint) (bitmap & (bit - 1)));

        internal readonly struct Ins {
            public readonly Node Node; public readonly bool Added;
            public Ins(Node n, bool a) { Node = n; Added = a; }
        }

        internal static Ins Assoc(Node n, object k, object v, int h, int shift) {
            n ??= EmptyNode();
            if (shift >= 32) {
                var cs = n._collisions == null
                    ? new List<KeyValuePair<object, object>>()
                    : new List<KeyValuePair<object, object>>(n._collisions);
                for (int i = 0; i < cs.Count; i++) {
                    if (Builtins.Eq(cs[i].Key, k)) {
                        cs[i] = new KeyValuePair<object, object>(k, v);
                        return new Ins(new Node(0, 0, Array.Empty<object>(),
                                                Array.Empty<Node>(), cs), false);
                    }
                }
                cs.Add(new KeyValuePair<object, object>(k, v));
                return new Ins(new Node(0, 0, Array.Empty<object>(), Array.Empty<Node>(), cs), true);
            }
            int bit = Bitpos(h, shift);
            if ((n._datamap & bit) != 0) {
                int i = IndexOf(n._datamap, bit);
                object ek = n._entries[2 * i];
                if (Builtins.Eq(ek, k)) {
                    var es0 = (object[]) n._entries.Clone();
                    es0[2 * i + 1] = v;
                    return new Ins(new Node(n._datamap, n._nodemap, es0, n._nodes, n._collisions), false);
                }
                object ev = n._entries[2 * i + 1];
                Node sub = Assoc(null, ek, ev, Hash.Of(ek), shift + Bits).Node;
                sub = Assoc(sub, k, v, h, shift + Bits).Node;
                var es = new object[n._entries.Length - 2];
                Array.Copy(n._entries, 0, es, 0, 2 * i);
                Array.Copy(n._entries, 2 * i + 2, es, 2 * i, es.Length - 2 * i);
                int ni = IndexOf(n._nodemap, bit);
                var ns = new Node[n._nodes.Length + 1];
                Array.Copy(n._nodes, 0, ns, 0, ni);
                ns[ni] = sub;
                Array.Copy(n._nodes, ni, ns, ni + 1, n._nodes.Length - ni);
                return new Ins(new Node(n._datamap & ~bit, n._nodemap | bit, es, ns, n._collisions), true);
            }
            if ((n._nodemap & bit) != 0) {
                int i = IndexOf(n._nodemap, bit);
                var sub = Assoc(n._nodes[i], k, v, h, shift + Bits);
                var ns = (Node[]) n._nodes.Clone();
                ns[i] = sub.Node;
                return new Ins(new Node(n._datamap, n._nodemap, n._entries, ns, n._collisions), sub.Added);
            }
            int at = IndexOf(n._datamap, bit);
            var e2 = new object[n._entries.Length + 2];
            Array.Copy(n._entries, 0, e2, 0, 2 * at);
            e2[2 * at] = k;
            e2[2 * at + 1] = v;
            Array.Copy(n._entries, 2 * at, e2, 2 * at + 2, n._entries.Length - 2 * at);
            return new Ins(new Node(n._datamap | bit, n._nodemap, e2, n._nodes, n._collisions), true);
        }

        internal static object Get(Node n, object k, int h, int shift, object dflt) {
            if (n == null) return dflt;
            if (n._collisions != null) {
                foreach (var e in n._collisions) if (Builtins.Eq(e.Key, k)) return e.Value;
                return dflt;
            }
            int bit = Bitpos(h, shift);
            if ((n._datamap & bit) != 0) {
                int i = IndexOf(n._datamap, bit);
                return Builtins.Eq(n._entries[2 * i], k) ? n._entries[2 * i + 1] : dflt;
            }
            if ((n._nodemap & bit) != 0)
                return Get(n._nodes[IndexOf(n._nodemap, bit)], k, h, shift + Bits, dflt);
            return dflt;
        }

        /// Every inline entry in bit order, THEN each sub-node. That order is
        /// the whole reason this class exists -- it is what `map.rs`'s
        /// `node_for_each` does, and matching it is what makes two hosts print
        /// the same map.
        internal static void Walk(Node n, List<KeyValuePair<object, object>> outl) {
            if (n == null) return;
            if (n._collisions != null) { outl.AddRange(n._collisions); return; }
            int ne = System.Numerics.BitOperations.PopCount((uint) n._datamap);
            for (int i = 0; i < ne; i++)
                outl.Add(new KeyValuePair<object, object>(n._entries[2 * i], n._entries[2 * i + 1]));
            foreach (var sub in n._nodes) Walk(sub, outl);
        }
    }
}
