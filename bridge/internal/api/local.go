package api

import (
	"context"
	"errors"
)

// This file is what the LOCAL control socket may ask of the handler, and it is deliberately short.
//
// **None of it is a new code path**, and that is the rule this file is held to rather than a count of
// methods. RestoreFit dispatches the ordinary resize verb with no box width, which is the same request
// the phone's own off press sends; AgtermReachable dispatches the ordinary sessions verb and reports
// only whether it worked. So the restore has one implementation, one owner of the store, and one place
// where the restore point is consumed, and "is agterm up" has one definition. A second implementation
// "for the local case" is exactly how two paths drift until one of them is wrong.
//
// See internal/control for why a unix socket needs no authentication of its own.

// FitInForce reports whether the bridge is currently holding a fit.
//
// Read from the published flag rather than the store, because this is called by the control socket's
// goroutine while a calibration may be running on the phone's. See serialize.go. The local command
// uses it to be honest about doing nothing rather than resizing a window on a guess.
func (h *Handler) FitInForce() bool {
	inForce, _ := h.fitNow()
	return inForce
}

// RestoreFit puts the owner's window back, through the phone's own off path.
//
// A resize request with no box width means "stop adapting and put the window back" - that reading is
// established in the resize handler and is not re-implemented here.
func (h *Handler) RestoreFit(ctx context.Context) error {
	resp := h.Handle(ctx, Request{Verb: VerbResize})
	if !resp.OK {
		// The bridge's own refusal text, passed through whole. It names what went wrong with the
		// window, which is the thing the person at the keyboard can act on.
		if resp.Error != "" {
			return errors.New(resp.Error)
		}
		return errors.New("the window could not be restored")
	}
	return nil
}

// AgtermReachable reports whether agterm is answering, for the menu bar's `status`.
//
// **The ordinary sessions request, and only its ok flag is read.** A bridge whose agterm has gone
// away is running, listening and useless, and that is a different thing to tell an owner than "the
// bridge is not running" - which is what they would otherwise conclude from a menu that shows a
// paired phone and nothing working.
//
// Nothing about what came back leaves this function: not the session list, not the count, not the
// error text. The caller asked a yes-or-no question and the answer to it is the only thing that is
// any of its business - the session names are the owner's work, and a menu-bar tooltip is not where
// they belong.
func (h *Handler) AgtermReachable(ctx context.Context) bool {
	return h.Handle(ctx, Request{Verb: VerbSessions}).OK
}
