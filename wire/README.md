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
| `version` | Which layout `text` is in. **Both are in the set**: 2 is what a Mac mints, 1 is decode-only. |
| `host` | The host the phone dials, as text. |
| `port` | The port the phone dials. Whose port it is depends on `scheme`. |
| `scheme` | How the phone OPENS that address: 1 plain (`ws`), 2 TLS (`wss`). Added at version 2; a version 1 payload carries no such byte and **means** 1. |
| `fingerprint_hex` | 32 bytes: SHA-256 of the bridge certificate's DER. |
| `token_hex` | 32 bytes: the one-time enrolment secret. |
| `expiry_unix` | Unix **seconds**, carried on the wire as a `uint32`. |
| `text` | What the encoder must produce: standard, **padded** base64. |
| `dial_address` | **Derived, not encoded.** `host` and `port` joined the way something about to connect must join them. Nothing in `text` carries it. |
| `dial_url` | **Derived too**, from `scheme`, `host` and `port`: the whole string something opens. See below. |

The fingerprints and tokens are fixtures — arithmetic ramps and fills, not secrets, and not derived
from any. Real ones are 32 bytes from a cryptographic random source and never appear in a repository.

The version 2 vectors cover a short host, the longest legitimate DNS name (253 bytes, which is what pins
the length prefix as a `uint16` rather than a byte), a host with a multi-byte character (which pins
that the prefix counts bytes and not characters), an IPv6 literal, and every field at its ceiling —
the largest port a `uint16` holds and the last second a `uint32` expiry can name,
2106-02-07T06:28:15Z, and between them they carry both schemes — a set that exercised only one would
leave the field untested, which `EnrollPayloadTest` asserts against directly.

Two more are version 1, and they are the whole reason the old layout still decodes: a decoder that
refused them would report *this Mac is newer than this app* about a code it can read perfectly, which
is the wrong sentence and one nobody can act on.

### `dial_address`, and why a decoder test is not enough

`dial_address` is the only field here that is **not** part of the encoded bytes. It is derived from
`host` and `port`, and it exists because the payload's host field is a bare string: `2001:db8::1`,
never `[2001:db8::1]`. The brackets are deliberately absent from the wire — one host, one spelling,
no display-versus-dial ambiguity in the bytes.

The consequence is a bug one layer past decoding. An IPv6 accept vector on its own would only pin
that both sides read the host as `2001:db8::1`, which they would do anyway. What goes wrong is the
next line, in whatever builds an address to connect to:

```
host + ":" + port   ->  "2001:db8::1:8443"     not an address
```

That is the obvious two lines, it is what a dialler written from the field list does, and nothing in
the format says otherwise. The symptom is a phone that will not pair, with nothing visible in the QR
code to explain it — which is the failure shape this whole directory exists to prevent.

So the rule has one implementation per language and one cross-language enforcement:

- Go: `enroll.Payload.DialAddress()`, which is `net.JoinHostPort`.
- Kotlin: `EnrollPayload.dialAddress`, asserted against this field by `EnrollPayloadTest`.
- **The vectors are the connection.** A Go helper the Android app cannot import proves nothing about
  the Android app.

Each side must produce `dial_address` from the decoded `host` and `port` and compare it byte for
byte, exactly as it does with `text`. The rule, stated without reference to either language: bracket
the host if and only if it contains a colon, then append `:` and the port.

### `dial_url`, and the second constant that was wrong for somebody

`dial_address` pins the bracketing. `dial_url` pins the bracketing **and** the scheme together, and it
is a separate field because the two mistakes compose: a dialler builds one string, and getting either
half wrong produces a phone that will not pair with nothing on the wire to say why.

The scheme is in the payload at all because it was a constant twice and both constants were wrong for
somebody. `wss://` cannot reach a bridge with nothing in front of it. `ws://` cannot reach a router
that publishes a Mac by proxying it — which is the deployment this project was written for, and which
shipped broken because the direct route was substituted for the proxied one in every test.

It could not be inferred and must not be guessed. Trying one and falling back to the other leaks
nothing — the token travels only after the inner handshake — and was still rejected: it doubles the
worst-case connect and replaces a fact the owner knows with a heuristic.

- Go: `enroll.Payload.DialURL()`.
- Kotlin: `EnrollPayload.dialUrl`, and `ConnectionProfile.dialUrl`, which must agree — the address
  enrolment dialled and the address the terminal dials are the same address.

### What version 2 changed

The scheme byte, immediately after the version and before anything length-prefixed, so it is readable
the moment the version is known and no length field has to be trusted to find it.

**Every version 1 `text` in the accept file changed**, because those vectors are now emitted at
version 2. The two `legacy-v1-*` vectors are the old layout, kept so it stays decodable, and the
`v2-payload-labelled-v1` reject vector holds the rule that the version chooses the layout rather than
the two being interchangeable.

Adding `dial_address` did **not** change any `text`. It is additive, and the four vectors that predate it
carry byte-identical text — proven by the accept-vector test, which still pins those bytes.

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
| `unsupported-scheme` | The scheme byte is not one this build knows. **Not** a malformed payload: it is a Mac that can arrange something this phone cannot, and it must never be treated as either known scheme. |
| `too-short` | Shorter than the fixed minimum, before any field is read. |
| `host-length-over-ceiling` | The host length exceeds `MaxField` (4096). Refuse **before** allocating for it. |
| `empty-host` | The host length is zero. |
| `length-mismatch` | The buffer is not exactly the length its own header describes — short, or with trailing bytes. |
| `host-not-utf8` | The host bytes are not valid UTF-8. |

A decoder must refuse every entry. It need not produce the same message, and it need not distinguish
`length-mismatch` from `too-short` in its own error type — but it must not return a value. The one
kind it **must** keep separate is `unsupported-version`: "this Mac is newer than this app" and "that
is not a pairing code" are different things to tell an owner, with different remedies, and the accept
vectors cannot tell a decoder that reports the difference from one that shrugs.

### Two things a base64 library will not do for you

Both were written here as assumptions and both turned out to be wrong in one direction or the other,
which is what the reject vectors are for.

- **`java.util.Base64.getDecoder()` does not refuse missing padding.** Measured on JDK 21: `'='` is
  "accepted and interpreted as the end of the encoded byte data, but is not required", so the
  unpadded rendering decodes as happily as the padded one. What it does refuse is a final unit of the
  wrong length. Go's `base64.StdEncoding` refuses the unpadded form, so **a Java-side decoder has to
  check the length itself** — a length that is not a multiple of four is not standard padded base64 —
  or the same string is a pairing code on one machine and not on the other. The `unpadded` vector is
  the only thing that says so.
- **Go's decoder skips `\r` and `\n` where Java's refuses them**, which no vector can pin, because a
  vector is one string and this is a disagreement about what surrounds it. Nothing in this project
  emits either, so Go is simply the more permissive side and it is recorded rather than fixed.
- **The Kotlin decoder refuses text longer than 8,192 characters and no other reader does.** It is a
  bound on the LENGTH OF THE INPUT rather than a rule about the format, and only one of the three
  readers has an input somebody else chose: the Kotlin one is handed every frame a camera sees and
  every string a clipboard holds, and `Base64.getDecoder().decode` allocates from that length before
  any field has been looked at. Go decodes codes its own encoder minted, on the machine that minted
  them; the Swift reader decodes what it has just asked the bridge for. There is no vector for it
  because there is no input any implementation can legitimately meet at that size - the largest code
  this format can express is 5,560 characters, and a QR symbol tops out at 2,953 bytes. The Kotlin
  refusal kind is `text-over-ceiling`, and `EnrollPayloadTest` declares it as this side's own so that
  every OTHER kind is still required to have a vector behind it.

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
