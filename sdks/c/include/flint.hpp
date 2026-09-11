// flint for C++.
//
// The same library as `flint.h` -- there is one ABI, and this is a header over
// it, not a second implementation. What it adds is the two things C makes you
// do by hand: every handle frees itself, and a failure is an exception rather
// than a `char **err` you might not check.
//
// Header-only and C++17. Link against the same `flint` library.

#ifndef FLINT_HPP
#define FLINT_HPP

#include "flint.h"

#include <functional>
#include <initializer_list>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace flint {

/// Anything the library refused to do. The message is flint's own.
class Error : public std::runtime_error {
public:
  explicit Error(std::string what) : std::runtime_error(std::move(what)) {}
};

namespace detail {

/// A `char *` from the library, freed on the way out. Used for both results
/// and error messages, so a throw cannot leak the message it threw.
inline std::string take(char *s) {
  if (!s) return {};
  std::string out(s);
  flint_string_free(s);
  return out;
}

/// Throw whatever the library put in `err`, or a fallback when it set none.
[[noreturn]] inline void fail(char *err, const char *fallback) {
  std::string message = take(err);
  throw Error(message.empty() ? fallback : message);
}

} // namespace detail

/// A value crossing the boundary.
///
/// Copies are real copies, which matches every other SDK: passing a value to a
/// collection or a call does not consume it.
class Value {
public:
  Value() : v_(flint_nil()) {}
  explicit Value(FlintValue *raw) : v_(raw) {
    if (!v_) throw Error("flint returned no value");
  }
  ~Value() { flint_value_free(v_); }

  Value(const Value &other) : v_(flint_value_clone(other.v_)) {}
  Value &operator=(const Value &other) {
    if (this != &other) { Value copy(other); std::swap(v_, copy.v_); }
    return *this;
  }
  Value(Value &&other) noexcept : v_(other.v_) { other.v_ = flint_nil(); }
  Value &operator=(Value &&other) noexcept {
    std::swap(v_, other.v_);
    return *this;
  }

  static Value nil() { return Value(flint_nil()); }
  static Value boolean(bool b) { return Value(flint_bool(b ? 1 : 0)); }
  static Value integer(int64_t i) { return Value(flint_int(i)); }
  static Value real(double d) { return Value(flint_float(d)); }
  static Value string(const std::string &s) { return Value(flint_str(s.c_str())); }
  static Value bytes(const uint8_t *b, size_t n) { return Value(flint_bytes(b, n)); }
  static Value bytes(const std::vector<uint8_t> &b) { return bytes(b.data(), b.size()); }

  /// `keyword("a")` is `:a`; `keyword("my.ns", "a")` is `:my.ns/a`.
  static Value keyword(const std::string &name) {
    return Value(flint_keyword(nullptr, name.c_str()));
  }
  static Value keyword(const std::string &ns, const std::string &name) {
    return Value(flint_keyword(ns.c_str(), name.c_str()));
  }
  static Value symbol(const std::string &name) {
    return Value(flint_symbol(nullptr, name.c_str()));
  }
  static Value symbol(const std::string &ns, const std::string &name) {
    return Value(flint_symbol(ns.c_str(), name.c_str()));
  }

  static Value vector(std::initializer_list<Value> items) { return build(flint_vector, items); }
  static Value list(std::initializer_list<Value> items) { return build(flint_list, items); }
  static Value set(std::initializer_list<Value> items) { return build(flint_set, items); }

  /// Pairs keep the order given, because a flint map's keys are not always
  /// strings and order is worth keeping through a round trip.
  static Value map(std::initializer_list<std::pair<Value, Value>> entries) {
    std::vector<const FlintValue *> ks, vs;
    ks.reserve(entries.size());
    vs.reserve(entries.size());
    for (const auto &e : entries) {
      ks.push_back(e.first.get());
      vs.push_back(e.second.get());
    }
    return Value(flint_map(ks.data(), vs.data(), ks.size()));
  }

  FlintTag tag() const { return flint_value_tag(v_); }
  bool isNil() const { return tag() == FLINT_NIL; }

  bool asBool() const { return flint_as_bool(v_) != 0; }
  int64_t asInt() const { return flint_as_int(v_); }
  double asFloat() const { return flint_as_float(v_); }
  /// The TEXT of a string, keyword or symbol: a keyword answers "foo", not
  /// ":foo". Empty for anything else.
  std::string asString() const { return detail::take(flint_as_str(v_)); }
  /// Empty when the keyword or symbol has no namespace.
  std::string ns() const { return detail::take(flint_namespace(v_)); }

  std::vector<uint8_t> asBytes() const {
    size_t n = 0;
    const uint8_t *b = flint_as_bytes(v_, &n);
    return b ? std::vector<uint8_t>(b, b + n) : std::vector<uint8_t>();
  }

  size_t size() const { return flint_count(v_); }
  /// Item `i`; the KEY at `i` for a map. Nil when out of range.
  Value at(size_t i) const {
    FlintValue *got = flint_nth(v_, i);
    return got ? Value(got) : Value::nil();
  }
  Value valueAt(size_t i) const {
    FlintValue *got = flint_map_value(v_, i);
    return got ? Value(got) : Value::nil();
  }

  /// Printed the way flint prints it.
  std::string str() const { return detail::take(flint_print(v_)); }

  const FlintValue *get() const { return v_; }

private:
  static Value build(FlintValue *(*make)(const FlintValue *const *, size_t),
                     std::initializer_list<Value> items) {
    std::vector<const FlintValue *> raw;
    raw.reserve(items.size());
    for (const auto &i : items) raw.push_back(i.get());
    return Value(make(raw.data(), raw.size()));
  }

  FlintValue *v_ = nullptr;
};

/// Who advances a sandbox, and when (`DECISIONS.md#drivers`).
///
/// `Driver::inline_()` runs on the calling thread; `Driver::pool(n)` gives a
/// sandbox several executors on one heap. Ask for what you want and read
/// `parallelism()` for what you got -- a target that cannot honour the request
/// answers honestly rather than pretending.
class Driver {
public:
  static Driver inline_() { return Driver(flint_driver_inline()); }
  static Driver pool(std::size_t threads) { return Driver(flint_driver_pool(threads)); }

  std::size_t parallelism() const { return flint_driver_parallelism(d_.get()); }
  const FlintDriver *get() const { return d_.get(); }

private:
  explicit Driver(FlintDriver *raw) : d_(raw, flint_driver_free) {
    if (!d_) throw Error("flint returned no driver");
  }
  std::shared_ptr<FlintDriver> d_;
};

/// A running instance of an image. Independent of every other.
class Sandbox {
public:
  explicit Sandbox(FlintSandbox *raw) : s_(raw, flint_sandbox_free) {
    if (!s_) throw Error("flint returned no sandbox");
  }

  /// A sandbox from a `.wasm` artifact somebody else compiled: no compiler
  /// needed, and no wasm engine either -- it runs natively.
  static Sandbox fromWasm(const uint8_t *wasm, size_t len) {
    char *err = nullptr;
    FlintSandbox *s = flint_sandbox_from_wasm(wasm, len, &err);
    if (!s) detail::fail(err, "the module did not load");
    return Sandbox(s);
  }

  /// `grant(name)` WAS HERE and could never have worked: it called
  /// `flint_sandbox_grant`, which this header declared and no translation unit
  /// ever defined. See the note in `flint.h`. The host projects opaque values
  /// into the sandbox instead.

  /// Stop a call after `n` instructions, and turn counting on.
  void setStepLimit(uint64_t n) { flint_sandbox_set_step_limit(s_.get(), n); }

  /// Instructions so far -- only while a step limit is set; 0 otherwise.
  uint64_t gas() const { return flint_sandbox_gas(s_.get()); }

  /// How many threads may be inside this sandbox at once.
  std::size_t parallelism() const { return flint_sandbox_parallelism(s_.get()); }

  /// Dispatches that ran on a secondary executor. See the C header.
  uint64_t parallelDispatches() const {
    return flint_sandbox_parallel_dispatches(s_.get());
  }

  Value call(const std::string &name, const std::vector<Value> &args = {}) {
    std::vector<const FlintValue *> raw;
    raw.reserve(args.size());
    for (const auto &a : args) raw.push_back(a.get());
    char *err = nullptr;
    FlintValue *out = flint_call(s_.get(), name.c_str(), raw.data(), raw.size(), &err);
    if (!out) detail::fail(err, "the call failed");
    return Value(out);
  }

private:
  std::unique_ptr<FlintSandbox, decltype(&flint_sandbox_free)> s_;
};

/// A compiled program. Instantiate it as many times as you like.
class Image {
public:
  explicit Image(FlintImage *raw) : img_(raw, flint_image_free) {
    if (!img_) throw Error("flint returned no image");
  }

  /// The module bytes, borrowed: valid while this image is.
  const uint8_t *wasm(size_t &len) const { return flint_image_wasm(img_.get(), &len); }
  std::vector<uint8_t> wasm() const {
    size_t n = 0;
    const uint8_t *b = flint_image_wasm(img_.get(), &n);
    return b ? std::vector<uint8_t>(b, b + n) : std::vector<uint8_t>();
  }

  Sandbox sandbox() const {
    char *err = nullptr;
    FlintSandbox *s = flint_sandbox_new(img_.get(), &err);
    if (!s) detail::fail(err, "the image did not instantiate");
    return Sandbox(s);
  }

  /// Instantiate under a driver of your choosing.
  Sandbox sandbox(const Driver &driver) const {
    char *err = nullptr;
    FlintSandbox *s = flint_sandbox_new_with(img_.get(), driver.get(), &err);
    if (!s) detail::fail(err, "the image did not instantiate");
    return Sandbox(s);
  }

private:
  std::unique_ptr<FlintImage, decltype(&flint_image_free)> img_;
};

/// How a namespace becomes source. Return an empty optional-ish `false` for
/// "no such namespace"; there is no filesystem here on purpose.
using Resolver = std::function<bool(const std::string &ns, std::string &out)>;

/// What to compile.
struct Compile {
  /// "namespace/function": what a sandbox calls by default.
  std::string fn;
  Resolver resolve;
  /// Additionally callable names. Only reachable code ships, so a function
  /// nobody calls from the entry has to be named here to survive.
  std::vector<std::string> exports;
  /// An ORDERED PREFERENCE: "perf" compiles every arity, "size" interprets.
  /// The first token this build understands decides, and unrecognised ones are
  /// ignored -- so a list written for a newer flint still works here.
  std::vector<std::string> optimize;
  bool shake = true;
  /// Recorded in the artifact and never read by flint.
  std::vector<std::pair<std::string, Value>> meta;
};

class Compiler {
public:
  Compiler() : c_(nullptr, flint_compiler_free) {
    char *err = nullptr;
    FlintCompiler *raw = flint_compiler_new(&err);
    if (!raw) detail::fail(err, "the embedded compiler did not load");
    c_.reset(raw);
  }

  Image compile(const Compile &opts) const {
    // The resolver's answer has to outlive the callback, because the C
    // contract is that `*out` stays valid until the next call. A member of the
    // trampoline is exactly that long.
    struct Trampoline {
      const Resolver *resolve;
      std::string held;
    } t{&opts.resolve, {}};

    FlintCompileOpts c{};
    c.fn_name = opts.fn.c_str();
    c.resolve_ctx = &t;
    c.resolve = [](void *ctx, const char *ns, const char **out) -> int {
      auto *t = static_cast<Trampoline *>(ctx);
      if (!t->resolve || !*t->resolve) return 0;
      std::string got;
      if (!(*t->resolve)(ns, got)) return 0;
      t->held = std::move(got);
      *out = t->held.c_str();
      return 1;
    };

    std::vector<const char *> exports = cstrs(opts.exports);
    std::vector<const char *> optimize = cstrs(opts.optimize);
    c.exports = exports.data();
    c.exports_len = exports.size();
    c.optimize = optimize.data();
    c.optimize_len = optimize.size();
    c.shake = opts.shake ? 1 : 0;

    std::vector<const char *> metaKeys;
    std::vector<const FlintValue *> metaValues;
    for (const auto &kv : opts.meta) {
      metaKeys.push_back(kv.first.c_str());
      metaValues.push_back(kv.second.get());
    }
    c.meta_keys = metaKeys.data();
    c.meta_values = metaValues.data();
    c.meta_len = metaKeys.size();

    char *err = nullptr;
    FlintImage *img = flint_compile(c_.get(), &c, &err);
    if (!img) detail::fail(err, "the program did not compile");
    return Image(img);
  }

private:
  static std::vector<const char *> cstrs(const std::vector<std::string> &v) {
    std::vector<const char *> out;
    out.reserve(v.size());
    for (const auto &s : v) out.push_back(s.c_str());
    return out;
  }

  std::unique_ptr<FlintCompiler, decltype(&flint_compiler_free)> c_;
};

} // namespace flint

#endif // FLINT_HPP
