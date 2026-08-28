package com.flint;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// A flint map, iterating in the order flint iterates.
///
/// Not a performance concern: `pr-str` of a map is how an answer is compared,
/// so a map that holds the right pairs in a different order gives a DIFFERENT
/// ANSWER to every test that prints one. `doc/decisions/0010` singles this out
/// as the divergence that is not cosmetic, because content-addressed artifacts
/// would hash differently per host.
///
/// The shape mirrors `runtime/src/map.rs`:
///
/// * up to `ARRAY_MAP_MAX` (8) entries a map is a flat array in INSERTION
///   order, which is what a compiler's AST nodes mostly are and what a linear
///   scan beats a trie on;
/// * past that it promotes to a CHAMP and iterates in HASH order -- every
///   entry stored inline at a node, in bit order, then each sub-node.
///
/// It extends `AbstractMap`, so everything that already treats a flint map as a
/// `java.util.Map` keeps working and only the iteration order changes.
public final class FlintMap extends AbstractMap<Object, Object> {
    static final int ARRAY_MAP_MAX = 8;
    private static final int BITS = 5;

    /// Insertion-ordered while small; null once promoted.
    private final LinkedHashMap<Object, Object> small;
    /// The CHAMP root once large; null while small.
    private final Node root;
    private final int count;

    private FlintMap(LinkedHashMap<Object, Object> small, Node root, int count) {
        this.small = small; this.root = root; this.count = count;
    }

    public static FlintMap empty() {
        return new FlintMap(new LinkedHashMap<>(), null, 0);
    }

    public static FlintMap of(Map<?, ?> m) {
        FlintMap out = empty();
        for (var e : m.entrySet()) out = out.assoc(e.getKey(), e.getValue());
        return out;
    }

    public FlintMap assoc(Object k, Object v) {
        if (small != null) {
            LinkedHashMap<Object, Object> next = new LinkedHashMap<>(small);
            Object existing = keyIn(next, k);
            if (existing != NOT_FOUND) next.put(existing, v);
            else next.put(k, v);
            if (next.size() <= ARRAY_MAP_MAX) return new FlintMap(next, null, next.size());
            // Promote, exactly where flint promotes.
            Node r = null;
            int n = 0;
            for (var e : next.entrySet()) {
                Node.Ins ins = Node.assoc(r, e.getKey(), e.getValue(), Hash.of(e.getKey()), 0);
                r = ins.node();
                if (ins.added()) n++;
            }
            return new FlintMap(null, r, n);
        }
        Node.Ins ins = Node.assoc(root, k, v, Hash.of(k), 0);
        return new FlintMap(null, ins.node(), count + (ins.added() ? 1 : 0));
    }

    private static final Object NOT_FOUND = new Object();

    /// flint's `=` rather than Java's, because `1` and `1L` are one key here
    /// and a keyword is interned but a string is not.
    private static Object keyIn(Map<Object, Object> m, Object k) {
        for (Object existing : m.keySet()) if (Builtins.eq(existing, k)) return existing;
        return NOT_FOUND;
    }

    @Override public int size() { return count; }

    @Override public Object get(Object k) {
        if (small != null) {
            for (var e : small.entrySet()) if (Builtins.eq(e.getKey(), k)) return e.getValue();
            return null;
        }
        return Node.get(root, k, Hash.of(k), 0);
    }

    @Override public boolean containsKey(Object k) {
        if (small != null) {
            for (Object existing : small.keySet()) if (Builtins.eq(existing, k)) return true;
            return false;
        }
        return Node.get(root, k, Hash.of(k), 0) != null || Node.has(root, k, Hash.of(k), 0);
    }

    @Override public Set<Entry<Object, Object>> entrySet() {
        List<Entry<Object, Object>> out = new ArrayList<>(count);
        if (small != null) out.addAll(small.entrySet());
        else Node.walk(root, out);
        return new AbstractSet<>() {
            @Override public Iterator<Entry<Object, Object>> iterator() { return out.iterator(); }
            @Override public int size() { return out.size(); }
        };
    }

    /// A CHAMP bitmap node: entries inline, then sub-nodes.
    private static final class Node {
        final int datamap, nodemap;
        final Object[] entries;   // 2 * bitCount(datamap)
        final Node[] nodes;       // bitCount(nodemap)
        /// Keys whose hashes collided all the way down. Rare, and the reason a
        /// trie keyed on a 32-bit hash needs a fallback at all.
        final List<Entry<Object, Object>> collisions;

        Node(int datamap, int nodemap, Object[] entries, Node[] nodes,
             List<Entry<Object, Object>> collisions) {
            this.datamap = datamap; this.nodemap = nodemap;
            this.entries = entries; this.nodes = nodes; this.collisions = collisions;
        }

        static Node emptyNode() {
            return new Node(0, 0, new Object[0], new Node[0], null);
        }

        static int mask(int h, int shift) { return (h >>> shift) & 0x1f; }
        static int bitpos(int h, int shift) { return 1 << mask(h, shift); }
        static int indexOf(int bitmap, int bit) { return Integer.bitCount(bitmap & (bit - 1)); }

        record Ins(Node node, boolean added) {}

        static Ins assoc(Node n, Object k, Object v, int h, int shift) {
            if (n == null) n = emptyNode();
            if (shift >= 32) {
                // Past the hash: a collision list, compared by value.
                List<Entry<Object, Object>> cs =
                    n.collisions == null ? new ArrayList<>() : new ArrayList<>(n.collisions);
                for (int i = 0; i < cs.size(); i++) {
                    if (Builtins.eq(cs.get(i).getKey(), k)) {
                        cs.set(i, new SimpleEntry<>(k, v));
                        return new Ins(new Node(0, 0, new Object[0], new Node[0], cs), false);
                    }
                }
                cs.add(new SimpleEntry<>(k, v));
                return new Ins(new Node(0, 0, new Object[0], new Node[0], cs), true);
            }
            int bit = bitpos(h, shift);
            if ((n.datamap & bit) != 0) {
                int i = indexOf(n.datamap, bit);
                Object ek = n.entries[2 * i];
                if (Builtins.eq(ek, k)) {
                    Object[] es = n.entries.clone();
                    es[2 * i + 1] = v;
                    return new Ins(new Node(n.datamap, n.nodemap, es, n.nodes, n.collisions), false);
                }
                // Two keys at this slot: push both down a level.
                Object ev = n.entries[2 * i + 1];
                Node sub = assoc(null, ek, ev, Hash.of(ek), shift + BITS).node();
                sub = assoc(sub, k, v, h, shift + BITS).node();
                Object[] es = new Object[n.entries.length - 2];
                System.arraycopy(n.entries, 0, es, 0, 2 * i);
                System.arraycopy(n.entries, 2 * i + 2, es, 2 * i, es.length - 2 * i);
                int ni = indexOf(n.nodemap, bit);
                Node[] ns = new Node[n.nodes.length + 1];
                System.arraycopy(n.nodes, 0, ns, 0, ni);
                ns[ni] = sub;
                System.arraycopy(n.nodes, ni, ns, ni + 1, n.nodes.length - ni);
                return new Ins(new Node(n.datamap & ~bit, n.nodemap | bit, es, ns, n.collisions), true);
            }
            if ((n.nodemap & bit) != 0) {
                int i = indexOf(n.nodemap, bit);
                Ins sub = assoc(n.nodes[i], k, v, h, shift + BITS);
                Node[] ns = n.nodes.clone();
                ns[i] = sub.node();
                return new Ins(new Node(n.datamap, n.nodemap, n.entries, ns, n.collisions), sub.added());
            }
            int i = indexOf(n.datamap, bit);
            Object[] es = new Object[n.entries.length + 2];
            System.arraycopy(n.entries, 0, es, 0, 2 * i);
            es[2 * i] = k;
            es[2 * i + 1] = v;
            System.arraycopy(n.entries, 2 * i, es, 2 * i + 2, n.entries.length - 2 * i);
            return new Ins(new Node(n.datamap | bit, n.nodemap, es, n.nodes, n.collisions), true);
        }

        static Object get(Node n, Object k, int h, int shift) {
            if (n == null) return null;
            if (n.collisions != null) {
                for (var e : n.collisions) if (Builtins.eq(e.getKey(), k)) return e.getValue();
                return null;
            }
            int bit = bitpos(h, shift);
            if ((n.datamap & bit) != 0) {
                int i = indexOf(n.datamap, bit);
                return Builtins.eq(n.entries[2 * i], k) ? n.entries[2 * i + 1] : null;
            }
            if ((n.nodemap & bit) != 0) {
                return get(n.nodes[indexOf(n.nodemap, bit)], k, h, shift + BITS);
            }
            return null;
        }

        static boolean has(Node n, Object k, int h, int shift) {
            if (n == null) return false;
            if (n.collisions != null) {
                for (var e : n.collisions) if (Builtins.eq(e.getKey(), k)) return true;
                return false;
            }
            int bit = bitpos(h, shift);
            if ((n.datamap & bit) != 0) return Builtins.eq(n.entries[2 * indexOf(n.datamap, bit)], k);
            if ((n.nodemap & bit) != 0) return has(n.nodes[indexOf(n.nodemap, bit)], k, h, shift + BITS);
            return false;
        }

        /// Every inline entry in bit order, THEN each sub-node. That order is
        /// the whole reason this class exists -- it is what `map.rs`'s
        /// `node_for_each` does, and matching it is what makes two hosts print
        /// the same map.
        static void walk(Node n, List<Entry<Object, Object>> out) {
            if (n == null) return;
            if (n.collisions != null) { out.addAll(n.collisions); return; }
            for (int i = 0; i < Integer.bitCount(n.datamap); i++) {
                out.add(new SimpleEntry<>(n.entries[2 * i], n.entries[2 * i + 1]));
            }
            for (Node sub : n.nodes) walk(sub, out);
        }
    }
}
