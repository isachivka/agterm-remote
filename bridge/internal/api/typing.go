package api

import (
	"context"

	"dev.isachivka.bewareofsugar/bridge/internal/keys"
)

// typing sends one keystroke, or a short run of text, to a session.
//
// **The only verb in this bridge that puts bytes on a pty.** `resize` changes the shape of a window
// and can carry nothing into it; this runs whatever the owner typed. Authorised by the owner on
// 2026-07-29, reversing REQ-0008's original ruling — see the allowlist entry, which records it as
// their decision rather than as an inevitability.
//
// # What the caller may say, and what it may not
//
// A named key from a closed set, or text with no control character in it. Never raw bytes, never an
// escape sequence, never both at once. The checking lives in `internal/keys` and not here: it is pure,
// so it can be tested exhaustively without a Mac, and it exists in exactly one place rather than at
// each call site.
//
// **Exactly one of text and key**, refused rather than resolved by precedence. A request carrying
// both is a caller this bridge does not understand, and picking one of them would be inventing an
// intention on its behalf.
func (h *Handler) typing(ctx context.Context, req Request) Response {
	// Both of these are about the request too, by the test on RefusalContent: a malformed session id
	// and a request carrying neither or both of text and key fail identically against a healthy
	// laptop. Neither is reachable from the owner's own app - they are a caller getting the protocol
	// wrong - but a refusal's KIND is a fact about the refusal, not about who is likely to see it.
	if err := validateSessionID(req.Session); err != nil {
		return refuseContent(err.Error())
	}
	// **Exactly one of the three.** Counted rather than nested, so adding a fourth cannot leave a
	// combination nobody rejected - REQ-0017, where `paste` joined `text` and `key`.
	given := 0
	for _, field := range []string{req.Text, req.Key, req.Paste} {
		if field != "" {
			given++
		}
	}
	if given != 1 {
		return refuseContent("send exactly one of text, paste or key")
	}

	var (
		out string
		err error
	)
	switch {
	case req.Key != "":
		out, err = keys.Key(req.Key)
	case req.Paste != "":
		out, err = keys.Paste(req.Paste)
	default:
		out, err = keys.Text(req.Text)
	}
	if err != nil {
		// The message names what was wrong and, for a key, what exists instead. The caller is
		// authenticated and is our own app; a description helps it and reveals nothing.
		//
		// **Marked as being about the CONTENT**, REQ-0017: nothing is wrong with the laptop, and this
		// request would fail identically on a perfectly healthy one. It is the difference between the
		// phone leaving the owner where they are with their text intact and the phone tearing the
		// screen down as though the connection had died - which is what it used to do to somebody who
		// had pasted a message with a line break in it.
		return refuseContent(err.Error())
	}

	// **The same resolver the screen path uses.** Not a pane read from somewhere else, not a default
	// applied here: one function, one answer, so what the owner is looking at and what he types into
	// cannot come apart. REQ-0032, and the defect it was written for.
	pane, err := paneFor(req)
	if err != nil {
		return refuseContent(err.Error())
	}

	if err := h.client.Type(ctx, req.Session, out, pane); err != nil {
		// unreadable rather than fail: a pane that vanished on the laptop is a thing that CHANGED
		// there, not the connection breaking, and it has its own sentence.
		return unreadable(describe(err))
	}
	// No echo, and no screen read. What the owner typed becomes visible when the NEXT poll returns it
	// from the laptop, which is the only source of terminal content in this design. Replying with the
	// screen here would make the phone's own keystroke look like output the laptop produced, and it
	// would do so at the one moment nobody could tell the difference.
	return Response{OK: true}
}
