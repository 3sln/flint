import com.flint.rt.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/// THE HOST, driving a real image through the port protocol.
///
/// `runtimes/conform/hostport.cljc` is the program; this is the other half of
/// it. The native driver (`units-src/flint-conc/src/bin/hostports.rs`) and the
/// CLR's (`--rt-hostports`) run the SAME image through the SAME script, and
/// `bin/conform-hosts` compares the three transcripts.
///
/// That comparison is the whole point. The host protocol is an ABI: a token
/// whose generation must be checked, a byte budget that must be respected in
/// both directions, an event layout with five fields per record. Three
/// runtimes implementing an ABI separately are three ABIs until something makes
/// them say the same thing out loud.
public class RtHostPorts {

  /// One outbound event, decoded.
  record Ev(int kind, int a, int b, byte[] payload) {}

  static String kindName(int k) {
    return switch (k) {
      case Conc.EV_OPEN -> "open";
      case Conc.EV_MESSAGE -> "message";
      case Conc.EV_CLOSED -> "closed";
      default -> "?";
    };
  }

  static String stateName(long s) {
    return switch ((int) s) {
      case Conc.P_PENDING -> "pending";
      case Conc.P_OPEN -> "open";
      case Conc.P_CLOSED -> "closed";
      case Conc.P_HALF -> "half-closed";
      case Conc.P_ORPHANED -> "orphaned";
      case Conc.P_REFUSED -> "refused";
      case 255 -> "unknown";
      default -> "?";
    };
  }

  static int le32(byte[] b, int at) {
    return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8)
         | ((b[at + 2] & 0xFF) << 16) | ((b[at + 3] & 0xFF) << 24);
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

  static String show(java.util.List<Ev> evs) {
    StringBuilder b = new StringBuilder();
    for (Ev e : evs) {
      if (b.length() > 0) b.append(" ");
      String p = e.kind() == Conc.EV_OPEN
        ? render(e.payload())
        : "\"" + new String(e.payload(), StandardCharsets.UTF_8) + "\"";
      b.append(kindName(e.kind())).append("(").append(e.a()).append(",").append(e.b())
       .append(",").append(p).append(")");
    }
    return b.toString();
  }

  /// The open-request payload, rendered.
  ///
  /// It is an ENCODED VALUE rather than a bare name, which is what "the host
  /// does what it wants with the args" means in practice: this driver decodes
  /// it the way any host would, and an opaque value arrives carrying the id the
  /// host issued -- which is the whole of the capability check, done here
  /// rather than by the runtime.
  static int ri;
  static String render(byte[] b) { ri = 0; return one(b); }

  static int u32(byte[] b) {
    int v = (b[ri] & 0xFF) | ((b[ri+1] & 0xFF) << 8)
          | ((b[ri+2] & 0xFF) << 16) | ((b[ri+3] & 0xFF) << 24);
    ri += 4;
    return v;
  }

  static String str(byte[] b) {
    int n = u32(b);
    String s = new String(b, ri, n, StandardCharsets.UTF_8);
    ri += n;
    return s;
  }

  static String one(byte[] b) {
    int t = b[ri++] & 0xFF;
    switch (t) {
      case 0: return "nil";
      case 1: return "true";
      case 2: return "false";
      case 3: { long lo = u32(b) & 0xFFFFFFFFL, hi = u32(b) & 0xFFFFFFFFL;
                return String.valueOf((hi << 32) | lo); }
      case 5: return "\"" + str(b) + "\"";
      case 6: case 7: {
        int save = ri;
        int ns = u32(b);
        String nss = "";
        if (ns != -1) { ri = save; nss = str(b) + "/"; }
        return (t == 6 ? ":" : "") + nss + str(b);
      }
      case 8: case 9: case 11: {
        int n = u32(b);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) { if (i > 0) sb.append(" "); sb.append(one(b)); }
        return sb.append("]").toString();
      }
      case 10: {
        int n = u32(b);
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < n; i++) {
          if (i > 0) sb.append(", ");
          sb.append(one(b)).append(" ").append(one(b));
        }
        return sb.append("}").toString();
      }
      // The one that matters: an opaque value with the id the HOST gave it.
      // A guest-minted one has id 0 and is recognisably not the host's.
      case 16: { long lo = u32(b) & 0xFFFFFFFFL, hi = u32(b) & 0xFFFFFFFFL;
                 long id = (hi << 32) | lo;
                 return "#opaque[\"" + str(b) + "\" id=" + id + "]"; }
      default: return "#tag" + t;
    }
  }

  /// `main`, then the scheduler -- what a host's `run` does.
  static long run(Rt rt, Img.Loaded img) {
    for (int fn : img.init) {
      rt.call(rt.makeClosure(fn, new long[0]), new long[0]);
      if (!Val.isNil(rt.thrown) && !rt.parked()) return Val.NIL;
    }
    // One opaque value PROJECTED IN as the entry's second argument, under an id
    // this driver chose. That is the whole of lending a capability: no grant
    // table, no declaration, and nothing in the runtime that knows what it is
    // for.
    int base = rt.mark();
    int ai = rt.push(Vec.empty(rt));
    int mi = rt.push(Maps.empty(rt));
    int ki = rt.push(Str.keyword(rt, null, "fs"));
    int li = rt.push(Str.of(rt, "fs"));
    int oi = rt.push(rt.newOpaque(rt.r(li), 7));
    rt.setR(mi, Maps.assoc(rt, rt.r(mi), rt.r(ki), rt.r(oi)));
    int vi = rt.push(Vec.empty(rt));
    rt.setR(vi, Vec.conj(rt, rt.r(vi), rt.r(ai)));
    rt.setR(vi, Vec.conj(rt, rt.r(vi), rt.r(mi)));
    long pair = rt.r(vi);
    rt.popTo(base);
    return rt.runProgram(rt.makeClosure(img.entry, new long[0]), new long[]{ pair });
  }

  /// The status a host sees, in the same three values the Rust reports:
  /// 0 finished, 1 threw, 2 parked on the host.
  static int status(Rt rt) {
    if (rt.status != 0) return rt.status;
    return Val.isNil(rt.thrown) ? 0 : 1;
  }

  static String rendered(Rt rt, long v) {
    if (!Val.isNil(rt.thrown)) {
      long e = rt.thrown;
      return Str.text(rt, rt.exKind(e)) + ": " + Str.text(rt, rt.exMessage(e));
    }
    return Str.isString(rt, v) ? Str.text(rt, v) : rt.describe(v);
  }

  public static void main(String[] a) throws Exception {
    Rt rt = new Rt(4L * 1024 * 1024, 512L * 1024 * 1024);
    Img.Loaded img = Img.load(rt, Files.readAllBytes(Path.of(a[0])));
    if (img == null) { System.out.println("  FAIL not a flint image"); System.exit(1); }

    // 1. The program runs until it asks for something only the host has.
    long v = run(rt, img);
    System.out.println("  ok   the program parked on the host: status " + status(rt));
    java.util.List<Ev> evs = java.util.Arrays.asList(drain(rt));
    System.out.println("  ok   it asked: " + show(evs));
    Ev open = evs.stream().filter(e -> e.kind() == Conc.EV_OPEN).findFirst().orElseThrow();
    int token = open.a(), port = open.b();

    // 2. WHAT WAS FORWARDED, decoded. Nothing in the runtime looked at it on
    //    the way past, and nothing in it knows what a capability is.
    System.out.println("  ok   it forwarded: " + render(open.payload()));

    // 3. Grant it. A second answer on the same token is refused: the generation
    //    in it has moved on, so a late or duplicated reply cannot resume a
    //    stranger's thread.
    System.out.println("  ok   the host grants it: " + Conc.hostContinue(rt, token, true));
    System.out.println("  ok   and a duplicate reply is refused: "
                       + Conc.hostContinue(rt, token, true));
    System.out.println("  ok   the runtime end is now: "
                       + stateName(Conc.hostPortState(rt, port)));

    // 4. Push something in, let the program read it and answer.
    System.out.println("  ok   delivered: "
                       + Conc.hostDeliver(rt, port, "one".getBytes(StandardCharsets.UTF_8)));
    v = Conc.resume(rt);
    System.out.println("  ok   ran on: status " + status(rt));
    System.out.println("  ok   it sent back: " + show(java.util.Arrays.asList(drain(rt))));

    // 5. A second wave, then hang up. Drained-and-closed is END OF STREAM --
    //    `nil` and not an error -- and the program's own `state` call has to
    //    agree with what the host sees.
    System.out.println("  ok   delivered: "
                       + Conc.hostDeliver(rt, port, "two".getBytes(StandardCharsets.UTF_8)));
    Conc.hostClosePort(rt, port);
    System.out.println("  ok   after the host hangs up: "
                       + stateName(Conc.hostPortState(rt, port)));
    v = Conc.resume(rt);
    java.util.List<Ev> tail = new java.util.ArrayList<>();
    // The last pump is TWO pumps: exit closes every flint end and pushes an
    // `EV_CLOSED` for each, and a run with events pending comes back 2 -- so a
    // host is never left guessing whether more is coming.
    for (int i = 0; i < 4 && status(rt) == 2; i++) {
      tail.addAll(java.util.Arrays.asList(drain(rt)));
      v = Conc.resume(rt);
    }
    System.out.println("  ok   the program answered: " + rendered(rt, v));
    System.out.println("  ok   status " + status(rt));
    System.out.println("  ok   and was told the port closed: " + show(tail));

    // 6. An id the runtime has never heard of. A host treats this as done,
    //    which is what makes the pushed `:closed` an optimisation over polling
    //    rather than the sole carrier of the truth.
    System.out.println("  ok   an unknown port id: "
                       + stateName(Conc.hostPortState(rt, 999999)));
  }
}
