namespace Flint.Rt;

/// One flint call, ported from `runtime/src/vm.rs`.
///
/// Deliberately does NOT cache the closure. It used to, in the Rust, and that
/// copy was a root the collector could not see: after a collection moved the
/// closure, `Upval` and `Self` read a stale address. `Stack[RetTo]` IS this
/// frame's closure until it returns.
public sealed class Frame {
    public int Fp, Ip, End, RetTo, Handlers;

    /// Index into `Rt.aot`, or `Aot.NONE`. Set by `Enter` from the arity it
    /// SELECTED, so the whole AOT question is one field on the frame rather
    /// than a lookup keyed on something the frame does not carry.
    public int AotIdx = Aot.NONE;
    /// Re-enter compiled code when `Ip` reaches this, at `AotBlock`. Every
    /// re-entry point in the design funnels through this one comparison.
    public int AotIp = Aot.NEVER;
    public int AotBlock;
}
