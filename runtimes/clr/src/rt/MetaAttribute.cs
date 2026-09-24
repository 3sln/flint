namespace _3sln.Flint;

using System;

/// What a flint CLR artifact says about itself, carried where a reader can get
/// at it WITHOUT EXECUTING ANYTHING (`DECISIONS.md#four-operations`).
///
/// A reader does
///
///     var edn = asm.GetCustomAttribute&lt;_3sln.Flint.MetaAttribute&gt;().Edn;
///
/// and that is the whole mechanism: no metadata reader, no EDN scanner in the
/// runtime, nothing booted. `System.Reflection.Metadata` is not needed either --
/// `GetCustomAttribute` is on `Assembly` itself. For a reader that will not load
/// the assembly at all, `CustomAttributeData.GetCustomAttributes(asm)` gets the
/// string without ever constructing this object.
///
/// ## Why this replaced an operation
///
/// `prop(name, buf)` used to be the fourth operation and answered exactly this
/// question by scanning `pr-str` output. It was removed because metadata that
/// can only be reached by CALLING the artifact cannot be read before deciding
/// WHETHER to load it, which is the one thing a runner needs it for.
///
/// The wasm face is what settled it: a wasm module cannot read its own custom
/// sections, so `prop` there required a second copy of the metadata spliced into
/// linear memory, a descriptor to find it, ~100 lines of EDN scanning in Rust,
/// and a gate to assert the two copies agreed. All to answer from inside what
/// the container answers from outside.
///
/// ## Why the namespace looks like this
///
/// `_3sln.Flint`, reverse-DNS, matching `@3sln/flint` on npm and
/// `com._3sln.flint` on the JVM. CUSTOM ATTRIBUTE TYPES SHARE ONE FLAT
/// NAMESPACE with everything else loaded in the process, and the JVM's
/// equivalent hazard is worse still -- JVMS 4.7 REQUIRES an unrecognised class
/// attribute to be silently ignored, so a collision there is quiet. A bare
/// `FlintMeta` would be a name anyone could also pick; this one is not. The
/// leading underscore is C#'s rule, not a decision: an identifier cannot begin
/// with a digit.
///
/// ## The value is EDN, deliberately
///
/// The same `pr-str` of the map `src/flint/modmeta.cljc` builds that every other
/// target carries, byte for byte. Not a set of typed attribute properties,
/// because then the CLR would carry a DIFFERENT metadata shape from wasm and the
/// JVM and the three could drift without anything noticing. One producer, one
/// string, three containers.
[AttributeUsage(AttributeTargets.Assembly, AllowMultiple = false)]
public sealed class MetaAttribute : Attribute {
    /// The metadata map, as EDN.
    public string Edn { get; }

    public MetaAttribute(string edn) { Edn = edn; }
}
