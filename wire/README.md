# `wire/` — cross-language golden vectors

This directory holds test vectors that are read by more than one language. Both files here describe
the payload a phone reads off the QR code the Mac app shows:

| File | What it pins |
|---|---|
| `enroll-payload-vectors.json` | Inputs, and the text they must **encode to**. The encoder. |
| `enroll-payload-reject-vectors.json` | Text that must be **refused**, and the refusal it must produce. The decoder. |

Both are needed, and the second one is the half that faces the camera. The accept vectors cannot
tell a decoder that shrugs at a version it does not know from one that refuses it — measured: with
`Decode` mutated to accept any version below 11, every accept vector, the round trip and the
field-order test still pass, and only the reject vectors go red. The decoder parses whatever a
stranger holds in front of a lens, before any authentication exists, so what it refuses is as much
of the contract as what it accepts.

## The rule

**The bytes in these files are the contract, not a snapshot of what the code currently does.**

Two implementations of the same wire format — the Go encoder in `bridge/internal/enroll` and the
Kotlin decoder in the Android app — cannot be changed in one commit and cannot be compiled against
each other. Nothing else in the build connects them. The vectors are the connection: each side
encodes the listed inputs and asserts, byte for byte, that it produces the listed `text`, and refuses
every entry in the reject file.

So a change that alters any `text` in either file is a **breaking wire change**, not a tidy-up. Both
sides stop pairing with every build of the other. Treat it as one:

- Bump `enroll.Version`, so a phone meeting the older format says "this Mac is newer" instead of
  misreading it.
- Add vectors for the new version rather than editing the old ones, where the old version is still
  supposed to be readable — and move the superseded version's accept vector into the reject file if it
  must now be refused, so the refusal is pinned rather than assumed.
- Land the Go and Kotlin sides together, and say so in the pull request title.

The predecessor project had exactly this shape — two hand-written implementations of one wire
format — and they drifted. The symptom was not a build failure. It was a phone that would not pair,
found by a person holding it.

## Regenerating

The generator lives behind a build tag so it cannot run as part of an ordinary test run and quietly
rewrite the contract to agree with whatever the encoder does today:

```bash
cd bridge && go test ./internal/enroll/ -tags vectors -run 'TestWrite.*Vectors'
```

Run it only when the format is being changed deliberately. Never to make a red test green — a red
vector test is the mechanism working.

## `enroll-payload-vectors.json`

A JSON array. Each entry is one payload, given as inputs plus the text they must encode to:

| Field | Meaning |
|---|---|
| `name` | Identifies the vector in a failure message. |
| `note` | What this vector is here to pin. Prose; no code reads it. |
| `host` | The host the phone dials, as text. |
| `port` | The bridge's TLS port. |
| `fingerprint_hex` | 32 bytes: SHA-256 of the bridge certificate's DER. |
| `token_hex` | 32 bytes: the one-time enrolment secret. |
| `expiry_unix` | Unix **seconds**, carried on the wire as a `uint32`. |
| `text` | What the encoder must produce: standard, **padded** base64. |

The fingerprints and tokens are fixtures — arithmetic ramps and fills, not secrets, and not derived
from any. Real ones are 32 bytes from a cryptographic random source and never appear in a repository.

The four vectors cover a short host, the longest legitimate DNS name (253 bytes, which is what pins
the length prefix as a `uint16` rather than a byte), a host with a multi-byte character (which pins
that the prefix counts bytes and not characters), and every field at its ceiling — the largest port
a `uint16` holds and the last second a `uint32` expiry can name, 2106-02-07T06:28:15Z.

## `enroll-payload-reject-vectors.json`

A JSON array of text that must not decode:

| Field | Meaning |
|---|---|
| `name` | Identifies the vector in a failure message. |
| `refusal` | The **kind** of refusal required. Not a message — each language maps it onto whatever it raises. |
| `note` | What this vector is here to pin, and what a decoder that accepts it would have got wrong. |
| `text` | The input. Standard padded base64 in every case except the two that deliberately are not. |

The refusal kinds, and what each means:

| `refusal` | Required behaviour |
|---|---|
| `not-standard-base64` | The text is not standard padded base64 (URL-safe alphabet, or missing padding). |
| `empty-payload` | Zero bytes after base64 decoding. |
| `unsupported-version` | The version byte is not one this build speaks. Report it as a version, never parse on. |
| `too-short` | Shorter than the fixed minimum, before any field is read. |
| `host-length-over-ceiling` | The host length exceeds `MaxField` (4096). Refuse **before** allocating for it. |
| `empty-host` | The host length is zero. |
| `length-mismatch` | The buffer is not exactly the length its own header describes — short, or with trailing bytes. |
| `host-not-utf8` | The host bytes are not valid UTF-8. |

A decoder must refuse every entry. It need not produce the same message, and it need not distinguish
`length-mismatch` from `too-short` in its own error type — but it must not return a value.

## Reading these files from the Android side

**Read them from the repository root. Do not copy either file into the app module.** A copy is
exactly how two implementations drift: the copy is what the Kotlin test pins, the original is what
the Go test pins, and nothing notices they have diverged until a phone will not pair.

Gradle does not put the repository root on a test's working directory, so wire it explicitly. In the
app module's `build.gradle.kts`:

```kotlin
tasks.withType<Test>().configureEach {
    systemProperty("wire.dir", rootProject.rootDir.resolve("wire").absolutePath)
}
```

and in the test:

```kotlin
private val wireDir = File(System.getProperty("wire.dir") ?: error("wire.dir is not set"))
private val vectors = wireDir.resolve("enroll-payload-vectors.json")
```

`rootProject.rootDir` is the repository root for both a local build and CI, and the `error(...)`
makes a missing property a failure rather than a silently skipped test. If a task genuinely needs the
files on the classpath instead — an instrumented test on a device, where the repository is not
present — use a `Copy` task that reads from `rootProject.rootDir.resolve("wire")` into that task's
generated resources, so the copy is produced by the build on every run and cannot be edited by hand:

```kotlin
val copyWireVectors by tasks.registering(Copy::class) {
    from(rootProject.rootDir.resolve("wire")) { include("*.json") }
    into(layout.buildDirectory.dir("generated/wire"))
}
```

Never `cp` them into `src/`.

The wire layout itself, and the reasoning behind each of its decisions, is documented at the top of
`bridge/internal/enroll/format.go`.
