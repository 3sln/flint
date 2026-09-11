// Which function a benchmark module exposes.
//
// Nothing is called automatically (`DECISIONS.md#structured-ports` step 5): a module has
// no entry point and a caller names the function it wants. Every benchmark
// module is built `:fn <ns>/main` and written to a path that carries the `<ns>`,
// so the name is derivable rather than something each script has to repeat.
//
// `bin/bench` writes `out/bench-<ns>.wasm`; the rest name themselves.
export function fnOf(path) {
  const p = String(path);
  const m = /out\/bench-([a-z0-9-]+)\.wasm$/.exec(p)
         ?? /out\/([a-z0-9-]+)\.wasm$/.exec(p);
  if (!m) throw new Error(`bench: cannot tell which function ${p} exposes`);
  return `${m[1]}/main`;
}
