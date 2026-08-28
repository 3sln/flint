// The C++ SDK, exercised the way a caller uses it.
//
// The same claims as `selftest.c` against the same library -- the point is
// that the wrapper adds RAII and exceptions and changes nothing else.
#include "flint.hpp"

#include <cstring>
#include <iostream>
#include <string>

static int failures = 0;

static void ok(bool cond, const std::string &what) {
  std::cout << "  " << (cond ? "ok  " : "FAIL") << " " << what << "\n";
  if (!cond) failures++;
}

static const char *APP = R"(
(ns app (:require [clojure.string :as s]))
(def seen (atom 0))
(defn greet [name] (s/upper-case (str "hello " name)))
(defn tally [] (swap! seen inc))
(defn echo [x] x)
(defn boom [] (throw (ex-info "deliberate" {:a 1})))
(defn main [args] (str "main saw " (pr-str args)))
)";

int main() {
  std::cout << "flint: the C++ SDK\n\n";
  try {
    flint::Compiler compiler;
    ok(true, "the compiler is embedded");

    flint::Compile spec;
    spec.fn = "app/main";
    spec.exports = {"app/greet", "app/tally", "app/echo", "app/boom"};
    spec.optimize = {"size"};
    spec.meta = {{"capabilities", flint::Value::vector({flint::Value::string("fs")})}};
    spec.resolve = [](const std::string &ns, std::string &out) {
      if (ns != "app") return false;
      out = APP;
      return true;
    };

    flint::Image img = compiler.compile(spec);
    ok(true, "a program compiles");
    ok(img.wasm().size() > 100000, "the artifact is a wasm module");

    flint::Sandbox s = img.sandbox();
    ok(true, "the image instantiates");

    flint::Value greeting = s.call("app/greet", {flint::Value::string("flint")});
    ok(greeting.asString() == "HELLO FLINT", "a call by name returns the right answer");
    ok(greeting.tag() == FLINT_STR, "with the right tag");

    ok(s.call("app/tally").asInt() == 1, "a sandbox keeps its state");
    ok(s.call("app/tally").asInt() == 2, "across calls");
    ok(img.sandbox().call("app/tally").asInt() == 1, "and a fresh sandbox starts over");

    flint::Value sent = flint::Value::vector(
        {flint::Value::map({{flint::Value::keyword("a"), flint::Value::integer(1)}})});
    flint::Value back = s.call("app/echo", {sent});
    ok(back.tag() == FLINT_VECTOR && back.size() == 1, "a vector survives a call");
    flint::Value inner = back.at(0);
    ok(inner.tag() == FLINT_MAP, "with a map inside it");
    ok(inner.at(0).tag() == FLINT_KEYWORD && inner.at(0).asString() == "a",
       "whose key is still a keyword");
    ok(inner.valueAt(0).asInt() == 1, "and whose value came back");

    // The copy is a real copy, and the original is untouched by its death.
    {
      flint::Value copy = greeting;
      ok(copy.asString() == "HELLO FLINT", "a copy carries the value");
    }
    ok(greeting.asString() == "HELLO FLINT", "and the original outlives it");

    ok(flint::Value::keyword("my.ns", "a").ns() == "my.ns", "a qualified keyword keeps its namespace");
    ok(flint::Value::integer(7).str() == "7", "a value prints");

    try {
      s.call("app/boom");
      ok(false, "a thrown error should not return");
    } catch (const flint::Error &e) {
      ok(std::string(e.what()).find("deliberate") != std::string::npos,
         "a thrown error arrives as an exception carrying its message");
    }

    flint::Sandbox idle = img.sandbox();
    idle.call("app/echo", {flint::Value::integer(1)});
    ok(idle.gas() == 0, "an unbudgeted sandbox does not count gas");
    idle.setStepLimit(50000000);
    idle.call("app/echo", {flint::Value::integer(1)});
    ok(idle.gas() > 0, "a budgeted one does");

    // --- drivers (`doc/decisions/0028`) ---------------------------------
    ok(flint::Driver::inline_().parallelism() == 1, "an inline driver is one thread");
    ok(flint::Driver::pool(4).parallelism() == 4, "a pool of four is four here");

    auto pooled = img.sandbox(flint::Driver::pool(4));
    ok(pooled.parallelism() == 4, "a sandbox reads its parallelism back");
    bool distinct = true;
    std::vector<bool> seen(101, false);
    for (int i = 0; i < 100; i++) {
      long long n = pooled.call("app/tally").asInt();
      if (n < 1 || n > 100 || seen[n]) distinct = false;
      else seen[n] = true;
    }
    ok(distinct, "100 calls through a pool are 100 distinct increments");

    std::vector<uint8_t> artifact = img.wasm();
    flint::Sandbox loaded = flint::Sandbox::fromWasm(artifact.data(), artifact.size());
    ok(loaded.call("app/greet", {flint::Value::string("world")}).asString() == "HELLO WORLD",
       "a compiled artifact loads on its own and answers the same");
  } catch (const flint::Error &e) {
    std::cout << "  FAIL unexpected: " << e.what() << "\n";
    failures++;
  }

  std::cout << "\n" << (failures ? "FAILED" : "all checks passed") << "\n";
  return failures ? 1 : 0;
}
