package com.flint.rt;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// THE THREE OPERATIONS, on the JVM (`DECISIONS.md#four-operations`).
///
/// `boot`, `loop`, `link`. The semantics are the record's and are identical on
/// every target; only the spelling is this one's.
///
/// ## `prop` WAS THE FOURTH AND IS GONE
///
/// The metadata is a class ATTRIBUTE on the generated `flint.Artifact`, named
/// `com.3sln.flint.meta` -- the same string the wasm custom section uses. All three
/// container formats carry metadata a reader gets at WITHOUT executing the
/// artifact, and a method call never can. So nothing here answers questions about
/// the artifact: a reader with the class file has the answers already, and one
/// without it was never going to get them from a method.
///
/// Reading it needs no JVM and no class loading -- JVMS 4.7 requires an
/// unrecognised attribute to be silently ignored, which is what makes a custom one
/// legal and inert, and also what makes the namespace load-bearing: a colliding
/// attribute name is not an error, it is wrong data read as right.
///
/// ## The artifact carries the PROGRAM, not the runtime
///
/// "Self-contained" means *contains all of the program's code*, NOT *runs
/// standalone*. This class, the collector and the builtins are the HOST's: an
/// artifact is one class file carrying bytecode and the three operations, and it
/// needs a JVM with flint's runtime on the classpath. Saying it the other way
/// round is the mistake the record warns about first.
///
/// ## Where this differs from wasm, and why that is allowed
///
/// A JVM sandbox can be handed a real OBJECT, so the bridge is one `Bridge` with
/// five methods over a `(port, bytes)` pair -- mirroring the CLR's `TryTake`,
/// `Put`, `Open`, `Answer`, `Closed`. wasm needs fourteen exports because most of
/// them exist only to move bytes across a boundary that cannot pass an array or a
/// callback: `flint_in_alloc` plus `flint_deliver` is `Put` inverted,
/// `flint_drain` plus `flint_events_ptr` plus five-`u32` record parsing is
/// `TryTake`, and `flint_grant`/`flint_answer`/`flint_continue` are `Open` and
/// `Answer` with the return value replaced by a token the host has to keep.
///
/// THE INBOUND DIRECTION IS A PULL, and that is the part worth noticing. The
/// sandbox asks the bridge for the next message rather than the host pushing one
/// in, so the host never needs a handle on the sandbox to deliver -- which is
/// what lets `boot` take one argument and still serve every port the host owns.
/// An earlier draft of this file pushed, and needed a fifth operation to attach
/// each further port to the sandbox first.
public final class Sandbox {

    // --- the bridge --------------------------------------------------------

    /// A message on a port. The `(port, bytes)` pair the five methods work over.
    ///
    /// BYTES, not text: a port's format may be binary (Transit over msgpack is),
    /// so decoding as UTF-8 here would replace whatever is not valid and corrupt
    /// the message.
    public record Msg(int port, byte[] bytes) {}

    /// The host's whole side of the bridge.
    ///
    /// ONE OBJECT FOR EVERY PORT, not one per port: the id travels in the
    /// arguments, which is what `(port, bytes)` means. The id is the HOST's, and
    /// that is the inversion the port design rests on
    /// (`DECISIONS.md#ports-are-the-hosts`) -- the sandbox does not mint an
    /// endpoint and offer it up, so an id means the same thing in every sandbox
    /// holding it.
    public abstract static class Bridge {
        /// The port that becomes this sandbox's SYSTEM port -- the one it can ask
        /// the host for things through. A sandbox given none runs logic and can
        /// ask for nothing, which is a coherent thing to be.
        public final int systemPort;
        public final String label;

        public Bridge(int systemPort, String label) {
            this.systemPort = systemPort;
            this.label = label;
        }

        /// HOST TO GUEST, pulled. The next message the host has for the sandbox,
        /// or null when it has none. `loop` calls this until it answers null.
        protected abstract Msg tryTake();

        /// GUEST TO HOST. The sandbox sent these bytes on that port.
        protected abstract void put(int port, byte[] wire);

        /// The guest called `open` on a capability, on the port it asked through.
        /// Return the host port id to GRANT, or 0 to refuse -- a refusal is a
        /// normal outcome and surfaces in the program as a catchable error.
        ///
        /// A GRANT NAMES A PORT, which is why this answers an id rather than a
        /// boolean: there is no port until the host says which one.
        protected int open(int port, String capability, byte[] args) { return 0; }

        /// The guest asked the host for a value that is not a port
        /// (`DECISIONS.md#workspace-capabilities` step 7). Return the encoded
        /// value, or null to refuse.
        protected byte[] answer(int port, byte[] wire) { return null; }

        /// The guest closed its end of that port.
        protected void closed(int port) {}
    }

    // --- status ------------------------------------------------------------

    /// `loop`'s answers, and THE EXISTING ABI NUMBERS -- this project treats them
    /// as the ABI itself, so they are not re-enumerated here.
    ///
    /// `NEEDS_HOST` IS THE RESTING STATE, not an error. The control plane is a
    /// green thread parked on the system port, so a healthy idle sandbox reports
    /// it.
    /// `SHELVED` and `WEDGED` added 2026-09-25. 3 was already returned by the
    /// runtime (`snap.rs`'s `STATUS_SHELVED`) and declared by no face; 4 is new,
    /// because the scheduler's deadlock branch answered 0 -- the same number a clean
    /// finish answers -- so a host could not tell a settled sandbox from a wedged
    /// one. WEDGED is reachable only for a sandbox with NO system port: one with a
    /// door answers NEEDS_HOST and lets the host decide it has nothing left to send.
    public static final int DONE = 0, THREW = 1, NEEDS_HOST = 2,
                           SHELVED = 3, WEDGED = 4;

    // --- state -------------------------------------------------------------

    /// Overrides waiting for a boot, and whether one has happened.
    ///
    /// STATIC, because `link` has to be callable with no sandbox in hand -- it
    /// must precede `boot`. One sandbox per class loader, which is the same reason
    /// the generated `flint.Artifact` keeps its sandbox in a static field.
    private static final Map<String, Builtins.Fn> OVERRIDES = new LinkedHashMap<>();
    private static Sandbox current;

    private final Rt rt;
    private final Bridge bridge;

    private Sandbox(Rt rt, Bridge bridge) { this.rt = rt; this.bridge = bridge; }

    // --- 1. boot -----------------------------------------------------------

    /// Load the artifact's image and give the sandbox its door.
    ///
    /// `imageChunks` IS PASSED IN: the bytes are constant data on the generated
    /// `flint.Artifact` class, which calls this. Nothing here opens a resource, so
    /// the program is not a file the artifact contains -- it is data the artifact is
    /// made of, and the CLR carries it the same way.
    ///
    /// IT FAILS ON A MISSING NATIVE, naming it. See below.
    ///
    /// ONE PROGRAM FOR THE SANDBOX'S WHOLE LIFE
    /// (`DECISIONS.md#construe-integration-bar`). Booting twice is refused rather
    /// than replacing the image: a second program in the same sandbox would share
    /// a heap with the first.
    public static Sandbox boot(Bridge system, String[] imageChunks) {
        if (current != null) throw new IllegalStateException(
            "this sandbox is already booted: a sandbox is ONE program for its whole life");
        Rt rt = new Rt(NURSERY, HEAP);
        Sandbox box = new Sandbox(rt, system);
        byte[] image = decode(imageChunks);
        // THE `Loaded` IS KEPT NOW. It carries the image's `flags` word, and this
        // line discarded it -- so `FLAG_PERF` was parsed at `Img.java:140`, stored
        // in a field, and read by nobody. See the `compileArities` call below.
        Img.Loaded loaded = Img.load(rt, image);
        if (loaded == null) throw new IllegalStateException(
            "the artifact's image is not a flint image, or is a version this runtime does not"
            + " speak (this runtime reads version " + Img.VERSION + ", got " + image.length
            + " bytes in " + imageChunks.length + " chunk(s))");

        // NATIVES RESOLVE EXACTLY ONCE, here. `Img.load` has just bound each slot
        // through `Builtins.byName` -- the host's own table, which is what "the
        // host carries the runtime" means on this target -- and the overrides
        // `link` collected go on top before anything runs. That is why `link`
        // refuses after this point: there is no second resolution to join.
        //
        // A NAME NOBODY ANSWERS FOR STAYS NULL rather than failing the load. An
        // image imports every builtin its namespaces MENTION, and a program that
        // never calls the missing one runs fine; the failure names it if reached.
        int n = rt.nativeNames == null ? 0 : rt.nativeNames.length;
        java.util.List<String> missing = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // `containsKey`, NOT a null check on `get`. An entry whose value is null
            // means the host declared the native ABSENT, and reading that as "no
            // override" would silently fall back to the host's own -- turning a
            // deliberate declaration into a no-op and making the boot failure
            // untestable.
            if (OVERRIDES.containsKey(rt.nativeNames[i])) {
                rt.natives[i] = OVERRIDES.get(rt.nativeNames[i]);
            }
            if (rt.natives[i] == null) missing.add(rt.nativeNames[i]);
        }
        // BOOT FAILS ON A MISSING NATIVE, and this is a deliberate change away from
        // `Img.load`'s null-tolerance. `Img` leaves a slot null on purpose -- an
        // image imports every builtin its namespaces MENTION, so a program that
        // never calls the missing one runs fine -- and the record chose the other
        // trade: the VERSION says what must be linked, so a gap is a version
        // mismatch and is worth refusing now rather than when something reaches it.
        //
        // A version-matched host carries all 88 a trivial program declares and
        // nothing changes. A host that deliberately TRIMMED its builtins now fails
        // to boot a program it could have run; that is the cost, and it is chosen.
        //
        // THE MESSAGE NAMES THEM, capped, because a host whose table is a version
        // behind is missing a handful and a host built wrong is missing dozens --
        // and a failure that says "8 natives are missing" sends someone reading the
        // wrong file.
        if (!missing.isEmpty()) {
            // `current` STAYS UNSET, so a host can `link` what it was missing and
            // try again -- which is what `FourOps` does to prove this path. It is
            // unset rather than cleared: the guard at the top already refused a
            // second boot, so nothing has been assigned yet, and a `current = null`
            // here would read as cleanup that does nothing.
            int shown = Math.min(missing.size(), 8);
            throw new IllegalStateException(
                "this runtime does not carry " + missing.size() + " of the "
                + n + " natives this program declares: "
                + String.join(", ", missing.subList(0, shown))
                + (missing.size() > shown ? ", ... (" + (missing.size() - shown) + " more)" : "")
                + ". The artifact's version says what has to be linked, so this is a"
                + " version mismatch between it and this host -- read"
                + " com.3sln.flint.meta on the artifact's class for the version it"
                + " was built against.");
        }

        // COMPILED ARITIES, when the image asks for them. `:optimize [perf]` sets
        // `FLAG_PERF` in the image (`src/flint/image.cljc`), and until now nothing
        // on this port acted on it: `AotEmit` is 408 lines that ran only from
        // `runtimes/jvm/test/RtAot.java`, so every artifact was interpreted however
        // it was compiled. The producer's own comment says the case was built for
        // this -- "an image for a PORT has the bit and an empty table, which is
        // exactly the case that could not be expressed before".
        //
        // HERE, because natives are resolved above and nothing has executed yet:
        // `RtAot.java` establishes the order as load, compile, then initialisers,
        // and the initialisers do not run until the first `loop`.
        //
        // `false` for `chunkAll`: it makes EVERY instruction a chunk boundary and
        // is "a bisection handle, not a mode" in `AotPlan`'s own words. The
        // reference producer agrees -- `src/flint/aot.cljc`'s four-argument
        // `compile-arity` delegates with `false`, and `src/flint/bundle.cljc` calls
        // that form.
        //
        // AN ARITY THAT CANNOT BE COMPILED STAYS INTERPRETED, so this cannot fail a
        // boot that would otherwise have worked: `AotEmit.compile` answers null for
        // an opcode it does not know and `compileArities` skips it.
        if ((loaded.flags & Img.FLAG_PERF) != 0) {
            rt.compileArities(false);
        }

        Conc.installSystemPort(rt, system.systemPort, Str.of(rt, system.label));
        current = box;
        return box;
    }

    /// The heap the sandbox boots with.
    ///
    /// FIXED, and not read off the artifact. They were metadata for a while:
    /// `nursery`, `heap` and `gas` rode in the properties so `boot` could apply what
    /// the builder chose. Nothing ever set them, and once `prop` was gone there was
    /// no way for a host to act on them either -- so they were metadata carried and
    /// never read, which is exactly the thing this project has a commit about. When
    /// a program needs its own bounds, they become arguments to `boot` or a second
    /// attribute the HOST reads and applies; a constant here is honest until then.
    static final long NURSERY = 1024 * 1024, HEAP = 64L * 1024 * 1024;

    /// The image, out of the string constants that carry it.
    ///
    /// ONE CHARACTER PER BYTE, so this is a cast and a copy. The class file stores
    /// each character in modified UTF-8 -- one byte for `0x01`..`0x7F`, two for
    /// `0x00` and for `0x80`..`0xFF` -- and `charAt` gives the character back, so
    /// nothing here decodes anything. The chunking exists only because a
    /// `CONSTANT_Utf8_info` length is a `u2`.
    private static byte[] decode(String[] chunks) {
        int n = 0;
        for (String s : chunks) n += s.length();
        byte[] out = new byte[n];
        int at = 0;
        for (String s : chunks) {
            for (int i = 0; i < s.length(); i++) out[at++] = (byte) s.charAt(i);
        }
        return out;
    }

    // --- 2. loop -----------------------------------------------------------

    /// Pump. `DONE`, `THREW` or `NEEDS_HOST`.
    ///
    /// DRAIN, DRIVE, DRAIN AGAIN, which the record is explicit about: the
    /// scheduler reports `NEEDS_HOST` while it is still holding undrained events,
    /// so a pump that skipped the trailing drain would report work it was itself
    /// holding and the host would answer a question nobody had asked yet.
    ///
    /// The inbound pull comes first, so whatever the host queued is in the
    /// sandbox's inboxes before the scheduler looks for something runnable.
    ///
    /// IT DOES NOT RETURN THE ANSWER. A program's answer comes back as a message
    /// on a bridge, because a bridge is the only door
    /// (`DECISIONS.md#bridges-are-the-only-door`) -- and a `loop` that also
    /// answered would only work for programs with a distinguished entry point.
    /// Those do not exist: a caller names the function it wants.
    ///
    /// THE LOOP CONDITION IS PROGRESS, not a spin count.
    /// `sdks/esm/src/guest.js` loops while the code is 2 and gives up at a
    /// million; here the question is whether this turn moved anything, which is
    /// the actual question and needs no ceiling.
    public int loop() {
        for (;;) {
            boolean took = pull();
            boolean before = drain();
            Conc.drive(rt);
            boolean after = drain();
            if (rt.status != NEEDS_HOST) {
                // THE BOUNDARY LOOKS AT `thrown` TO DECIDE STATUS, and
                // `Mainanswer.mainAnswer` is what puts it there: `TH_RESULT`
                // holds the error for a failed thread and the value for a
                // finished one, so the status cannot be read off the answer.
                return Val.isNil(rt.thrown) ? DONE : THREW;
            }
            if (!took && !before && !after) return NEEDS_HOST;
        }
    }

    /// Everything the host has queued, into the sandbox. True if anything moved.
    private boolean pull() {
        boolean any = false;
        for (Msg m = bridge.tryTake(); m != null; m = bridge.tryTake()) {
            Conc.hostDeliver(rt, m.port(), m.bytes());
            any = true;
        }
        return any;
    }

    /// Everything the guest said, to the bridge. True if anything moved.
    ///
    /// The five-`u32` records are unpacked HERE rather than by the host. A wasm
    /// host has to do this itself, over linear memory, at `flint_events_ptr`.
    private boolean drain() {
        Conc.Events evs = Conc.drainEvents(rt);
        if (evs.count() == 0) return false;
        byte[] b = evs.bytes();
        for (int i = 0; i < evs.count(); i++) {
            int rec = i * 20;
            int kind = (int) u32(b, rec), a = (int) u32(b, rec + 4), bb = (int) u32(b, rec + 8);
            int off = (int) u32(b, rec + 12), len = (int) u32(b, rec + 16);
            byte[] payload = new byte[len];
            System.arraycopy(b, off, payload, 0, len);
            switch (kind) {
                case Conc.EV_MESSAGE -> bridge.put(a, payload);
                case Conc.EV_CLOSED -> bridge.closed(a);
                // `bb` is the SYSTEM port the request came out on, not a port
                // made for it: there is no port until the host grants one.
                case Conc.EV_OPEN -> {
                    int granted = bridge.open(bb, capabilityName(payload), payload);
                    if (granted == 0 || !Conc.hostGrant(rt, a, granted)) {
                        Conc.hostContinue(rt, a, false);
                    }
                }
                case Conc.EV_REQUEST -> {
                    byte[] answer = bridge.answer(bb, payload);
                    if (answer == null || !Conc.hostAnswer(rt, a, answer)) {
                        Conc.hostContinue(rt, a, false);
                    }
                }
                // EV_RETAIN / EV_RELEASE are the host's reference count on its own
                // ports. A host sharing a port between sandboxes needs them; this
                // sandbox holds every port it was handed for its whole life, so a
                // count of holders has nothing to say to it.
                default -> { }
            }
        }
        return true;
    }

    // --- 3. link -----------------------------------------------------------

    /// Override the native called `name`.
    ///
    /// AN OVERRIDE MECHANISM AND NOT A WIRING ONE, and the difference is the whole
    /// design. A trivial program's image declares 88 natives -- `clojure.core`'s
    /// reach, not the program's -- against 223 the runtime carries, and a host
    /// makes ZERO `link` calls in the normal case because `Img.load` resolves them
    /// all against `Builtins.byName`. A bulk or resolver API that required all 88
    /// would be actively wrong: a missing native is left NULL rather than refused,
    /// so demanding every one of them would reject programs that run.
    ///
    /// IT MUST PRECEDE `boot` and refuses afterwards, because natives resolve
    /// exactly once when the image loads. Refusing is the point: an override
    /// registered after boot would silently not apply, and a host would be left
    /// with a hook it believed in.
    public static void link(String name, Builtins.Fn fn) {
        if (current != null) throw new IllegalStateException(
            "link(" + name + ") after boot: natives resolve exactly once when the image loads,"
            + " so an override registered now would never apply. Call link before boot.");
        // A NULL `fn` DECLARES THE NATIVE ABSENT, and does not forget the override.
        // That is the only sense it can have now that `boot` fails on a missing
        // native: a host saying "I do not carry this" is saying something, and it is
        // the only way anything can exercise that failure through this surface.
        // Reverting to the host's own is `link(name, Builtins.byName(name))`.
        OVERRIDES.put(name, fn);
    }

    // --- there is no fourth ---
    //
    // `prop(name, buf)` lived here. It answered a metadata property, and the
    // metadata is now a class attribute the CONTAINER carries
    // (`DECISIONS.md#four-operations`) -- so `readProps`, `propLong`, the
    // `name<TAB>value` flattening and the NO-LINKAGE-REPORT tally went with it.
    //
    // What replaced the tally is `boot` failing above. An earlier face grew
    // `prop("natives")` and `prop("natives-unresolved")` so a host with a trimmed
    // runtime could discover a gap before a program reached it; refusing to boot
    // tells it the same thing at the same moment and needs no operation.

    /// The capability an `open` asked for, out of the encoded argument vector.
    ///
    /// NOT A DECODER. The payload is `[name & args]` as one encoded value, and
    /// reading the name properly means the wire codec -- which belongs in an SDK
    /// beside `sdks/esm/src/codec.js`, not in the runtime. This finds the first
    /// string so a host's `open` has something to switch on, and the whole payload
    /// goes with it so a host with a real decoder can ignore this.
    private static String capabilityName(byte[] payload) {
        for (int i = 0; i + 5 <= payload.length; i++) {
            if ((payload[i] & 0xff) != 5) continue;          // Codec.K_STRING
            long n = u32(payload, i + 1);
            if (n <= 0 || i + 5 + n > payload.length) continue;
            return new String(payload, i + 5, (int) n, StandardCharsets.UTF_8);
        }
        return "";
    }

    private static long u32(byte[] b, int at) {
        return (b[at] & 0xffL) | ((b[at + 1] & 0xffL) << 8)
             | ((b[at + 2] & 0xffL) << 16) | ((b[at + 3] & 0xffL) << 24);
    }
}
