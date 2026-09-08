package api

import (
	"context"
	"encoding/base64"

	"dev.isachivka.bewareofsugar/bridge/internal/dropoff"
)

// MaxRequestBytes is the ceiling on one request line, and it is derived rather than chosen.
//
// **It lives here because the wire is what needs it, and it is computed from the file bound so the two
// cannot drift apart.** A file arrives base64-encoded inside the JSON line, so the line has to hold
// 4/3 of the file plus the envelope. Two independent magic numbers would eventually disagree, and the
// symptom would be a file the bridge accepts in principle and refuses in practice.
//
// **This raised a security bound and that should not pass unremarked.** It was 64 KB with the note
// "far above any real request", which was true until a request could carry a photograph. What is
// preserved is the property rather than the number: nothing a caller sends can grow a buffer without
// limit, and the limit is still small next to the machine. What is given up is that an authenticated
// caller — a phone that has been taken — can now make the bridge buffer megabytes instead of kilobytes,
// once per request, on one connection. That is the trade, and it is the file feature's cost.
const MaxRequestBytes = dropoff.MaxFileBytes*4/3 + (64 << 10)

// file writes what the phone sent into a directory the BRIDGE chooses, and returns the path.
//
// # The third kind of write
//
// Geometry changed a window's shape. Typing put keystrokes on a pty. This puts data on the owner's
// filesystem: **whoever gets past pairing can write bytes to their disk.** A smaller escalation than
// typing, which can already run anything at all — but a different kind, and stated here rather than
// left for a reader to infer from the absence of an objection.
//
// # No new agterm verb, and the allowlist is untouched
//
// The bridge writes the file itself with `os.OpenFile`. Nothing is asked of agterm, so
// `emitted_commands_test.go` and its four literals are unchanged — worth saying because a guard that
// stays green is easy to read as a guard that approved something. It did not; it was not consulted.
//
// # What comes back is a path, and the phone does not run it
//
// The reply carries the path and nothing else. The app puts it in the DRAFT and the owner presses
// Enter themselves — choosing a file must never execute a command, which is the same reasoning that
// keeps the typing bar shut until they open it.
func (h *Handler) file(_ context.Context, req Request) Response {
	if req.Name == "" {
		return fail("the file has no name")
	}
	if req.Content == "" {
		return fail("the file has no content")
	}

	// Strict encoding, and padding required: this is our own app on the other end, and a decoder that
	// tolerates variants is a decoder whose output depends on which variant arrived.
	raw, err := base64.StdEncoding.DecodeString(req.Content)
	if err != nil {
		// The message says what was wrong with the ENCODING and never quotes the content, which is the
		// owner's file. Same rule as their screen and their keystrokes.
		return fail("the file content is not valid base64")
	}

	path, err := dropoff.Save(dropoff.Root, req.Name, raw)
	if err != nil {
		// dropoff's errors name the bound or the character at fault and never the content.
		return fail(err.Error())
	}
	return Response{OK: true, Path: path}
}
