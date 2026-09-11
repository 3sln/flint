# flint for C and C++

One library, two headers. `flint.h` is the ABI; `flint.hpp` is a header over
the same symbols that adds RAII and exceptions. There is no second
implementation to keep in step.

```sh
sdks/c/build     # builds the library and runs both selftests
```

That produces `target/release/libflint.a` and `libflint.dylib` (`.so` on
Linux). Compile against `sdks/c/include`.

```c
#include "flint.h"

static const char *APP = "(ns app) (defn greet [n] (str \"hello \" n))";

static int resolve(void *ctx, const char *ns, const char **out) {
  if (strcmp(ns, "app") != 0) return 0;
  *out = APP;
  return 1;
}

char *err = NULL;
FlintCompiler *c = flint_compiler_new(&err);

FlintCompileOpts opts;
memset(&opts, 0, sizeof opts);          /* a zeroed struct is a valid request */
opts.fn_name = "app/greet";
opts.resolve = resolve;
opts.shake = 1;

FlintImage *img = flint_compile(c, &opts, &err);
FlintSandbox *s = flint_sandbox_new(img, &err);

FlintValue *name = flint_str("world");
const FlintValue *args[] = {name};
FlintValue *out = flint_call(s, "app/greet", args, 1, &err);
char *text = flint_as_str(out);         /* "hello world" */
```

The C++ version of the same thing frees everything for you and throws
`flint::Error` instead of setting `err`:

```cpp
flint::Compiler compiler;
flint::Compile spec;
spec.fn = "app/greet";
spec.resolve = [](const std::string &ns, std::string &out) {
  if (ns != "app") return false;
  out = APP;
  return true;
};
auto sandbox = compiler.compile(spec).sandbox();
std::string text = sandbox.call("app/greet", {flint::Value::string("world")}).asString();
```

## Two rules cover the whole header

1. Anything the library **returns** is yours — free it with the matching
   `flint_*_free`, or `flint_string_free` for a `char *`.
2. Anything you **pass in** stays yours. Collection constructors copy.

Every fallible call takes a `char **err`, sets it only on failure, and returns
`NULL`. Passing `NULL` discards the message.

## Why values are opaque

A flint value is NaN-boxed and its representation is a runtime detail
(`DECISIONS.md#dispatch`). A struct in this header would freeze that detail into
an ABI that could then never change. It is also what keeps C from fabricating a
port or a sentinel: you can receive one and hand it back, and there is no
constructor that makes one out of an integer (`DECISIONS.md#structured-ports`).

## No wasm engine

The runtime is Rust, so it already compiles natively through LLVM — the
collector, the interpreter and every builtin are the same code the wasm module
is built from (`DECISIONS.md#other-hosts`). This library carries no wasm engine.

It still *reads* a `.wasm` artifact: a module carries its program as a data
segment and `FLINT_IMAGE_DESC` says where, so `flint_sandbox_from_wasm` is a
matter of finding the program rather than of executing wasm.

## Where the symbols live

`flint::capi` in `sdks/rust`, behind the `capi` feature. This crate is only
what emits it as a `cdylib` and a `staticlib`, because cargo cannot make
`crate-type` conditional on a feature and putting `cdylib` on the Rust SDK
would build a shared library for every Rust user who wanted none.
