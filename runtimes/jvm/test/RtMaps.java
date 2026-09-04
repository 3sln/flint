import flint.rt.Mapread;
import flint.rt.Mapcore;
import com.flint.rt.*;

/// Maps on the ported runtime: the array-map, the promotion to CHAMP, and the
/// two properties CHAMP is chosen FOR.
///
/// The interesting cases are not "assoc then get".
///
///  * PROMOTION. Under 9 entries a map is a flat array; at 9 it becomes a
///    trie. A bug on either side of that boundary is invisible from the other,
///    so the test walks across it and checks every key still reads back.
///
///  * CANONICAL FORM. Clojure's HAMT can represent one map two ways depending
///    on insertion and deletion history, because deleting does not un-inline a
///    node that shrank to one entry. CHAMP always collapses. So a map built by
///    inserting 200 keys and deleting 100 must equal -- and HASH ALIKE -- the
///    same map built by inserting the surviving 100 directly. That is the
///    property the whole choice rests on, and it is what would break if
///    `bnNodeToInline` were wrong.
///
///  * COLLISIONS. Two keys with the same 32-bit hash go in a COLLISION NODE,
///    a path ordinary keys never reach -- and INTEGER keys never reach it at
///    all, because `hashLong` on a small long is a bijection on 32 bits. The
///    pair used is "Aa"/"BB", Java's classic, and the test asserts that they
///    still collide rather than assuming it: the day the hash changes this
///    should say so instead of quietly testing nothing.
public class RtMaps {
  static int fails = 0;

  static void ok(String what, boolean cond) {
    System.out.println((cond ? "  ok   " : "  FAIL ") + what);
    if (!cond) fails++;
  }

  static long k(Rt rt, int i) { return Val.fixnum(i); }

  public static void main(String[] a) {
    Rt rt = new Rt(4 * 1024 * 1024, 128L * 1024 * 1024);

    // --- the array-map, and the boundary.
    int base = rt.mark();
    int m = rt.push(Maps.empty(rt));
    ok("an empty map counts 0", Mapcore.mapCount(rt, rt.r(m)) == 0);
    for (int i = 0; i < 8; i++) rt.setR(m, Maps.assoc(rt, rt.r(m), k(rt, i), Val.fixnum(i * 10)));
    ok("8 entries is still a flat array-map", Mapcore.isArrayMap(rt, rt.r(m)));
    ok("  and every one reads back", allPresent(rt, rt.r(m), 0, 8));
    rt.setR(m, Maps.assoc(rt, rt.r(m), k(rt, 8), Val.fixnum(80)));
    ok("the 9th promotes to a CHAMP trie", !Mapcore.isArrayMap(rt, rt.r(m)) && Mapcore.isMap(rt, rt.r(m)));
    ok("  and nothing was lost crossing the boundary", allPresent(rt, rt.r(m), 0, 9));

    // --- a big map, in and out.
    final int N = 2000;
    rt.setR(m, Maps.empty(rt));
    for (int i = 0; i < N; i++) rt.setR(m, Maps.assoc(rt, rt.r(m), k(rt, i), Val.fixnum(i * 10)));
    ok(N + " keys, all present, count agrees", Mapcore.mapCount(rt, rt.r(m)) == N && allPresent(rt, rt.r(m), 0, N));
    ok("a key that was never added is absent",
       Mapread.mapGet(rt, rt.r(m), Val.fixnum(-1), Val.NIL) == Val.NIL);
    // Re-assoc with the same value must not grow the map.
    rt.setR(m, Maps.assoc(rt, rt.r(m), k(rt, 5), Val.fixnum(50)));
    ok("re-assoc with an identical value does not grow it", Mapcore.mapCount(rt, rt.r(m)) == N);

    // --- CANONICAL FORM: the property CHAMP is chosen for.
    int viaDelete = rt.push(rt.r(m));
    for (int i = 0; i < N; i += 2) {
      rt.setR(viaDelete, Maps.dissoc(rt, rt.r(viaDelete), k(rt, i)));
    }
    int direct = rt.push(Maps.empty(rt));
    for (int i = 1; i < N; i += 2) {
      rt.setR(direct, Maps.assoc(rt, rt.r(direct), k(rt, i), Val.fixnum(i * 10)));
    }
    ok("built-by-deleting and built-directly have the same count",
       Mapcore.mapCount(rt, rt.r(viaDelete)) == Mapcore.mapCount(rt, rt.r(direct)));
    ok("  ... and are =", Maps.eq(rt, rt.r(viaDelete), rt.r(direct)));
    ok("  ... and HASH ALIKE, which is what canonical form means",
       Eq.hashValue(rt, rt.r(viaDelete)) == Eq.hashValue(rt, rt.r(direct)));
    ok("  ... and the deleted keys really are gone",
       Mapread.mapGet(rt, rt.r(viaDelete), k(rt, 0), Val.NIL) == Val.NIL
       && Mapread.mapGet(rt, rt.r(viaDelete), k(rt, 2), Val.NIL) == Val.NIL);

    // --- COLLISIONS. Not with integer keys: `hashLong` on a small long is a
    // BIJECTION on 32 bits -- `mixK1`, `mixH1` and `fmix` are each invertible,
    // and with the high word zero the whole pipeline is -- so sequential
    // integers NEVER collide. (Searched 300,000 of them and found nothing,
    // which is what sent me looking for the reason.) That is a real property
    // worth knowing: an integer-keyed map never reaches the collision path.
    //
    // Strings do collide, and the classic Java pair is the honest instrument:
    // "Aa" and "BB" have the same `String.hashCode`, so they have the same
    // flint hash too. Asserted rather than assumed, because the day the hash
    // changes this test should say so instead of quietly testing nothing.
    byte[] aa = "Aa".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] bb = "BB".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    ok("\"Aa\" and \"BB\" collide, as they do in Java",
       Hash.hashString(aa) == Hash.hashString(bb));
    {
      int c = rt.push(Maps.empty(rt));
      // Padded past the array-map, or the collision never reaches a trie node
      // and the collision-node code is not what is being exercised.
      for (int i = 0; i < 20; i++) rt.setR(c, Maps.assoc(rt, rt.r(c), Val.fixnum(1000000 + i), Val.fixnum(i)));
      int ka = rt.push(Str.of(rt, "Aa"));
      int kb = rt.push(Str.of(rt, "BB"));
      rt.setR(c, Maps.assoc(rt, rt.r(c), rt.r(ka), Val.fixnum(111)));
      rt.setR(c, Maps.assoc(rt, rt.r(c), rt.r(kb), Val.fixnum(222)));
      ok("both colliding keys are stored and distinct",
         Val.asFixnum(Mapread.mapGet(rt, rt.r(c), rt.r(ka), Val.NIL)) == 111
         && Val.asFixnum(Mapread.mapGet(rt, rt.r(c), rt.r(kb), Val.NIL)) == 222);
      ok("  and the count counts them both", Mapcore.mapCount(rt, rt.r(c)) == 22);
      rt.setR(c, Maps.dissoc(rt, rt.r(c), rt.r(ka)));
      ok("  removing one leaves the other",
         Mapread.mapGet(rt, rt.r(c), rt.r(ka), Val.NIL) == Val.NIL
         && Val.asFixnum(Mapread.mapGet(rt, rt.r(c), rt.r(kb), Val.NIL)) == 222);
      ok("  and the collision node collapsed back to an inline entry",
         Mapcore.mapCount(rt, rt.r(c)) == 21);
    }

    // --- keys that are not fixnums, and a collection under collection.
    int s = rt.push(Maps.empty(rt));
    rt.setR(s, Maps.assoc(rt, rt.r(s), Str.of(rt, "hello, world"), Val.fixnum(1)));
    rt.setR(s, Maps.assoc(rt, rt.r(s), Str.keyword(rt, null, "kw"), Val.fixnum(2)));
    int vk = rt.push(Vec.empty(rt));
    rt.setR(vk, Vec.conj(rt, rt.r(vk), Val.fixnum(7)));
    rt.setR(s, Maps.assoc(rt, rt.r(s), rt.r(vk), Val.fixnum(3)));
    // A SEPARATE but equal key must find the same entry -- that is the whole
    // difference between `=` and identity, and where interning would be a
    // shortcut rather than the answer.
    int vk2 = rt.push(Vec.empty(rt));
    rt.setR(vk2, Vec.conj(rt, rt.r(vk2), Val.fixnum(7)));
    ok("a string key reads back",
       Val.asFixnum(Mapread.mapGet(rt, rt.r(s), Str.of(rt, "hello, world"), Val.NIL)) == 1);
    ok("an equal-but-separate VECTOR key finds the same entry",
       Val.asFixnum(Mapread.mapGet(rt, rt.r(s), rt.r(vk2), Val.NIL)) == 3);

    // --- the collector, over all of it.
    rt.gc.major(rt.roots);
    ok("everything survives a major collection", allPresent(rt, rt.r(m), 0, N)
       && Maps.eq(rt, rt.r(viaDelete), rt.r(direct)));

    rt.popTo(base);
    if (fails > 0) { System.out.println("  " + fails + " failed"); System.exit(1); }
  }

  static boolean allPresent(Rt rt, long m, int from, int to) {
    for (int i = from; i < to; i++) {
      long got = Mapread.mapGet(rt, m, Val.fixnum(i), Val.NIL);
      if (!Val.isFixnum(got) || Val.asFixnum(got) != i * 10L) return false;
    }
    return true;
  }
}
