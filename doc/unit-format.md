# The flint unit format

A **unit** is one compiled namespace. `flint` computes the set of units reachable
from `:fn` and composes exactly those into a single wasm module.

Nothing in this format says "built-in". A unit is described by what it *is* —
artifact, exports, dependencies, compatibility — not by who shipped it. Compiling
a user namespace ahead of time produces the same shape; that is deliberate
(`doc/decisions/0003-namespace-units.md`).

## Where a unit lives

Units are laid out **by namespace**, exactly the way `:src` lays out source, and
found on a search path given by `:wasm-ld`:

```
flint.data.xml   ->  <dir>/flint/data/xml.unit.edn   the manifest
                     <dir>/flint/data/xml.o          the artifact
                     <dir>/flint/data/xml.libs/*.rlib  its dependency rlibs
```

flint's own `units/` directory is simply the **last entry** on that path, so a
`:wasm-ld` directory can shadow a built-in unit and every compile exercises the
same mechanism a user-supplied unit uses. Earlier directories win; a unit that
loses is reported rather than silently dropped.

A `.cljc` file may sit beside a unit, and often does: a unit is a namespace's
*native* half and the source its Clojure half — `flint.data.json` ships as both.
They compose rather than compete. Source resolution searches `:src` directories
first, then `:wasm-ld` directories, then flint's own `lib/`.

## Manifest

```edn
;; <dir>/flint/data/xml.unit.edn
{:flint/unit 1                       ; unit-format version
 :name       flint.data.xml
 :kind       :wasm-object            ; or :bytecode
 :artifact   "xml.o"                 ; relative to the manifest
 :libs       "xml.libs"              ; optional: directory of rlibs it needs
 :requires   [flint.rt]              ; other units, by name
 :provides   {"flint/xml-parse" {:symbol "flint_b_xml_parse"}}
 :abi        {:runtime 1 :value 1 :image 1}}
```

`:provides` maps a **builtin name** — what `flint.rt/xml-parse` resolves to — to
the exported C symbol implementing it.

* `:kind :wasm-object` — a relocatable wasm object. `:provides` values name the
  exported C symbol that implements each var.
* `:kind :bytecode` — a precompiled bytecode fragment. `:provides` values carry
  the fragment-local var index.

`:abi` is checked before the compile starts, for **every** unit on the path:
`:runtime` is the builtin calling convention `(rt, base, argc) -> u64`, `:value`
the NaN-boxing layout, `:image` the bytecode image format. A unit this flint
cannot link is refused by name and version rather than linked and left to trap:

```
refusing unit demo.shout at vendor/units/demo/shout.unit.edn: runtime 2 (need 1)
```

Naming a directory with `:wasm-ld` is an assertion that its units are for this
flint, so the check is over the whole path rather than only the units that end up
linked — a stale copy that would have been shadowed is still worth hearing about
before it becomes a puzzle.

## How a build is composed

1. Read `:src`, compile the user namespaces, and walk the reference graph from
   `:fn` to get the reachable **var** set (not namespace set — reachability is
   per var).
2. Map each reachable var to its providing unit. Close over `:requires`.
3. `:bytecode` units: concatenate the reachable fragments into the program image,
   renumbering vars and constants, and resolving each fragment's native-import
   table.
4. `:wasm-object` units: hand the artifacts to `rust-lld -flavor wasm`, with
   `--gc-sections` and one `--export=<symbol>` per *reached* provide. Unreached
   builtins in a linked object are dropped too, so granularity is per builtin,
   not per namespace.
5. Read the linked module's export section to get each builtin's function index,
   append an element segment binding them into `__indirect_function_table`, and
   write the id→slot mapping into the image. The VM's `CALL_NATIVE` is a
   `call_indirect`; it is the **only** way a builtin is reached.
6. Splice the program image in as a data segment at `__heap_base`, and patch the
   descriptor slot with (ptr,len).

Step 4 is what makes "only reachable code ships" true rather than aspirational,
and step 5 is why it stays true: nothing in the runtime holds a static table of
builtins, so nothing keeps an unused one alive.

## Toolchain notes (this machine, measured)

* `rustc --emit=obj --target wasm32-unknown-unknown` produces the relocatable
  objects. **This needs no nightly features.** The nightly toolchain is used only
  because it is the one with the `wasm32-unknown-unknown` target installed here;
  Homebrew's `rustc` 1.92.0 has no wasm std.
* The linker is `rust-lld -flavor wasm` from the rustup toolchain (LLD 19.1.1).
  **`/opt/homebrew/bin/wasm-ld` is present but does not run on this machine** —
  it is lld 19.1.7 built against llvm 21.1.8 and dies with a dyld symbol error.
  `rust-lld` is not a fallback, it is the primary and it ships with the compiler.
* `rustc` drives the final link normally, so when we drive it ourselves the crate
  must supply the three symbols the allocator shim would have provided:
  `__rust_no_alloc_shim_is_unstable`, `__rust_alloc_error_handler_should_panic`,
  `__rust_alloc_error_handler`.
* Link `--strip-all`. Debug info dominates otherwise: a validation module measured
  1386 bytes of code and 755 KB of `.debug_*` custom sections.

## Measured, on the validation harness

Three namespace objects, a core object, `--gc-sections`, sysroot `core`/`alloc`/
`compiler_builtins` rlibs on the link line:

| what | bytes |
|---|---|
| linked module, `--strip-all` | 1632 |
| of which code | 1386 |
| a builtin present but not `--export`ed | **eliminated** |
| a namespace object not passed to the linker | **absent** |

Indirect dispatch through the spliced element segment and the spliced data
segment were both verified running under node.

## Admitting a user-compiled namespace

Two of the three things this document used to list as missing are done: there is
a search path (`:wasm-ld`) rather than a fixed directory, and `:abi` mismatch is
a message naming the unit and the version rather than an assert.
`test/options.clj` builds `units-src/flint-demo-shout` into
`test/fixtures/wasm-ld/demo/shout.{o,unit.edn}` with a `shout.cljc` beside it,
puts it on the path, links it, and runs the module.

What is still missing:

* the compiler cannot yet emit a `:bytecode` unit for a user namespace — cljc
  namespaces are recompiled from source on every build. The format describes
  them, and the composition step above handles them; nothing produces one.
* the builtin calling convention is documented and versioned (`:abi :runtime 1`)
  but is not *promised* stable, so a `:wasm-object` unit built today may need
  rebuilding against a later flint. The refusal above is what makes that safe
  rather than mysterious.
