import com.flint.rt.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/// A HOST-REQUESTED, STREAMED SNAPSHOT on the JVM runtime
/// (`DECISIONS.md#snapshots`), end to end through a compiled image.
///
/// One source for every runtime: `test/snapstream/snap.cljc`.
/// `cli/src/snapstream_test.rs` is the native sibling and this mirrors its
/// assertions exactly, so a divergence between the two readers is a bug in
/// one of them rather than a difference in what was asked. Driven by hand at
/// the port level -- `HostCall`'s wire writer and `RtHostPorts`'s event
/// draining, not a generated client -- because what is asserted is the
/// protocol itself: which events come out, in which order, carrying which
/// bytes.
///
/// Usage: `java -cp runtimes/jvm/classes RtSnapStream <image.img> [<native.stream>]`.
public class RtSnapStream {
  static int fails = 0;

  static void ok(String what, boolean cond) {
    System.out.println((cond ? "  ok   " : "  FAIL ") + what);
    if (!cond) fails++;
  }

  static final int SYS = 1000, CALLS = 1001, DEST = 1002;

  // --- the writer, matching sdks/esm/src/codec.js and HostCall.java tag for
  // tag -------------------------------------------------------------------

  static final int K_KEYWORD = 6, K_STRING = 5, K_INT = 3, K_MAP = 10,
                    K_VECTOR = 8, K_PORT = 15;
  static final long NO_NS = 0xffffffffL;

  static final class W {
    final ByteArrayOutputStream b = new ByteArrayOutputStream();
    W tag(int x) { b.write(x); return this; }
    W u32(long v) { for (int i = 0; i < 4; i++) b.write((int) ((v >>> (8 * i)) & 0xff)); return this; }
    W i64(long v) { for (int i = 0; i < 8; i++) b.write((int) ((v >>> (8 * i)) & 0xff)); return this; }
    W text(String s) {
      byte[] u = s.getBytes(StandardCharsets.UTF_8);
      u32(u.length); b.write(u, 0, u.length); return this;
    }
    W kw(String n) { return tag(K_KEYWORD).u32(NO_NS).text(n); }
    W str(String s) { return tag(K_STRING).text(s); }
    W num(long n) { return tag(K_INT).i64(n); }
    W port(int id) { return tag(K_PORT).u32(id); }
    W map(int n) { return tag(K_MAP).u32(n); }
    W vec(int n) { return tag(K_VECTOR).u32(n); }
    byte[] done() { return b.toByteArray(); }
  }

  static byte[] portMsg(String op, int port) {
    return new W().map(2).kw("op").kw(op).kw("port").port(port).done();
  }

  /// `{:tx n :op :call :fn "snap/f" :args []}` -- every function this test
  /// calls on `snap` takes no arguments.
  static byte[] callMsg(long tx, String f) {
    return new W().map(4).kw("tx").num(tx).kw("op").kw("call")
                  .kw("fn").str("snap/" + f).kw("args").vec(0).done();
  }

  // --- the reader: enough of the wire format to read what this protocol
  // sends back, not a general decoder -------------------------------------

  /// A keyword or symbol, by namespace and name (both interned as Java
  /// strings, so `.equals` is the wire's equality).
  record Kw(String ns, String name) {
    public String toString() { return (ns != null ? ns + "/" : "") + name; }
  }

  /// `nil`, distinguished from Java's own `null` -- which here means "no such
  /// key" -- exactly as `flint.rt/nil?` is distinguished from an absent map
  /// entry.
  static final Object NIL = new Object() { public String toString() { return "nil"; } };

  static final class Dec {
    final byte[] b; int i = 0;
    Dec(byte[] b) { this.b = b; }
    int u8() { return b[i++] & 0xff; }
    int u32() {
      int v = (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8)
            | ((b[i + 2] & 0xff) << 16) | ((b[i + 3] & 0xff) << 24);
      i += 4; return v;
    }
    long u64() { long lo = u32() & 0xffffffffL, hi = u32() & 0xffffffffL; return lo | (hi << 32); }
    String str() {
      int n = u32();
      if (n == -1) return null;         // NO_NS as a signed int
      String s = new String(b, i, n, StandardCharsets.UTF_8);
      i += n; return s;
    }
    byte[] raw(int n) { byte[] out = Arrays.copyOfRange(b, i, i + n); i += n; return out; }

    Object val() {
      int t = u8();
      switch (t) {
        case 0: return NIL;
        case 1: return Boolean.TRUE;
        case 2: return Boolean.FALSE;
        case 3: return u64();
        case 4: return Double.longBitsToDouble(u64());
        case 5: return str();
        case 6: case 7: { String ns = str(); String name = str(); return new Kw(ns, name); }
        case 8: case 9: case 11: {
          int n = u32();
          List<Object> l = new ArrayList<>();
          for (int k = 0; k < n; k++) l.add(val());
          return l;
        }
        case 10: {
          int n = u32();
          LinkedHashMap<Object, Object> m = new LinkedHashMap<>();
          for (int k = 0; k < n; k++) { Object key = val(); Object v = val(); m.put(key, v); }
          return m;
        }
        case 14: { int n = u32(); return raw(n); }
        case 15: return (long) u32();    // a port id: not inspected further here
        default: throw new RuntimeException("RtSnapStream's reader does not handle wire tag " + t);
      }
    }
  }

  static Object decode(byte[] b) { return new Dec(b).val(); }

  @SuppressWarnings("unchecked")
  static Object mget(Object m, String k) {
    if (!(m instanceof Map)) throw new RuntimeException("not a map: " + m);
    return ((Map<Object, Object>) m).get(new Kw(null, k));
  }

  static boolean isKw(Object v, String name) {
    return v instanceof Kw kw && kw.name().equals(name) && kw.ns() == null;
  }

  static long asLong(Object v) {
    if (!(v instanceof Long)) throw new RuntimeException("not an int: " + v);
    return (Long) v;
  }

  // --- events, as `RtHostPorts` reads them --------------------------------

  record Ev(int kind, int a, int b, byte[] payload) {}

  static int le32(byte[] b, int at) {
    return (b[at] & 0xff) | ((b[at + 1] & 0xff) << 8)
         | ((b[at + 2] & 0xff) << 16) | ((b[at + 3] & 0xff) << 24);
  }

  static Ev[] drain(Rt rt) {
    Conc.Events ev = Conc.drainEvents(rt);
    byte[] buf = ev.bytes();
    int n = ev.count();
    Ev[] out = new Ev[n];
    for (int i = 0; i < n; i++) {
      int r = i * 20;
      int off = le32(buf, r + 12), len = le32(buf, r + 16);
      byte[] p = new byte[len];
      System.arraycopy(buf, off, p, 0, len);
      out[i] = new Ev(le32(buf, r), le32(buf, r + 4), le32(buf, r + 8), p);
    }
    return out;
  }

  /// Pump until nothing more happens, collecting every event. Mirrors
  /// `cli/src/snapstream_test.rs`'s `pump`.
  static List<Ev> pump(Rt rt) {
    List<Ev> all = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      rt.resume();
      Ev[] evs = drain(rt);
      if (evs.length == 0) break;
      all.addAll(Arrays.asList(evs));
    }
    return all;
  }

  static Rt freshRt(byte[] img) {
    Rt rt = new Rt(4L * 1024 * 1024, 512L * 1024 * 1024);
    Img.load(rt, img);
    return rt;
  }

  /// A sandbox with its system port installed and a call port bound.
  static Rt booted(byte[] img) {
    Rt rt = freshRt(img);
    Conc.installSystemPort(rt, SYS, Str.of(rt, "system"));
    Conc.hostDeliver(rt, SYS, portMsg("bind", CALLS));
    pump(rt);
    return rt;
  }

  /// Call `snap/<f>` on the bound call port, answering its `:value`.
  static Object call(Rt rt, long tx, String f) {
    if (!Conc.hostDeliver(rt, CALLS, callMsg(tx, f))) {
      throw new RuntimeException("the call port would not take the call");
    }
    for (Ev e : pump(rt)) {
      if (e.kind() == Conc.EV_MESSAGE && e.a() == CALLS) {
        Object v = decode(e.payload());
        Object txv = mget(v, "tx");
        if (txv instanceof Long && (Long) txv == tx) {
          if (!((Map<?, ?>) v).containsKey(new Kw(null, "value"))) {
            throw new RuntimeException("threw: " + v);
          }
          return mget(v, "value");
        }
      }
    }
    throw new RuntimeException("no answer to " + f);
  }

  record Request(List<byte[]> chunks, Object end, boolean closed) {}

  /// Ask for a snapshot on the system port; answer the chunks, the
  /// terminator, and whether the destination was closed after it.
  static Request request(Rt rt) {
    Conc.hostDeliver(rt, SYS, portMsg("snapshot", DEST));
    List<byte[]> chunks = new ArrayList<>();
    Object end = null;
    boolean closed = false;
    for (Ev e : pump(rt)) {
      if (e.a() != DEST) continue;
      if (e.kind() == Conc.EV_CLOSED) {
        closed = true;
      } else if (e.kind() == Conc.EV_MESSAGE) {
        if (closed) throw new RuntimeException("a message arrived after the close");
        Object v = decode(e.payload());
        if (v instanceof byte[]) {
          if (end != null) throw new RuntimeException("a chunk arrived after the terminator");
          chunks.add((byte[]) v);
        } else {
          end = v;
        }
      }
    }
    return new Request(chunks, end, closed);
  }

  static byte[] concat(List<byte[]> chunks) {
    int total = 0;
    for (byte[] c : chunks) total += c.length;
    byte[] out = new byte[total];
    int at = 0;
    for (byte[] c : chunks) { System.arraycopy(c, 0, out, at, c.length); at += c.length; }
    return out;
  }

  public static void main(String[] a) throws Exception {
    byte[] img = Files.readAllBytes(Path.of(a[0]));

    // --- the main round trip ----------------------------------------------
    Rt rtA = booted(img);
    ok("bump -> 1", asLong(call(rtA, 1, "bump")) == 1);

    Request req = request(rtA);
    byte[] stream = concat(req.chunks());
    if (System.getenv("FLINT_SNAPSTREAM_OUT") != null) {
      Files.write(Path.of(System.getenv("FLINT_SNAPSTREAM_OUT")), stream);
    }
    ok("the ballast should need several chunks, got " + req.chunks().size(),
       req.chunks().size() >= 2);
    boolean allFullButLast = true;
    for (int i = 0; i < req.chunks().size() - 1; i++) {
      if (req.chunks().get(i).length != 65536) allFullButLast = false;
    }
    ok("every chunk but the last is full", allFullButLast);
    ok("terminator: op end (" + req.end() + ")", isKw(mget(req.end(), "op"), "end"));
    ok("the terminator names the byte count",
       asLong(mget(req.end(), "size")) == stream.length);
    ok("the destination is closed after the terminator", req.closed());

    // THE INSTANCE ASKED carries on: the request did not stop it.
    ok("the instance asked carries on: bump -> 2", asLong(call(rtA, 2, "bump")) == 2);

    // THE STREAM IS THE ONE-SHOT EXPORT. A fresh instance takes the
    // concatenation, and the one-shot export of what it took is the same
    // bytes.
    Rt c = freshRt(img);
    ok("import accepted the concatenated stream", Snap.importLive(c, stream));
    byte[] oneShot = Snap.exportLive(c);
    ok("the one-shot export did not refuse", oneShot != null);
    ok("streamed " + stream.length + " bytes, one-shot "
       + (oneShot == null ? -1 : oneShot.length) + " bytes, and they are equal",
       oneShot != null && Arrays.equals(oneShot, stream));

    // THE COPY CARRIES ON FROM THE SNAPSHOT, not from where `rtA` is now, and
    // does NOT stream itself again to a port that belonged to `rtA`.
    Rt b = freshRt(img);
    ok("a fresh copy accepts the stream", Snap.importLive(b, stream));
    List<Ev> evs = pump(b);
    boolean streamedAgain = false;
    for (Ev e : evs) {
      if (e.a() == DEST && (e.kind() == Conc.EV_MESSAGE || e.kind() == Conc.EV_CLOSED)) {
        streamedAgain = true;
      }
    }
    ok("the copy did not stream itself again: " + evs, !streamedAgain);
    ok("the copy's counter is the snapshot's: bump -> 2", asLong(call(b, 3, "bump")) == 2);
    ok("sizes -> 30000", asLong(call(b, 4, "sizes")) == 30000);

    // And the copy's control plane answers a snapshot request of its own.
    Request req2 = request(b);
    ok("the copy could not be snapshotted: " + req2.end(),
       !req2.chunks().isEmpty() && req2.closed());

    // Another layout and another program are refused by name.
    Rt d = freshRt(img);
    ok("a truncated stream is refused",
       !Snap.importLive(d, Arrays.copyOfRange(stream, 0, 8)));

    // --- the guest-bypass probe --------------------------------------------
    Rt e = booted(img);
    String[] stealFns = {"steal", "steal-chunk", "steal-by-value", "steal-on-a-thread"};
    for (int k = 0; k < stealFns.length; k++) {
      Object v = call(e, k + 1, stealFns[k]);
      ok(stealFns[k] + ": refused (" + v + ")",
         v instanceof String s && s.contains("guest code cannot take one"));
    }
    // A `:snapshot` the GUEST sends on the system port goes OUT, to the host:
    // it is an ordinary message there, and nothing is exported.
    if (!Conc.hostDeliver(e, CALLS, callMsg(5, "ask-on-the-system-port"))) {
      throw new RuntimeException("the call port would not take ask-on-the-system-port");
    }
    List<Ev> evs2 = pump(e);
    boolean leftOnSys = false, bytesAnywhere = false;
    for (Ev ev2 : evs2) {
      if (ev2.kind() == Conc.EV_MESSAGE && ev2.a() == SYS) leftOnSys = true;
      if (ev2.kind() == Conc.EV_MESSAGE && decode(ev2.payload()) instanceof byte[]) bytesAnywhere = true;
    }
    ok("the guest's message should have left on the system port", leftOnSys);
    ok("a chunk was NOT sent for a guest's request", !bytesAnywhere);

    // THE CONTROL: the same builtin, asked for by the HOST, does run.
    Request req3 = request(e);
    ok("the host's request was not refused too: " + req3.end(),
       !req3.chunks().isEmpty() && req3.closed());

    // --- cross-runtime: a JVM Rt imports what NATIVE streamed --------------
    if (a.length > 1) {
      byte[] nativeStream = Files.readAllBytes(Path.of(a[1]));
      Rt f = freshRt(img);
      ok("a fresh JVM runtime imports native's stream", Snap.importLive(f, nativeStream));
      ok("the copy from native's stream answers bump -> 2", asLong(call(f, 1, "bump")) == 2);
      System.out.println("  info JVM's own stream == native's stream byte for byte: "
                          + Arrays.equals(stream, nativeStream)
                          + " (jvm " + stream.length + " bytes, native " + nativeStream.length
                          + " bytes -- they may differ in intern tables)");
    }

    if (fails > 0) { System.out.println("  " + fails + " failed"); System.exit(1); }
  }
}
