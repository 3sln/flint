/* The C SDK, exercised the way a caller uses it.
 *
 * Not a demo: every line here is a claim about the ABI that would otherwise
 * only be checked by whoever integrates it first. Build and run it with
 * `sdks/c/build`.
 */
#include "flint.h"

#include <stdio.h>
#include <string.h>

static int failures = 0;

static void ok(int cond, const char *what) {
  printf("  %s %s\n", cond ? "ok  " : "FAIL", what);
  if (!cond) failures++;
}

static const char *APP =
    "(ns app (:require [clojure.string :as s]))\n"
    "(def seen (atom 0))\n"
    "(defn greet [name] (s/upper-case (str \"hello \" name)))\n"
    "(defn tally [] (swap! seen inc))\n"
    "(defn echo [x] x)\n"
    "(defn boom [] (throw (ex-info \"deliberate\" {:a 1})))\n"
    "(defn main [args] (str \"main saw \" (pr-str args)))\n";

/* There is no filesystem here: a resolver answers for a namespace, which is
 * what lets a caller compile out of anything at all -- here, a string. */
static int resolve(void *ctx, const char *ns, const char **out) {
  (void)ctx;
  if (strcmp(ns, "app") != 0) return 0;
  *out = APP;
  return 1;
}

int main(void) {
  char *err = NULL;
  printf("flint: the C SDK\n\n");

  FlintCompiler *c = flint_compiler_new(&err);
  if (!c) {
    printf("  FAIL the compiler did not load: %s\n", err ? err : "(no message)");
    return 1;
  }
  ok(1, "the compiler is embedded");

  const char *exports[] = {"app/greet", "app/tally", "app/echo", "app/boom"};
  const char *optimize[] = {"size"};
  const char *metaKeys[] = {"capabilities"};
  FlintValue *caps = flint_str("fs");
  const FlintValue *capsList[] = {caps};
  FlintValue *capsVec = flint_vector(capsList, 1);
  const FlintValue *metaValues[] = {capsVec};

  FlintCompileOpts opts;
  memset(&opts, 0, sizeof opts);
  opts.fn_name = "app/main";
  opts.resolve = resolve;
  opts.exports = exports;
  opts.exports_len = sizeof exports / sizeof *exports;
  opts.optimize = optimize;
  opts.optimize_len = 1;
  opts.shake = 1;
  opts.meta_keys = metaKeys;
  opts.meta_values = metaValues;
  opts.meta_len = 1;

  FlintImage *img = flint_compile(c, &opts, &err);
  if (!img) {
    printf("  FAIL it did not compile: %s\n", err ? err : "(no message)");
    return 1;
  }
  ok(1, "a program compiles");

  size_t wasmLen = 0;
  const uint8_t *wasm = flint_image_wasm(img, &wasmLen);
  ok(wasm != NULL && wasmLen > 100000, "the artifact is a wasm module");
  ok(wasm[0] == 0 && wasm[1] == 'a' && wasm[2] == 's' && wasm[3] == 'm',
     "and it carries the wasm magic");

  FlintSandbox *s = flint_sandbox_new(img, &err);
  if (!s) {
    printf("  FAIL it did not instantiate: %s\n", err ? err : "(no message)");
    return 1;
  }
  ok(1, "the image instantiates");

  /* Calling by name, with positional arguments and nothing more. */
  FlintValue *name = flint_str("flint");
  const FlintValue *args[] = {name};
  FlintValue *greeting = flint_call(s, "app/greet", args, 1, &err);
  ok(greeting != NULL, "a call by name returns");
  if (greeting) {
    char *text = flint_as_str(greeting);
    ok(text && strcmp(text, "HELLO FLINT") == 0, "and it is the right answer");
    flint_string_free(text);
    ok(flint_value_tag(greeting) == FLINT_STR, "with the right tag");
    flint_value_free(greeting);
  }
  flint_value_free(name);

  /* State belongs to the sandbox, not the image. */
  FlintValue *one = flint_call(s, "app/tally", NULL, 0, &err);
  FlintValue *two = flint_call(s, "app/tally", NULL, 0, &err);
  ok(one && flint_as_int(one) == 1, "a sandbox keeps its state");
  ok(two && flint_as_int(two) == 2, "across calls");
  flint_value_free(one);
  flint_value_free(two);

  FlintSandbox *fresh = flint_sandbox_new(img, &err);
  FlintValue *again = flint_call(fresh, "app/tally", NULL, 0, &err);
  ok(again && flint_as_int(again) == 1, "and a fresh sandbox starts over");
  flint_value_free(again);
  flint_sandbox_free(fresh);

  /* Every shape the codec carries, out and back. */
  {
    FlintValue *k = flint_keyword(NULL, "a");
    FlintValue *v = flint_int(1);
    const FlintValue *ks[] = {k};
    const FlintValue *vs[] = {v};
    FlintValue *m = flint_map(ks, vs, 1);
    FlintValue *items[] = {NULL};
    (void)items;
    const FlintValue *inner[] = {m};
    FlintValue *vec = flint_vector(inner, 1);
    const FlintValue *callArgs[] = {vec};
    FlintValue *back = flint_call(s, "app/echo", callArgs, 1, &err);
    ok(back && flint_value_tag(back) == FLINT_VECTOR, "a vector survives a call");
    if (back) {
      FlintValue *first = flint_nth(back, 0);
      ok(first && flint_value_tag(first) == FLINT_MAP, "with a map inside it");
      if (first) {
        FlintValue *key = flint_nth(first, 0);
        FlintValue *val = flint_map_value(first, 0);
        char *kt = key ? flint_as_str(key) : NULL;
        ok(key && flint_value_tag(key) == FLINT_KEYWORD && kt && strcmp(kt, "a") == 0,
           "whose key is still a keyword");
        ok(val && flint_as_int(val) == 1, "and whose value came back");
        flint_string_free(kt);
        flint_value_free(key);
        flint_value_free(val);
      }
      flint_value_free(first);
      flint_value_free(back);
    }
    flint_value_free(k);
    flint_value_free(v);
    flint_value_free(m);
    flint_value_free(vec);
  }

  /* A copy is a copy: freeing one does not disturb the other. */
  {
    FlintValue *original = flint_str("held");
    FlintValue *copy = flint_value_clone(original);
    flint_value_free(original);
    char *t = flint_as_str(copy);
    ok(t && strcmp(t, "held") == 0, "a clone outlives the value it came from");
    flint_string_free(t);
    flint_value_free(copy);
  }

  /* A thrown error reaches the caller as a failure, not as a return. */
  {
    char *boomErr = NULL;
    FlintValue *nope = flint_call(s, "app/boom", NULL, 0, &boomErr);
    ok(nope == NULL, "a thrown error is a failure, not a value");
    ok(boomErr != NULL && strstr(boomErr, "deliberate") != NULL,
       "and the message reaches the caller");
    flint_string_free(boomErr);
    flint_value_free(nope);
  }

  /* Gas counts only under a limit -- see flint_sandbox_set_step_limit. */
  {
    FlintSandbox *idle = flint_sandbox_new(img, &err);
    FlintValue *v = flint_int(1);
    const FlintValue *a[] = {v};
    flint_value_free(flint_call(idle, "app/echo", a, 1, &err));
    ok(flint_sandbox_gas(idle) == 0, "an unbudgeted sandbox does not count gas");
    flint_sandbox_set_step_limit(idle, 50000000);
    flint_value_free(flint_call(idle, "app/echo", a, 1, &err));
    ok(flint_sandbox_gas(idle) > 0, "a budgeted one does");
    flint_value_free(v);
    flint_sandbox_free(idle);
  }

  /* An artifact runs without a compiler, and without a wasm engine. */
  {
    FlintSandbox *fromFile = flint_sandbox_from_wasm(wasm, wasmLen, &err);
    ok(fromFile != NULL, "a compiled artifact loads on its own");
    if (fromFile) {
      FlintValue *n = flint_str("world");
      const FlintValue *a[] = {n};
      FlintValue *g = flint_call(fromFile, "app/greet", a, 1, &err);
      char *t = g ? flint_as_str(g) : NULL;
      ok(t && strcmp(t, "HELLO WORLD") == 0, "and answers the same");
      flint_string_free(t);
      flint_value_free(g);
      flint_value_free(n);
      flint_sandbox_free(fromFile);
    }
  }

  /* --- drivers (DECISIONS.md#drivers) --------------------------------------
   *
   * The same fifth noun as the Rust and JavaScript SDKs, by the same names.
   * Here the answer is real: this target runs guest code on several executors
   * over one heap. */
  {
    FlintDriver *inl = flint_driver_inline();
    ok(flint_driver_parallelism(inl) == 1, "an inline driver is one thread");
    flint_driver_free(inl);

    FlintDriver *pool = flint_driver_pool(4);
    ok(flint_driver_parallelism(pool) == 4, "a pool of four is four here");

    char *perr = NULL;
    FlintSandbox *ps = flint_sandbox_new_with(img, pool, &perr);
    ok(ps != NULL, "an image instantiates under a driver");
    if (ps) {
      ok(flint_sandbox_parallelism(ps) == 4, "and reads its parallelism back");
      /* 100 calls through one (swap! seen inc): every answer distinct, and
       * 1..=100 between them, or two threads read one state. */
      int seen[101];
      memset(seen, 0, sizeof seen);
      int dupes = 0, out_of_range = 0;
      for (int i = 0; i < 100; i++) {
        FlintValue *v = flint_call(ps, "app/tally", NULL, 0, &perr);
        if (!v) { out_of_range++; continue; }
        long long n = flint_as_int(v);
        if (n < 1 || n > 100) out_of_range++;
        else if (seen[n]++) dupes++;
        flint_value_free(v);
      }
      ok(dupes == 0 && out_of_range == 0,
         "100 calls through a pool are 100 distinct increments");
      flint_sandbox_free(ps);
    }
    flint_driver_free(pool);
  }

  flint_value_free(caps);
  flint_value_free(capsVec);
  flint_sandbox_free(s);
  flint_image_free(img);
  flint_compiler_free(c);

  printf("\n%s\n", failures ? "FAILED" : "all checks passed");
  return failures ? 1 : 0;
}
