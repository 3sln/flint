(ns flint.jvm
  "`:to :jvm`: a program as ONE CLASS FILE.

  ## What the artifact is

  `flint/Artifact.class`, and nothing else:

  | | |
  | --- | --- |
  | the program | the bytecode image, as `CONSTANT_String` entries in the pool |
  | what it says about itself | a `com.3sln.flint.meta` class ATTRIBUTE, canonical EDN |
  | its face | `boot`, `loop`, `link` -- three static methods |
  | its compiled arities | NOT YET. That is the AOT half; see the report. |

  ## SELF-CONTAINED MEANS ALL OF THE PROGRAM'S CODE

  It does NOT mean \"runs standalone\". The interpreter, the collector and the
  builtins are the HOST's, resolved by name out of its own table when the image
  loads -- 88 of them for `(ns t) (defn main [args] \"x\")`, which is
  `clojure.core`'s reach and not the program's -- and `link` overrides one before
  `boot`. So the deployment is `java -cp flint-rt.jar:. ...`, and an artifact on its
  own runs nothing.

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
            [flint.modmeta :as modmeta]))

;; --------------------------------------------------------------- the face
;;
;; The generated class is the artifact's OWN name for the three operations, and
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
  "`flint/Artifact.class`: THE THREE OPERATIONS, the program as constant data, and
  the metadata as a class attribute.

  ## The image is ON the class, not beside it

  No resource. The bytes are `CONSTANT_String` entries in this class's own constant
  pool, and `boot` hands them to the interpreter. A resource would have been fewer
  moving parts and is the wrong shape: it makes the bytecode a FILE the artifact
  contains rather than data the artifact is made of, and the CLR carries it the
  same way, so the two stay uniform.

  ## There is no `prop`

  It was the fourth operation until 2026-09-24. The metadata is a class ATTRIBUTE
  named `flint.modmeta/section-name` -- the same string the wasm custom section
  uses -- because all three containers carry metadata a reader gets at WITHOUT
  executing anything, and a call never can.

  An ANNOTATION was the alternative and is worse here, for two reasons that both
  come back to the contract's own words. `clazz.getAnnotation` needs the class
  LOADED and the annotation type on the classpath, so it is not readable without
  executing the artifact's container in the way an attribute is -- `javap`, a build
  system or `flint inspect` can read an attribute out of the bytes with no JVM at
  all. And the name could not match: an annotation's is a Java type name, so `3sln`
  -- not a legal identifier start -- forces `com._3sln.flint.Meta` where the wasm
  section says `com.3sln.flint.meta`. One fact, two spellings, which is the shape
  this project keeps paying for.

  ## One sandbox per class

  The three operations carry no handle -- `loop()` takes no argument -- so the
  sandbox it pumps lives in a static field, which is where a wasm module
  instance's memory lives. A host wanting two sandboxes takes a second class
  loader, the JVM's answer to instantiating a module twice."
  [image meta-text]
  (let [cuts (chunks image)
        p (cf/pool)
        [p box] (cf/field-ref p ARTIFACT-CLASS "BOX" D-SANDBOX)
        [p mboot] (cf/method-ref p SANDBOX "boot"
                                 (str "(" D-BRIDGE "[" D-STRING ")" D-SANDBOX))
        [p mloop] (cf/method-ref p SANDBOX "loop" "()I")
        [p mlink] (cf/method-ref p SANDBOX "link" (str "(" D-STRING D-FN ")V"))
        [p f] (cf/field p (+ cf/ACC-PRIVATE cf/ACC-STATIC) "BOX" D-SANDBOX)
        [p chunk-code] (push-chunks p image cuts)
        ;; boot: the bridge becomes the system port, the image goes with it, and the
        ;; sandbox comes back AND goes in the field. `dup` is the whole cost of both.
        ;;
        ;; IT HAS TO COME BACK. A host owns more ports than the system one -- a
        ;; caller port is the ordinary case -- and the bridge is how it reaches them,
        ;; so something has to hold the sandbox. wasm covers the same need with an
        ;; extra ABI function, because there is no object for a module to hand back.
        ;;
        ;; ONE ARGUMENT, and no resolver. An earlier draft had `boot` take a
        ;; name-to-builtin resolver so the host could supply the runtime, on the
        ;; reasoning that 88 `link` calls is no API. The record settles it the other
        ;; way: `Img.load` resolves against the host's own table and `link` is an
        ;; override, so the normal case is zero calls and needs no parameter.
        [p m1] (cf/method p (+ cf/ACC-PUBLIC cf/ACC-STATIC) "boot"
                          (str "(" D-BRIDGE ")" D-SANDBOX)
                          [(nth cf/ALOAD 0)
                           chunk-code
                           cf/INVOKESTATIC (cf/u16 mboot)
                           cf/DUP
                           cf/PUTSTATIC (cf/u16 box)
                           cf/ARETURN]
                          ;; bridge, array, dup, index, constant = 5
                          5 1)
        [p m2] (cf/method p (+ cf/ACC-PUBLIC cf/ACC-STATIC) "loop" "()I"
                          [cf/GETSTATIC (cf/u16 box)
                           cf/INVOKEVIRTUAL (cf/u16 mloop)
                           cf/IRETURN]
                          1 0)
        ;; STATIC, and it must PRECEDE `boot`: natives resolve exactly once when the
        ;; image loads, so this cannot read `BOX` -- there is no sandbox yet when a
        ;; host registers an override. `Sandbox.link` refuses afterwards.
        [p m3] (cf/method p (+ cf/ACC-PUBLIC cf/ACC-STATIC) "link" (str "(" D-STRING D-FN ")V")
                          [(nth cf/ALOAD 0) (nth cf/ALOAD 1)
                           cf/INVOKESTATIC (cf/u16 mlink)
                           cf/RETURN]
                          2 2)
        [p attr] (cf/attribute p modmeta/section-name meta-text)]
    ;; No `<init>`: nothing constructs this and every member is static. A class with
    ;; no constructor is legal; `javac` writes one only because a source class
    ;; without one is a source class with a default one.
    (cf/class-file p (+ cf/ACC-PUBLIC cf/ACC-FINAL cf/ACC-SUPER)
                   ARTIFACT-CLASS "java/lang/Object"
                   [f] [m1 m2 m3] [attr])))

;; ----------------------------------------------------------------- metadata
;;
;; THE CANONICAL EDN, VERBATIM, and no flattening. There was a flattener here that
;; turned the nested map into `name<TAB>value` lines, because `prop(name, buf)` had
;; to answer one name with one byte string and a runtime that read EDN would need a
;; reader to answer `prop("version")`. `prop` is gone
;; (`DECISIONS.md#four-operations`) and so is the reason: an attribute is read from
;; OUTSIDE, by something that has a reader already -- `bin/check-four-ops` reads it
;; with `clojure.edn/read-string` straight off the class bytes.
;;
;; `modmeta/canonical` and not `pr-str`. A map's iteration order is not part of its
;; value, and flint's maps are a CHAMP iterating in hash order while the JVM port's
;; iterate in insertion order (`runtimes/jvm/README.md`, "Known divergence") -- so
;; `pr-str` produced a DIFFERENT artifact on flint and on babashka, same length and
;; different bytes. Measured before it was fixed: CRC 3278667205 against 2547322026
;; over 378 identical-length bytes. `canonical` sorts keys, which is why the
;; compatibility key it already fed was stable and only the descriptive text was not.

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
    :exports ["boot" "loop" "link"]
    :imports []
    :units [{:name "flint.rt" :abi {:runtime 1 :value 1 :image (:image-version opts 3)}}]
    :builtins (:builtins opts 0)
    :meta (or (:meta opts) {})
    :features {:diagnostics false :snapshots true :capabilities true
               :loader false :aot true}}))

(defn metadata
  "What goes in the `com.3sln.flint.meta` attribute: the describe map, canonical.

  ONE FUNCTION, called by every path that emits an artifact. The properties were
  built inline in `pack` once, and when the class file became the artifact and
  `pack` stopped being on the way there, two of them came back absent from the
  thing that had just been measured and declared working. `emit` is the only door.

  NO BOOT BOUNDS. An earlier version carried `nursery`, `heap` and `gas` here for
  `boot` to read. Nothing set them, and nothing could apply them once `prop` was
  gone -- so they were metadata the runtime carried and never read, which is the
  failure this project has a commit message about. `Sandbox` uses its own defaults
  and the attribute says nothing it cannot act on."
  [image opts]
  (flint.rt/str->b
   (modmeta/canonical (describe (assoc opts :bytes (flint.rt/b-count image))))))

(defn emit
  "THE ARTIFACT: one class file.

  `image` is what `flint.image/emit` wrote with an EMPTY slot map -- native slots
  belong to whichever module linked the builtins, and this target resolves every
  one of them BY NAME through the resolver `boot` is handed, so a slot written
  here would be a number meaningful nowhere."
  [image opts]
  (artifact-class image (metadata image opts)))

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
