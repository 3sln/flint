(ns flint.jvm
  "`:to :jvm`: a program as ONE CLASS FILE.

  ## What the artifact is

  `flint/Artifact.class`, and nothing else:

  | | |
  | --- | --- |
  | the program | the bytecode image, as `CONSTANT_String` entries in the pool |
  | what it says about itself | the properties, as one more string constant |
  | its face | `boot`, `loop`, `link`, `prop`, four static methods |
  | its compiled arities | NOT YET. That is the AOT half; see the report. |

  ## SELF-CONTAINED MEANS ALL OF THE PROGRAM'S CODE

  It does NOT mean \"runs standalone\". The interpreter, the collector and the
  builtins are the HOST's: `boot` takes a resolver and asks it for each builtin the
  image names -- 88 of them for `(ns t) (defn main [args] \"x\")`, which is
  `clojure.core`'s reach and not the program's -- and `link` overrides one
  afterwards. So the deployment is `java -cp flint-rt.jar:. ...`, and an artifact on
  its own runs nothing.

  This file said the opposite while the interpreter travelled in a jar beside the
  image, and the correction is worth stating rather than making quietly: a reader
  who takes \"self-contained\" to mean \"needs nothing else\" will be wrong about the
  deployment.

  ## Why the image is CONSTANT DATA and not a resource

  A resource is fewer moving parts and is how this was first written. It is the
  wrong shape: it makes the bytecode a FILE the artifact contains rather than data
  the artifact is made of, and it needs a container -- so one class file could not
  be the unit at all. The CLR will carry it the same way, so the two stay uniform
  rather than each being natural on its own terms.

  It costs the modified-UTF-8 expansion, and here the image is nearly the whole
  artifact, so that is the dominant cost and not an incidental one: 1.417x measured
  on a 26 715-byte image and 1.323x on a 218 859-byte one, over a flat ~1 050 bytes
  of class structure. `flint.classfile` has the encoding, the chunking, and why
  seven-bit packing was not taken.

  ## What is not here

  No `javac` and no linker at any point, which is the whole reason this is cljc.
  And no AOT: compiled arities are what would make this more than a bytecode
  carrier, and emitting one needs branches, which needs `StackMapTable`, which is
  the one thing `flint.classfile` does not do."
  (:require [flint.rt]
            [flint.jar :as jar]
            [flint.classfile :as cf]
            [flint.modmeta :as modmeta]
            [clojure.string :as str]))

;; --------------------------------------------------------------- the face
;;
;; The generated class is the artifact's OWN name for the four operations, and
;; that is its whole job: a consumer calls `flint.Artifact.boot`, never
;; `com.flint.rt.Sandbox.boot`, so which interpreter is underneath stays a
;; detail. Every body is one delegation, which is why this needs no dataflow
;; pass -- see `flint.classfile`.

(def ARTIFACT-CLASS "flint/Artifact")

(def ^:private SANDBOX "com/flint/rt/Sandbox")
(def ^:private BRIDGE "com/flint/rt/Sandbox$Bridge")
(def ^:private FN "com/flint/rt/Builtins$Fn")
(def ^:private D-SANDBOX "Lcom/flint/rt/Sandbox;")
(def ^:private D-BRIDGE "Lcom/flint/rt/Sandbox$Bridge;")
(def ^:private D-FN "Lcom/flint/rt/Builtins$Fn;")
(def ^:private D-NATIVES "Lcom/flint/rt/Sandbox$Natives;")
(def ^:private D-STRING "Ljava/lang/String;")

(defn chunks
  "Where to cut `image` so every piece fits one constant-pool entry.

  Returns `[[from to] ..]`. A 26 715-byte image is one piece and the 206 383-byte
  compiler is five, because the cut is on ENCODED length and the encoding is
  1.33x-1.42x on real images (`flint.classfile`)."
  [image]
  (let [n (flint.rt/b-count image)]
    (loop [at 0 out []]
      (if (>= at n)
        out
        (let [k (cf/chunk-bound image at cf/UTF8-MAX)
              k (if (<= k 0) 1 k)]        ; a single byte always fits; no zero step
          (recur (+ at k) (conj out [at (+ at k)])))))))

(defn- push-chunks
  "`String[]` of the image's pieces, built on the operand stack.

  BRANCH-FREE, and that is what keeps `flint.classfile` free of a dataflow pass: a
  `<clinit>` loop filling a `byte[]` would need a `StackMapTable` at the loop
  head, and the unrolled `bipush`/`bastore` alternative costs about four bytes of
  code per image byte against a `Code` attribute that also caps at 65 535 -- two
  methods for this program's image, and forty for the compiler's. Pushing N
  string constants costs 9 bytes each."
  [p image cuts]
  (let [[p cls] (cf/class-ref p "java/lang/String")]
    (loop [p p i 0 out [cf/SIPUSH (cf/u16 (count cuts))
                        cf/ANEWARRAY (cf/u16 cls)]]
      (if (>= i (count cuts))
        [p out]
        (let [[from to] (nth cuts i)
              [p s] (cf/blob p image from to)]
          (recur p (inc i)
                 (conj out cf/DUP cf/SIPUSH (cf/u16 i)
                       cf/LDC-W (cf/u16 s) cf/AASTORE)))))))

(defn artifact-class
  "`flint/Artifact.class`: the four operations, and the program itself as constant
  data on the class.

  ## The image is ON the class, not beside it

  No `flint/program.image` resource. The bytes are `CONSTANT_String` entries in
  this class's own constant pool, and `boot` hands them to the interpreter. A
  resource would have been fewer moving parts and is the wrong shape: it makes the
  bytecode a FILE the artifact contains rather than data the artifact is made of,
  and the CLR will carry it the same way this does, so the two stay uniform.

  ## One sandbox per class

  The four operations carry no handle -- `loop()` takes no argument -- so the
  sandbox it pumps lives in a static field, which is where a wasm module
  instance's memory lives. A host wanting two sandboxes takes a second class
  loader, the JVM's answer to instantiating a module twice."
  [image props-text]
  (let [cuts (chunks image)
        p (cf/pool)
        [p box] (cf/field-ref p ARTIFACT-CLASS "BOX" D-SANDBOX)
        [p mboot] (cf/method-ref p SANDBOX "boot"
                                 (str "(" D-BRIDGE D-NATIVES "[" D-STRING D-STRING ")" D-SANDBOX))
        [p mloop] (cf/method-ref p SANDBOX "loop" "()I")
        [p mlink] (cf/method-ref p SANDBOX "link" (str "(" D-STRING D-FN ")I"))
        [p mprop] (cf/method-ref p SANDBOX "prop" (str "(" D-STRING "[B" D-STRING ")I"))
        [p f] (cf/field p (+ cf/ACC-PRIVATE cf/ACC-STATIC) "BOX" D-SANDBOX)
        ;; The properties are constant data too, for the same reason: a runner
        ;; decides whether to load the artifact from what it says about itself, and
        ;; that answer must not depend on a second file being present.
        [p pstr] (cf/blob p props-text 0 (flint.rt/b-count props-text))
        [p chunk-code] (push-chunks p image cuts)
        ;; boot: the bridge becomes the system port, the image and the properties
        ;; go with it, and the sandbox comes back AND goes in the field. `dup` is
        ;; the whole cost of doing both.
        ;;
        ;; IT HAS TO COME BACK. A host owns more ports than the system one -- a
        ;; caller port is the ordinary case -- and a second bridge has to be
        ;; attached to something before the host can send on it. wasm covers the
        ;; same need with a thirteenth ABI function (`flint_install_port`), because
        ;; there is no object for a wasm module to hand back. Returning it does
        ;; leak the runtime's class name into the face, which is the one place this
        ;; target's four operations are not opaque.
        ;; THE RUNTIME ARRIVES HERE. The artifact carries the program's code and
        ;; not the interpreter, so `boot` takes the resolver that supplies the
        ;; builtins -- 88 of them for a one-line program, which is why it is one
        ;; resolver and not 88 `link` calls (`Sandbox.Natives`).
        [p m1] (cf/method p (+ cf/ACC-PUBLIC cf/ACC-STATIC) "boot"
                          (str "(" D-BRIDGE D-NATIVES ")" D-SANDBOX)
                          [(nth cf/ALOAD 0) (nth cf/ALOAD 1)
                           chunk-code
                           cf/LDC-W (cf/u16 pstr)
                           cf/INVOKESTATIC (cf/u16 mboot)
                           cf/DUP
                           cf/PUTSTATIC (cf/u16 box)
                           cf/ARETURN]
                          ;; bridge, resolver, array, dup, index, constant = 6
                          6 2)
        [p m2] (cf/method p (+ cf/ACC-PUBLIC cf/ACC-STATIC) "loop" "()I"
                          [cf/GETSTATIC (cf/u16 box)
                           cf/INVOKEVIRTUAL (cf/u16 mloop)
                           cf/IRETURN]
                          1 0)
        [p m3] (cf/method p (+ cf/ACC-PUBLIC cf/ACC-STATIC) "link" (str "(" D-STRING D-FN ")I")
                          [cf/GETSTATIC (cf/u16 box)
                           (nth cf/ALOAD 0) (nth cf/ALOAD 1)
                           cf/INVOKEVIRTUAL (cf/u16 mlink)
                           cf/IRETURN]
                          3 2)
        ;; prop is STATIC all the way down, and carries the property text with it,
        ;; so it answers before `boot` has run.
        [p m4] (cf/method p (+ cf/ACC-PUBLIC cf/ACC-STATIC) "prop" (str "(" D-STRING "[B)I")
                          [(nth cf/ALOAD 0) (nth cf/ALOAD 1)
                           cf/LDC-W (cf/u16 pstr)
                           cf/INVOKESTATIC (cf/u16 mprop)
                           cf/IRETURN]
                          3 2)]
    ;; No `<init>`: nothing constructs this and every member is static. A class
    ;; with no constructor is legal; `javac` writes one only because a source
    ;; class without one is a source class with a default one.
    (cf/class-file p (+ cf/ACC-PUBLIC cf/ACC-FINAL cf/ACC-SUPER)
                   ARTIFACT-CLASS "java/lang/Object"
                   [f] [m1 m2 m3 m4])))

;; --------------------------------------------------------------- properties
;;
;; FLATTENED HERE, where there is a printer, so nothing in the runtime needs a
;; reader. `modmeta/describe` builds a nested map and the JVM's `prop` answers
;; one name with one byte string; a runtime that had to get `:version` out of
;; EDN would need a reader to answer the simplest question an artifact is asked.
;; The whole map rides along under `meta` for a caller that has one.

(defn- flat
  "One level of `:compat` spelled `compat.abi`, and the rest by their own names.
  Values are printed the way EDN prints them, minus the quotes on a string: a
  version is `0.1.0` and not `\"0.1.0\"`, because a caller reading it into a
  buffer is reading text and not a form.

  `modmeta/canonical` AND NOT `pr-str`, which is not a style preference. A map's
  iteration order is not part of its value, and flint's maps are a CHAMP
  iterating in hash order while the JVM port's iterate in insertion order
  (`runtimes/jvm/README.md`, \"Known divergence\") -- so `pr-str` of
  `{:runtime 1 :value 1 :image 3}` gave a DIFFERENT `flint/props` on flint and on
  babashka, same length and different bytes. `modmeta/canonical` sorts keys,
  which is why the compatibility key it feeds was already stable and only the
  descriptive text was not. Measured: CRC 3278667205 against 2547322026 over 378
  identical-length bytes, which is precisely the failure a timestamp-free jar was
  meant to make visible."
  [m]
  (let [show (fn [v] (if (string? v) v (modmeta/canonical v)))]
    (reduce (fn [acc k]
              (let [v (get m k)]
                (if (map? v)
                  (reduce (fn [a k2] (assoc a (str (name k) "." (name k2)) (show (get v k2))))
                          acc (keys v))
                  (assoc acc (name k) (show v)))))
            {}
            (keys m))))

(defn props
  "`flint/props`: one `name\\tvalue` line each, sorted so two builds of the same
  program produce the same bytes."
  [m]
  (let [f (flat m)]
    (flint.rt/str->b
     (str/join "\n" (mapv (fn [k] (str k "\t" (get f k))) (sort (keys f)))))))

;; --------------------------------------------------------------- the target

(defn describe
  "What the artifact says about itself.

  `:abi` is the JVM's and not wasm's, and the difference is the point: a wasm
  module's compatibility key covers a linear-memory ABI this target does not
  have, so the two keys must not collide. `:image` is the bytecode version, which
  on this target IS the ABI -- there is no spliced runtime whose calling
  convention could change independently.

  `:gas-in-aot` is false and stays false however `:optimize` was set: this port
  emits compiled arities at LOAD time from the same bytecode, so the artifact is
  identical either way and the preference travels inside the image's flags
  (`src/flint/image.cljc`, `FLAG-PERF`)."
  [opts]
  (modmeta/describe
   {:abi {:runtime 1 :value 1 :image (:image-version opts 3)}
    :memory :unshared
    :gas-in-aot false
    :version (or (:version opts) "0.1.0")
    :exports ["boot" "loop" "link" "prop"]
    :imports []
    :units [{:name "flint.rt" :abi {:runtime 1 :value 1 :image (:image-version opts 3)}}]
    :builtins (:builtins opts 0)
    :meta (or (:meta opts) {})
    :features {:diagnostics false :snapshots true :capabilities true
               :loader false :aot true}}))

(defn properties
  "Everything `prop` will answer, as the flat text the artifact carries.

  ONE FUNCTION, called by every path that emits an artifact. It was inlined in
  `pack`, and when the class file became the artifact and `pack` stopped being on
  the way there, `prop(\"target\")` and `prop(\"image.bytes\")` came back absent --
  a whole class of property silently missing from the thing that had just been
  measured and declared working. `emit` below is the only door now."
  [image opts]
  (let [m (describe (assoc opts :bytes (flint.rt/b-count image)))
        ;; The bounds the sandbox boots with, inside the artifact rather than at
        ;; the caller: a consumer has an artifact, and whoever built it knew what
        ;; it needs. Plain numbers, so `Sandbox` parses no EDN to read them.
        extra {"target" "jvm"
               "image.bytes" (str (flint.rt/b-count image))
               "nursery" (str (:nursery opts 1048576))
               "heap" (str (:heap opts 67108864))
               "gas" (str (:gas opts 0))
               ;; CANONICAL, for the reason `flat` gives: `pr-str` of this map
               ;; differs by host and the artifact's bytes must not.
               "meta" (modmeta/canonical m)}
        text (flint.rt/b->str (props m))
        lines (str/join "\n" (mapv (fn [k] (str k "\t" (get extra k))) (sort (keys extra))))]
    (flint.rt/str->b (str text "\n" lines))))

(defn emit
  "THE ARTIFACT: one class file.

  `image` is what `flint.image/emit` wrote with an EMPTY slot map -- native slots
  belong to whichever module linked the builtins, and this target resolves every
  one of them BY NAME through the resolver `boot` is handed, so a slot written
  here would be a number meaningful nowhere."
  [image opts]
  (artifact-class image (properties image opts)))

(defn pack
  "The same class, in a jar with the HOST's runtime, so `java -jar` works.

  A CONVENIENCE, not the artifact. The artifact is what `emit` returns; this puts
  a copy of the interpreter next to it for a consumer who has not got one, the way
  a distribution ships a JRE beside an application. `runtime-jar` is
  `dist/flint-rt.jar`, prebuilt by `bin/build-jvm-runtime`, and it is appended to
  rather than rebuilt -- `flint.jar/append` never inflates anything."
  [runtime-jar image opts]
  (jar/append runtime-jar
              [{:name (str ARTIFACT-CLASS ".class") :bytes (emit image opts)}]))
