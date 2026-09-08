# `wire/` — cross-language golden vectors

This directory holds test vectors that are read by more than one language. Right now that is one
file, `enroll-payload-vectors.json`: the payload a phone reads off the QR code the Mac app shows.

## The rule

**The bytes in these files are the contract, not a snapshot of what the code currently does.**

Two implementations of the same wire format — the Go encoder in `bridge/internal/enroll` and the
Kotlin decoder in the Android app — cannot be changed in one commit and cannot be compiled against
each other. Nothing else in the build connects them. The vectors are the connection: each side
encodes the listed inputs and asserts, byte for byte, that it produces the listed `text`.

So a change that alters any `text` in this file is a **breaking wire change**, not a tidy-up. Both
sides stop pairing with every build of the other. Treat it as one:

- Bump `enroll.Version`, so a phone meeting the older format says "this Mac is newer" instead of
  misreading it.
- Add vectors for the new version rather than editing the old ones, where the old version is still
  supposed to be readable.
- Land the Go and Kotlin sides together, and say so in the pull request title.

The predecessor project had exactly this shape — two hand-written implementations of one wire
format — and they drifted. The symptom was not a build failure. It was a phone that would not pair,
found by a person holding it.

## Regenerating

The generator lives behind a build tag so it cannot run as part of an ordinary test run and quietly
rewrite the contract to agree with whatever the encoder does today:

```bash
cd bridge && go test ./internal/enroll/ -tags vectors -run TestWriteVectors
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

The wire layout itself, and the reasoning behind each of its decisions, is documented at the top of
`bridge/internal/enroll/format.go`.
