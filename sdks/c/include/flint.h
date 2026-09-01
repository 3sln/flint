/* flint for C.
 *
 * Compile pure Clojure and run it in a sandbox. The shape is the JavaScript
 * and Rust SDKs' (doc/decisions/0025): resolve namespaces, compile to an
 * image, instantiate a sandbox, call functions by name.
 *
 * Two rules cover the whole header, so there is no per-function ownership to
 * remember:
 *
 *   1. Anything this library RETURNS is yours. Free it with the matching
 *      flint_*_free, or flint_string_free for a char *.
 *   2. Anything you PASS IN stays yours. Collection constructors copy; nothing
 *      here takes ownership of a pointer you hand it.
 *
 * Every fallible call takes a `char **err`. On failure it returns NULL (or 0)
 * and sets *err to a message you must free with flint_string_free; on success
 * it does not touch *err. Passing NULL for err discards the message.
 */
#ifndef FLINT_H
#define FLINT_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct FlintCompiler FlintCompiler;
typedef struct FlintImage FlintImage;
typedef struct FlintSandbox FlintSandbox;
typedef struct FlintValue FlintValue;

/* Free any string this library returned, including an `err`. */
void flint_string_free(char *s);

/* --- the compiler ------------------------------------------------------- */

/* How a namespace becomes source.
 *
 * There is no filesystem here: a resolver is asked for a namespace such as
 * "my.app.core" and answers with source or with nothing, which is what lets a
 * caller compile out of a database, a zip, or memory. Return 0 for "no such
 * namespace"; return nonzero and set *out to NUL-terminated source.
 *
 * *out must stay valid until the resolver is called again or compilation
 * returns. flint copies it before asking for anything else.
 */
typedef int (*FlintResolver)(void *ctx, const char *ns, const char **out);

FlintCompiler *flint_compiler_new(char **err);
void flint_compiler_free(FlintCompiler *c);

typedef struct {
  /* "namespace/function": what a sandbox calls by default. Required. */
  const char *fn_name;
  FlintResolver resolve; /* Required. */
  void *resolve_ctx;     /* Passed back to resolve untouched. */

  /* Additionally callable names, as "namespace/function".
   *
   * Only reachable code ships (doc/decisions/0002), so a function nobody calls
   * from the entry is exactly the one that has to be named here in order to
   * survive into the artifact. */
  const char *const *exports;
  size_t exports_len;

  /* An ORDERED PREFERENCE, not a switch. "perf" compiles every arity ahead of
   * time -- bigger, and much faster on arithmetic; "size" interprets.
   *
   * The first token this build understands decides, and unrecognised ones are
   * IGNORED. That is what makes a list written against a newer flint still get
   * this one's best effort rather than a refusal. */
  const char *const *optimize;
  size_t optimize_len;

  /* Nonzero to cut the runtime down to what this program reaches. */
  int shake;

  /* Arbitrary metadata recorded in the artifact and never read by flint.
   * Keys are strings; values are any FlintValue. The DECLARED CAPABILITIES of
   * a program live here by convention -- flint carries them, and what to make
   * of them is the host's business. */
  const char *const *meta_keys;
  const FlintValue *const *meta_values;
  size_t meta_len;
} FlintCompileOpts;

FlintImage *flint_compile(const FlintCompiler *c, const FlintCompileOpts *opts, char **err);

/* The module bytes. Borrowed from the image and valid until it is freed. */
const uint8_t *flint_image_wasm(const FlintImage *img, size_t *len);
void flint_image_free(FlintImage *img);

/* --- drivers (doc/decisions/0028) ---------------------------------------- */

/* Who advances a sandbox, and when.
 *
 * A sandbox does not run because someone called into it; it runs because a
 * driver gave it a thread. The inline driver runs on the calling thread; a
 * pool gives a sandbox several executors on ONE heap, so threads run guest
 * code alongside each other.
 *
 * The vocabulary is the same in every flint SDK and only the ANSWER differs.
 * Ask for what you want and read back what you got with
 * flint_driver_parallelism: a target that cannot give you four threads says
 * 1 rather than pretending, because a driver that quietly gives one when
 * asked for four is a performance mystery with no evidence in it. */
typedef struct FlintDriver FlintDriver;

FlintDriver *flint_driver_inline(void);
FlintDriver *flint_driver_pool(size_t threads);
/* What you actually got, which may be less than you asked for. */
size_t flint_driver_parallelism(const FlintDriver *d);
void flint_driver_free(FlintDriver *d);

/* --- the sandbox -------------------------------------------------------- */

/* A running instance of an image. Independent of every other: state a call
 * leaves behind is this sandbox's and no one else's. */
FlintSandbox *flint_sandbox_new(const FlintImage *img, char **err);

/* Instantiate under a driver of your choosing -- a pool, for instance. */
FlintSandbox *flint_sandbox_new_with(const FlintImage *img, const FlintDriver *driver,
                                     char **err);

/* How many threads may be inside this sandbox at once. */
size_t flint_sandbox_parallelism(const FlintSandbox *s);

/* How many dispatches ran on a SECONDARY executor -- genuinely alongside
 * another thread rather than behind the program lock. Readable because a
 * parallel pool cannot otherwise be told from one that quietly fell back to
 * serialising, and the two pass identical tests. */
uint64_t flint_sandbox_parallel_dispatches(const FlintSandbox *s);

/* A sandbox from a .wasm artifact somebody else compiled -- no compiler
 * needed. The module carries its program inside it, and this runs it natively
 * (doc/decisions/0010) rather than through a wasm engine. */
FlintSandbox *flint_sandbox_from_wasm(const uint8_t *wasm, size_t len, char **err);

/* Lend a capability by name.
 *
 * Authority is never a type test (doc/decisions/0022): a program holds a
 * capability because the host gave it one. Granting nothing -- the default --
 * means the program can reach nothing. */
void flint_sandbox_grant(const FlintSandbox *s, const char *name);

/* Stop a call after n instructions, and turn COUNTING ON.
 *
 * Gas is deterministic (doc/decisions/0009): the same call stops in the same
 * place on every engine and every machine. Without a limit the interpreter
 * carries no counter at all, which is why flint_sandbox_gas reads 0 until this
 * is called. */
void flint_sandbox_set_step_limit(const FlintSandbox *s, uint64_t n);

/* Instructions executed so far -- only while a step limit is set; 0 otherwise.
 * See flint_sandbox_set_step_limit. */
uint64_t flint_sandbox_gas(const FlintSandbox *s);

/* Call a function by name.
 *
 * The SDK takes a name and positional arguments and nothing more: an argument
 * map, capabilities-as-arguments and the rest are a CLI's conventions
 * (doc/decisions/0025), not this layer's. */
/* Blocking: it queues the request with the sandbox's driver and waits.
 * Under the default inline driver that is the same thread and the same
 * instant. C gets no promise back because inventing one would be inventing an
 * async runtime for C; a driver-aware C surface is separate work. */
FlintValue *flint_call(const FlintSandbox *s, const char *name,
                       const FlintValue *const *args, size_t nargs, char **err);

void flint_sandbox_free(FlintSandbox *s);

/* --- values ------------------------------------------------------------- */

/* A flint value is NaN-boxed and its representation is a runtime detail
 * (doc/decisions/0001), so it is opaque here: exposing a struct would freeze
 * that detail into an ABI. It is also what lets a value hold a port or a
 * sentinel that C has no way to fabricate. */
typedef enum {
  FLINT_NIL = 0,
  FLINT_BOOL = 1,
  FLINT_INT = 2,
  FLINT_FLOAT = 3,
  FLINT_STR = 4,
  FLINT_KEYWORD = 5,
  FLINT_SYMBOL = 6,
  FLINT_BYTES = 7,
  FLINT_VECTOR = 8,
  FLINT_LIST = 9,
  FLINT_SET = 10,
  FLINT_MAP = 11,
  /* A live thing, by identity. You may receive one and hand it back; nothing
   * here can MAKE one, which is the sandbox rule (doc/decisions/0025). */
  FLINT_PORT = 12,
  FLINT_SENTINEL = 13,
  /* A tagged literal (doc/decisions/0034): a namespaced symbol and a form.
   * Its own tag rather than a two-key map, because a host meeting one has to
   * be able to tell it from a map that happens to have those keys. */
  FLINT_TAGGED = 14,
  /* A table (doc/decisions/0026). COLUMNAR, here as on the wire: ask for the
   * schema, then for one column at a time. Handing rows across would rebuild a
   * map per row and spend on arrival exactly what the sender saved. */
  FLINT_TABLE = 15
} FlintTag;

FlintTag flint_value_tag(const FlintValue *v);
/* A copy, yours to free. */
FlintValue *flint_value_clone(const FlintValue *v);
void flint_value_free(FlintValue *v);

FlintValue *flint_nil(void);
FlintValue *flint_bool(int b);
FlintValue *flint_int(int64_t i);
FlintValue *flint_float(double f);
FlintValue *flint_str(const char *s);
FlintValue *flint_bytes(const uint8_t *b, size_t len);
/* ns may be NULL for an unqualified keyword such as :foo. */
FlintValue *flint_keyword(const char *ns, const char *name);
FlintValue *flint_symbol(const char *ns, const char *name);

/* The collection constructors COPY their items: you keep ownership of
 * everything you passed and free it as you always would. */
FlintValue *flint_vector(const FlintValue *const *items, size_t n);
FlintValue *flint_list(const FlintValue *const *items, size_t n);
FlintValue *flint_set(const FlintValue *const *items, size_t n);
/* Parallel key and value arrays. Pairs keep the order given: a flint map's
 * keys are not always strings, and order is worth keeping through a round
 * trip. */
FlintValue *flint_map(const FlintValue *const *keys, const FlintValue *const *vals, size_t n);

int flint_as_bool(const FlintValue *v);
int64_t flint_as_int(const FlintValue *v);
double flint_as_float(const FlintValue *v);
/* The TEXT of a string, keyword or symbol -- not its printed form, so a
 * keyword answers "foo" and not ":foo". Yours; free with flint_string_free.
 * NULL for anything else. */
char *flint_as_str(const FlintValue *v);
/* The namespace of a keyword or symbol, or NULL when it has none. */
char *flint_namespace(const FlintValue *v);
/* Borrowed from the value and valid until it is freed; NULL for anything that
 * is not a byte string. */
const uint8_t *flint_as_bytes(const FlintValue *v, size_t *len);

/* How many items a collection has; the number of PAIRS for a map. */
size_t flint_count(const FlintValue *v);
/* Item i, COPIED: yours to free, and the collection is unaffected. NULL when
 * out of range. For a map this is the key at i. */
FlintValue *flint_nth(const FlintValue *v, size_t i);
FlintValue *flint_map_value(const FlintValue *v, size_t i);

/* Printed the way flint prints it, for logs and for tests. Yours; free with
 * flint_string_free. */
char *flint_print(const FlintValue *v);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* FLINT_H */
