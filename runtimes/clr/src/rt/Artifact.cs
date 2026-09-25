namespace Flint.Rt;

using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Text;

/// THE THREE OPERATIONS, on the CLR: `Boot`, `Loop`, `Link`
/// (`DECISIONS.md#four-operations`, whose slug outlived the fourth).
///
/// `Prop` was the fourth and is gone. Metadata is not an operation on any target
/// -- it lives in the container, where a reader gets at it without executing
/// anything. On the CLR that is `_3sln.Flint.MetaAttribute`.
///
/// ## AN INSTANCE, and why it had to stop being static
///
/// This was a static class for a day: `static void Boot`, `static Status Loop`,
/// static `rt`. That is ONE SANDBOX PER LOAD CONTEXT, and nothing about it said
/// so -- a host that booted a second sandbox would find its `Loop` driving the
/// first one's scheduler, with no error anywhere. The contract requires many
/// sandboxes each holding one program, and concurrent `loop`, and a static
/// surface cannot have either.
///
/// So `Boot` is a FACTORY and `Loop` belongs to what it returns. The JVM face
/// reached the same shape independently, which is better evidence than either
/// side arguing for it.
///
/// ## Where `link` goes, and why it takes the bridge
///
/// `link` MUST precede `boot`: `Img.Load` resolves every native by name exactly
/// once, while the image loads, and the load is inside `Boot`. So a hook cannot
/// be registered on the object `Boot` returns -- by then it is too late, and an
/// instance method that always refused would be a worse lie than no method.
///
/// It therefore takes the BRIDGE, which is the sandbox's identity before the
/// sandbox exists. That is not a workaround: a sandbox IS a program plus the
/// bridge it answers on, and the bridge is the only thing a caller holds at the
/// moment it wants to name hooks. The two alternatives were a builder type -- a
/// fourth noun for a three-operation contract -- and process-global registration,
/// which is what the static version did and which cannot give two sandboxes
/// different hooks at all.
///
/// Hooks are keyed per bridge, consumed by `Boot`, and resolved through
/// `Rt.nativeResolver` so they never reach the static `Builtins` table.
public sealed class Artifact {

    // --- the host's end of the system bridge -------------------------------
    //
    // ONE OBJECT, NOT TWELVE EXPORTS. The wasm face of this is
    // `flint_install_port`, `flint_deliver`, `flint_drain`, `flint_resume`,
    // `flint_continue`, `flint_close`, `flint_port_state`, `flint_in_alloc`,
    // `flint_grant`, `flint_answer`, `flint_system_port`, `flint_events_ptr`,
    // plus `out_ptr`/`out_len` -- fourteen, most of which exist only because
    // wasm cannot pass a byte array or a callback across the boundary. The CLR
    // can pass both, so the same protocol is one interface with five methods,
    // and the host holds a real object rather than minting integer ids into
    // linear memory.
    //
    // The direction of every method is from the ARTIFACT'S point of view: the
    // artifact calls into the host. The host's own direction -- writing to the
    // bridge -- needs no operation here, because the host owns the object it
    // passed to `Boot` and writes into it directly, then calls `Loop`.

    /// One message on one port.
    public readonly record struct Msg(long Port, byte[] Bytes);

    public interface IBridge {
        /// Host -> guest. The artifact drains this until it is empty at the top
        /// of every `Loop`. `false` means nothing is waiting.
        ///
        /// A QUEUE THE HOST OWNS, which is the whole reason there is no
        /// `deliver` operation: back-pressure is the host holding the message
        /// and offering it again on the next `Loop`, and `TryTake` returning
        /// `false` is indistinguishable from a host that chose to wait.
        bool TryTake(out Msg m);

        /// Guest -> host. Wire-encoded, always; the host decodes.
        void Put(Msg m);

        /// The guest asked to open a named port. Return the host port id that
        /// serves it, or -1 to refuse. `request` is the wire-encoded argument.
        ///
        /// NOT A BOOLEAN. "Yes" is not expressible without naming a port,
        /// because until the host says which port there is none
        /// (`DECISIONS.md#ports-are-the-hosts`).
        long Open(long token, byte[] request);

        /// The guest asked the host a question whose answer is a value.
        /// Wire-encoded answer, or null to refuse.
        byte[] Answer(long token, byte[] request);

        /// A port the host held has been closed from the guest's side.
        void Closed(long port);
    }

    /// What `Loop` answers.
    ///
    /// THE NUMBERS ARE THE ABI, not these names. They are `flint_resume`'s and
    /// `rt.status`'s, unchanged. The JVM face spells them `static final int DONE,
    /// THREW, NEEDS_HOST`, and that is NOT a divergence -- an enum is how .NET
    /// spells three named constants, and forcing either language into the other's
    /// spelling would be the mistake. The contract asserts the numbers.
    public enum Status {
        /// Nothing left to run. The program is over.
        Done = 0,
        /// It threw.
        Threw = 1,
        /// Parked on the host. THE RESTING STATE, not an error and not progress
        /// remaining: the control plane is a green thread parked on the system
        /// port, so a live sandbox with nothing to do reports this. The host
        /// writes to the bridge and calls `Loop` again.
        NeedsHost = 2,
        /// Shelved: the sandbox exported itself and stopped. ALREADY RETURNED by
        /// the runtime (`snap.rs`'s `STATUS_SHELVED`) and declared by no face until
        /// 2026-09-25, so every face agreed on three numbers while the runtime had
        /// four -- and the contract they were all checked against agreed too.
        Shelved = 3,
        /// Wedged: nothing runnable and nothing a host can supply, because the
        /// threads that remain are waiting on each other.
        ///
        /// NOT `Done`, which is what the scheduler answered until 2026-09-25 -- so
        /// "every thread settled" and "every thread is waiting on another" were the
        /// same number and a host could only tell them apart by reading a
        /// diagnostic string.
        ///
        /// REACHABLE ONLY WITHOUT A SYSTEM PORT: a sandbox with a door answers
        /// `NeedsHost` and lets the host decide it has nothing left to send, which
        /// is correct. A sandbox given no bridge runs logic and can ask for nothing,
        /// and that is the one that can be wedged.
        Wedged = 4,
    }

    /// The host port id of the system bridge. The host picks ids for every other
    /// port; this one is fixed so that `Boot` needs no argument for it.
    public const long SystemPort = 1;

    // --- link ---------------------------------------------------------------

    /// Hooks recorded for a bridge that has not booted yet, and which bridges
    /// have. CONCURRENT, because two threads booting two sandboxes is the case
    /// this whole rewrite exists to allow.
    static readonly ConcurrentDictionary<IBridge, Dictionary<string, Builtins.Fn>> pending = new();
    static readonly ConcurrentDictionary<IBridge, Artifact> booted = new();

    /// Wire a runtime hook in by name, for the sandbox `bridge` will serve.
    ///
    /// The hook's shape is WIRE BYTES IN, WIRE BYTES OUT, and that is the whole
    /// reason it is usable: a host that had to supply a `Builtins.Fn` would be
    /// handing over a function that reads NaN-boxed values off the interpreter's
    /// value stack, which is not a contract to put in front of anyone. Bytes are
    /// the encoding the system port already uses.
    ///
    /// BEFORE `Boot`, ALWAYS. `Img.Load` resolves natives by name once, so a hook
    /// wired in afterwards is never reached. `Link` must be called before `Boot`,
    /// and refuses afterwards rather than doing nothing quietly.
    public static void Link(IBridge bridge, string name, Func<byte[], byte[]> fn) {
        if (fn == null) throw new ArgumentNullException(nameof(fn));
        LinkRaw(bridge, name, (r, at, argc) => {
            // ONE ARGUMENT. A spike limit, not a design: encoding N arguments
            // means building a vector on the guest heap first, and the rooting
            // that needs is real work this does not do yet.
            byte[] said = Codec.Encode(r, argc > 0 ? r.VAt(at) : Val.Nil);
            byte[] got = fn(said);
            return got == null ? Val.Nil : Codec.Decode(r, got);
        });
    }

    /// The raw form, for a host that does reference this assembly and wants the
    /// interpreter's own calling convention. Same ordering rule.
    public static void LinkRaw(IBridge bridge, string name, Builtins.Fn fn) {
        if (bridge == null) throw new ArgumentNullException(nameof(bridge));
        if (name == null) throw new ArgumentNullException(nameof(name));
        if (booted.ContainsKey(bridge)) {
            throw new InvalidOperationException(
                "flint: `Link` must be called before `Boot`. An image resolves its natives by\n" +
                "name once, while it loads, so a hook wired in afterwards is never reached.");
        }
        pending.GetOrAdd(bridge, _ => new Dictionary<string, Builtins.Fn>())[name] = fn;
    }

    /// The same, taking `object`, for a caller that cannot name either delegate
    /// type in a signature.
    ///
    /// THIS EXISTS FOR A WRITER, NOT FOR A HOST. A generated artifact's `link`
    /// forwards to this because `Func<byte[],byte[]>` is a GENERIC INSTANTIATION,
    /// and spelling one in metadata needs a `TypeSpec` row and a `GENERICINST`
    /// signature that `flint.clr` does not emit yet. `object` needs neither.
    public static void Link(IBridge bridge, string name, object fn) {
        if (fn is Func<byte[], byte[]> wire) { Link(bridge, name, wire); return; }
        if (fn is Builtins.Fn raw) { LinkRaw(bridge, name, raw); return; }
        throw new ArgumentException(
            "flint: `link` takes a Func<byte[],byte[]> (wire bytes in, wire bytes out) or a\n" +
            "Flint.Rt.Builtins.Fn (the interpreter's own convention). Got " +
            (fn?.GetType().ToString() ?? "null") + ".");
    }

    // --- boot ---------------------------------------------------------------

    readonly Rt rt;
    readonly IBridge bridge;
    readonly Img.Loaded loaded;
    Msg? pushback;

    Artifact(Rt rt, IBridge bridge, Img.Loaded loaded) {
        this.rt = rt; this.bridge = bridge; this.loaded = loaded;
    }

    /// The live runtime, for a host that wants more than these three operations.
    public Rt Runtime => rt;

    /// How many natives the image imported. A FACT ABOUT THE IMAGE, not a
    /// linkage report -- `Boot` already refused if any of them were missing.
    public int ImportedNatives => loaded.nativeNames.Length;

    /// Instantiate `image` and give it `bridge` as its system port. A FACTORY:
    /// what comes back is the sandbox, and `Loop` is its.
    public static Artifact Boot(IBridge bridge, byte[] image) {
        if (bridge == null) throw new ArgumentNullException(nameof(bridge));
        if (image == null) throw new ArgumentNullException(nameof(image));
        if (booted.ContainsKey(bridge)) {
            throw new InvalidOperationException(
                "flint: this bridge has already booted a sandbox. A sandbox is ONE program\n" +
                "for its whole life; give a second program its own bridge.");
        }

        // The hooks `Link` recorded for this bridge, consulted BEFORE the static
        // table so an override wins, and never written INTO it so one sandbox's
        // hooks cannot reach another's.
        pending.TryRemove(bridge, out var hooks);

        // The sizes the conformance harness uses for the host-port tests. Not
        // tuned, and a host cannot yet say.
        var rt = new Rt(4L * 1024 * 1024, 512L * 1024 * 1024);
        if (hooks != null && hooks.Count > 0) {
            rt.nativeResolver = n => hooks.TryGetValue(n, out var f) ? f : Builtins.ByName(n);
        }
        var loaded = Img.Load(rt, image);
        if (loaded == null) {
            throw new InvalidOperationException(
                "flint: the embedded bytes are not a flint image, or are a version this " +
                "runtime does not read (this runtime reads version " + Img.Version + ")");
        }

        // A MISSING NATIVE FAILS THE BOOT. This is a DELIBERATE DEPARTURE from
        // `Img.Load`, which leaves an unresolved builtin null on purpose and says
        // why in its own comment: an image imports every builtin its NAMESPACES
        // mention -- 88 for a trivial program, by reaching `clojure.core` -- and a
        // program that never calls the missing one runs fine.
        //
        // `four-operations` overrides that here and names the trade rather than
        // hiding it: the VERSION says what must be linked, so a missing native
        // means a version mismatch. A version-matched host carries all 223 and
        // nothing changes; a host that deliberately TRIMMED its builtin set now
        // fails to boot a program it could have run. That is the cost, chosen.
        //
        // The alternative was a tally the host could query, dropped because nobody
        // reads a tally -- a gap would still be found when a program reached it,
        // only later and with a number to ignore first.
        //
        // The message NAMES the natives. "A builtin is missing" sends a reader to
        // the wrong place; `flint/fabs` sends them to the right one.
        var missing = new List<string>();
        for (int i = 0; i < loaded.nativeNames.Length; i++) {
            if (rt.natives[i] == null) missing.Add(loaded.nativeNames[i]);
        }
        if (missing.Count > 0) {
            missing.Sort();
            const int show = 12;
            string names = string.Join(", ", missing.GetRange(0, Math.Min(show, missing.Count)));
            if (missing.Count > show) names += ", and " + (missing.Count - show) + " more";
            throw new InvalidOperationException(
                "flint: this host cannot supply " + missing.Count + " of the " +
                loaded.nativeNames.Length + " natives this artifact imports: " + names + ".\n" +
                "The artifact's version says what has to be linked, so this is a version\n" +
                "mismatch or a trimmed runtime. `link` a replacement for each before `Boot`,\n" +
                "or run it on a host built from the same version.");
        }

        // COMPILED ARITIES, when the image asks for them. `:optimize [perf]` sets
        // `FlagPerf` in the image (`src/flint/image.cljc`), and until now nothing on
        // this port acted on it: `AotEmit.cs` is 498 lines that ran only from
        // `runtimes/clr/conform/Program.cs`, so every artifact was interpreted
        // however it was compiled. The producer's own comment says the case exists
        // for this -- "an image for a PORT has the bit and an empty table, which is
        // exactly the case that could not be expressed before".
        //
        // BEFORE `EnsureStarted` BELOW, which runs the initialisers.
        // `runtimes/jvm/test/RtAot.java` establishes the order as load, compile,
        // then initialisers, and this is the last point before they run.
        //
        // `false` for `chunkAll`: it makes EVERY instruction a chunk boundary and is
        // "a bisection handle, not a mode" in `AotPlan`'s own words. The reference
        // producer agrees -- `src/flint/aot.cljc`'s four-argument `compile-arity`
        // delegates with `false`.
        //
        // AN ARITY THAT CANNOT BE COMPILED STAYS INTERPRETED, so this cannot fail a
        // boot that would otherwise have worked.
        if ((loaded.flags & Img.FlagPerf) != 0) {
            rt.CompileArities(false);
        }

        var a = new Artifact(rt, bridge, loaded);
        // BEFORE ANYTHING RUNS. `InstallSystemPort` is what makes
        // `BootSystemThreadOnce` spawn the control plane, and a sandbox given its
        // door late has already decided it has none.
        Conc.InstallSystemPort(rt, SystemPort, Str.Of(rt, "system"));
        rt.EnsureStarted();
        booted[bridge] = a;
        return a;
    }

    // --- loop ---------------------------------------------------------------

    /// Pump: give the guest everything the host wrote, run until there is no more
    /// work, hand back everything the guest produced.
    ///
    /// DRAIN, THEN RUN, THEN DRAIN. The trailing drain is not tidiness: the
    /// scheduler reports `NeedsHost` when it has undrained events, so a pump that
    /// ran without draining afterwards would report work remaining that it was
    /// itself holding.
    public Status Loop() {
        while (bridge.TryTake(out var m)) {
            // FALSE IS NOT AN ERROR -- it is the guest's buffer being full. The
            // message goes back to the host, which will offer it again.
            if (!Conc.HostDeliver(rt, m.Port, m.Bytes)) { pushback = m; break; }
        }

        long guard = 0;
        for (;;) {
            Conc.Drive(rt);
            if (!Pass()) break;
            if (++guard > 1_000_000) {
                throw new InvalidOperationException("flint: the host pump made no progress");
            }
            if (Now() != Status.NeedsHost) break;
        }
        Pass();
        return Now();
    }

    /// The message the last `Loop` could not deliver, if it hit back-pressure.
    public Msg? Pending => pushback;

    /// Drain one round of events to the host. Returns whether anything moved,
    /// which is what tells `Loop` whether another drive can do more.
    bool Pass() {
        var evs = Conc.DrainEvents(rt);
        if (evs.Count == 0) return false;
        for (int i = 0; i < evs.Count; i++) {
            int at = i * 20;
            long kind = Word(evs.Bytes, at);
            long a = Word(evs.Bytes, at + 4);
            long off = Word(evs.Bytes, at + 12);
            long len = Word(evs.Bytes, at + 16);
            byte[] payload = new byte[len];
            Array.Copy(evs.Bytes, (int) off, payload, 0, (int) len);

            if (kind == Conc.EV_MESSAGE) {
                bridge.Put(new Msg(a, payload));
            } else if (kind == Conc.EV_OPEN) {
                long port = bridge.Open(a, payload);
                if (port < 0) Conc.HostContinue(rt, a, false);
                else Conc.HostGrant(rt, a, port);
            } else if (kind == Conc.EV_REQUEST) {
                byte[] answer = bridge.Answer(a, payload);
                if (answer == null) Conc.HostContinue(rt, a, false);
                else Conc.HostAnswer(rt, a, answer);
            } else if (kind == Conc.EV_CLOSED) {
                bridge.Closed(a);
                Conc.HostClosePort(rt, a);
            }
            // EV_RETAIN and EV_RELEASE are the collector's business and need no
            // host reply; they are deliberately not surfaced.
        }
        return true;
    }

    Status Now() {
        int s = rt.status != 0 ? rt.status : (Val.IsNil(rt.thrown) ? 0 : 1);
        return (Status) s;
    }

    static long Word(byte[] b, int at) {
        long v = 0;
        for (int i = 3; i >= 0; i--) v = (v << 8) | (b[at + i] & 0xffL);
        return v;
    }

    // --- metadata is NOT an operation ---------------------------------------
    //
    // `prop(name, buf)` WAS the fourth operation and is GONE (`four-operations`,
    // 2026-09-24). It answered a metadata property by scanning `pr-str` output,
    // and everything about it was the wrong shape:
    //
    //   * metadata must be readable WITHOUT EXECUTING the artifact, and a call is
    //     execution by definition;
    //   * all three container formats already carry namespaced metadata a reader
    //     gets at from outside -- a wasm custom section, a JVM class attribute,
    //     and on the CLR a `CustomAttribute` row;
    //   * the wasm face proved the cost. A module cannot read its own custom
    //     sections, so `prop` there needed a SECOND copy of the metadata spliced
    //     into linear memory, a descriptor to find it, and ~100 lines of Rust
    //     scanning EDN with balanced-delimiter counting -- plus a gate asserting
    //     the two copies agreed. Written, measured, reverted the same day.
    //
    // So the CLR answer is `_3sln.Flint.MetaAttribute`, and a reader does
    //
    //     var m = asm.GetCustomAttribute<_3sln.Flint.MetaAttribute>().Edn;
    //
    // with no metadata reader, no EDN scanner here, and nothing booted.
    //
    // `prop("natives")` and `prop("natives-unresolved")` went with it. The version
    // says what has to be linked, so `Boot` FAILS on a missing native rather than
    // handing back a tally -- see `Boot`.
}
