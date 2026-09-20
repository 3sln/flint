import com._3sln.flint.kgen.rt.Mapwrite;
import com.flint.rt.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/// The REQUEST/RESPONSE half of the host ABI, on the JVM.
///
/// The same script as `units-src/flint-conc/src/bin/hostreq.rs`, run against
/// the same image, so `bin/conform-hosts` can compare the transcripts. See
/// that file for why this path needed a host written for it at all, and why
/// the transcript prints no tokens.
public class RtHostReq {
  record Ev(int kind, int a, int b, byte[] payload) {}

  static final int SYSTEM = 1;

  static int le32(byte[] b, int at) {
    return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8)
         | ((b[at + 2] & 0xFF) << 16) | ((b[at + 3] & 0xFF) << 24);
  }

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

  static long run(Rt rt, Img.Loaded img) {
    // THE RUNTIME'S OWN ONE-SHOT RUNNER, not a loop of our own.
    //
    // This used to hand-roll the loop -- `started = true` and then `call` per
    // initialiser -- which ran them UNDER A LIVE SLICE. A scheduler exists
    // before this is entered whenever the host installed a port first, and a
    // slice is armed the moment a scheduler exists; an initialiser then yields,
    // the yield is discarded here, and the entry's value is never recorded.
    // The symptom is a program whose entry is `(defn main [_] "CONSTANT")`
    // answering nothing at all.
    //
    // `ensureStarted` disarms the slice around the loop for exactly that
    // reason, and it is one-shot, so a control plane spawned later cannot run
    // them a second time (`DECISIONS.md#the-codec-is-guest-code`).
    if (!rt.ensureStarted()) return Val.NIL;
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
    rt.setR(mi, Mapwrite.mapAssoc(rt, rt.r(mi), rt.r(ki), rt.r(oi)));
    int vi = rt.push(Vec.empty(rt));
    rt.setR(vi, Vec.conj(rt, rt.r(vi), rt.r(ai)));
    rt.setR(vi, Vec.conj(rt, rt.r(vi), rt.r(mi)));
    // THE CLOSURE IS MADE WHILE THE PAIR IS STILL ROOTED, and the roots are
    // dropped after the call rather than before it.
    //
    // `makeClosure` ALLOCATES. Reading `pair` out to a host local and then
    // `popTo` left it unrooted across that allocation, so under a collection at
    // every allocation the pair moved and the entry was handed the address it
    // used to have. After the flip that address names to-space, and the next
    // collection traced it and died in `Gc.forward` -- nowhere near here.
    long fn = rt.makeClosure(img.entry, new long[0]);
    int fi = rt.push(fn);
    long out = rt.runProgram(rt.r(fi), new long[]{ rt.r(vi) });
    rt.popTo(base);
    return out;
  }

  static int status(Rt rt) {
    if (rt.status != 0) return rt.status;
    return Val.isNil(rt.thrown) ? 0 : 1;
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

  public static void main(String[] a) throws Exception {
    Rt rt = new Rt(4L * 1024 * 1024, 512L * 1024 * 1024);
    Img.Loaded img = Img.load(rt, Files.readAllBytes(Path.of(a[0])));
    if (img == null) { System.out.println("  FAIL not a flint image"); System.exit(1); }
    Conc.installSystemPort(rt, SYSTEM, Str.of(rt, "system"));

    long v = run(rt, img);
    int seen = 0;
    for (int round = 0; round < 12 && status(rt) == 2; round++) {
      Ev[] evs = drain(rt);
      boolean acted = false;
      for (Ev e : evs) {
        if (e.kind() != Conc.EV_REQUEST) continue;
        seen++;
        String what = render(e.payload());
        System.out.println("  ok   it asked: " + what);
        // `nope` is REFUSED, which is a `SecurityException` on the guest side;
        // everything else is answered with a string.
        if (what.contains("nope")) {
          System.out.println("  ok   refused: " + Conc.hostContinue(rt, e.a(), false));
        } else {
          System.out.println("  ok   answered: "
              + Conc.hostAnswer(rt, e.a(), Codec.encode(rt, Str.of(rt, "tick"))));
        }
        acted = true;
      }
      if (!acted && evs.length == 0) break;
      v = Conc.resume(rt);
    }
    System.out.println("  ok   requests seen: " + seen);
    // AND NOW LET IT FINISH -- see the note in the Rust driver: a sandbox whose
    // control plane is parked is never done, so the entry's value cannot be
    // read while the host still holds the door.
    Conc.hostClosePort(rt, SYSTEM);
    for (int i = 0; i < 8 && status(rt) == 2; i++) {
      drain(rt);
      v = Conc.resume(rt);
    }
    // A FAILED RUN AND AN EMPTY ANSWER WERE THE SAME LINE. `rendered` already
    // reports a throw, and the nil test in front of it meant the one case that
    // most needs reporting never reached it: a run that threw returns nil, so
    // this printed the empty string and the reason was discarded. Measured
    // against `initpark.img`, where the runtime refuses by name and this
    // transcript said nothing at all.
    System.out.println("  ok   the program answered: " + rendered(rt, v));
    System.out.println("  ok   status " + status(rt));
  }

  static String rendered(Rt rt, long v) {
    if (!Val.isNil(rt.thrown)) {
      long e = rt.thrown;
      return Str.text(rt, rt.exKind(e)) + ": " + Str.text(rt, rt.exMessage(e));
    }
    // NIL WITH NOTHING THROWN stays the empty string, which is what this line
    // meant before: a program that answered nothing, rather than one that
    // failed to answer.
    if (Val.isNil(v)) return "";
    return Str.isString(rt, v) ? Str.text(rt, v) : rt.describe(v);
  }
}
