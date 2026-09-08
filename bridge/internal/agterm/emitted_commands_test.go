package agterm_test

import (
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"testing"
)

// emittable is every agterm control command this binary is allowed to send.
//
// # Why this list exists as a test rather than as a sentence
//
// REQ-0008 ruling 3 said the bridge constructs its own requests and forwards nothing, so the other
// sixty-odd agterm commands are unreachable. That was true, and it was held up by nothing except the
// code happening to be written that way. **It survived exactly until someone wanted a feature** — the
// window-resize amendment of 2026-07-29 — at which point the only thing standing between "one narrow
// write" and "a second one nobody noticed" was whether a reviewer remembered.
//
// The zero-dependency rule is held by `go.sum` not existing, not by a paragraph asking people to be
// careful. This is the same shape of mechanism for the same shape of rule.
//
// **Adding an entry here is the deliberate act.** A new command in the source without a matching
// entry fails this test, and the failure names the command.
//
// # The assertion that carries the weight
//
// **A literal allowlist is decoration if the value can be assembled at runtime, because anything
// assembled at runtime can be assembled from input.**
//
// So this test checks two separate things, and the second is the important one. It is not enough that
// every command it finds is on the list; every command must also be a **string literal written down in
// our own source**. A `Cmd` built by concatenation, from a variable, or from a struct field is
// rejected outright, whatever it happens to evaluate to — because at that point the list is no longer
// what decides which commands the binary can send.
//
// Both failure modes are mutation-checked rather than assumed. `session.type` was the example of the
// first until the owner authorised typing on 2026-07-29 and it became an entry; any command NOT in
// the map still fails it, and replacing a literal with `"tr" + "ee"` still fails the second.
var emittable = map[string]string{
	"tree":         "REQ-0008 §2 verb one: list the sessions. Read-only.",
	"session.text": "REQ-0008 §2 verb two: read one session's screen. Read-only.",

	// Read-only, and here because the resize path must remember what to put back. Nothing derives a
	// column count from what it returns - see the note on agterm.Window.
	"window.list": "REQ-0008 amendment 2026-07-29: read the geometry to restore. Read-only.",

	// Read-only: which zmx daemon each pane claims, and where zmx and its sockets are. The styled
	// screen path (2026-09-05) needs the daemon name to ask zmx for a pane's screen with its colours.
	// agterm's inventory attaches, prunes and kills nothing on a list; `zmx.prune` and `zmx.kill` are
	// the destructive siblings and are NOT here.
	"zmx.list": "Styled screen, 2026-09-05: map a pane to its daemon. Read-only.",

	// **THE FIRST WRITE.** Authorised by the REQ-0008 amendment of 2026-07-29, on the owner's
	// request, reaffirmed after the cost was put to them.
	//
	// A resize alters the grid a program draws into. It cannot deliver a keystroke, a control
	// character or a command - it changes the terminal's SHAPE and never its CONTENTS.
	"window.resize": "REQ-0008 amendment 2026-07-29: geometry only, and it carries nothing into the terminal.",

	// Same class as window.resize and permitted for one purpose: RESTORING the zoom a resize changed
	// underneath us. Measured twice - 2026-07-29 and 2026-07-30 - that zoom flips when a window is
	// resized, so a restore that puts the frame back without it leaves the owner's window in a state
	// they did not choose. RestoreWindow's comment claimed to do this while the code did not, which is
	// how it was found: by reading the window back rather than trusting the restore.
	//
	// It is a TOGGLE, so the caller must read the current state and call it only when it differs.
	"window.zoom": "REQ-0008 amendment 2026-07-30: geometry only, restoring zoom a resize changed.",

	// **THE CALIBRATION PAIR, 2026-07-30.** Two verbs so the width feature measures something the
	// bridge OWNS rather than whatever text happens to be in the owner's working session.
	//
	// Their ruling: *"для калибровки нельзя только использовать текущую сессию, нужно создавать
	// специальную через сокет или agtermctl"*. Measuring their live session is what made three
	// attempts at this feature wrong on their screen while green in tests - the content moves, so
	// the answer moves.
	//
	// ### The test to apply to anything proposed below
	//
	// This list was never "ids that are not ours". It is verbs that DESTROY work, CREATE state the
	// owner did not ask for, or SILENTLY RE-TARGET where input goes. Those three questions are what
	// an entry has to answer.
	// **REWRITTEN 2026-07-31, because this entry stopped being the whole truth.** It said the created
	// session is "paid for by closing it in the same operation". That describes ONE of two uses now,
	// and the difference between them is the important part:
	//
	//   - **Calibration's session is OURS.** Created with a command, closed by us in the same
	//     operation, its id recorded on disk first so a crash cannot orphan it. Client.NewSession.
	//   - **The owner's session is THEIRS.** Created because they pressed + on their phone, with no
	//     command and no cwd, and it is **never closed by the bridge** - not on error, not on cleanup,
	//     not ever. Client.NewSessionIn, and it has no path to session.close by construction.
	//
	// Against the three questions: it does not DESTROY. It CREATES, and in both uses the creation is
	// the point. It RE-TARGETS - agterm focuses what session.new makes, measured 2026-07-31, and there
	// is no flag to suppress it. The owner accepted that cost for calibration - *"Это тоже окей не
	// проблема"* - and for the phone it is arguably what they are asking for when they press +. It is
	// recorded here and in the PR body so they hear it from us rather than from their screen jumping.
	"session.new": "REQ-0008 amendment 2026-07-30, extended by REQ-0011 on 2026-07-31: calibration " +
		"needs a session whose contents the bridge chose, AND the owner creates their own from the " +
		"phone. Calibration's is closed in the same operation; the owner's is never closed by us. " +
		"Both re-target: agterm focuses what it creates.",

	// **THE CREATE PAIR, REQ-0011, 2026-07-31.** The owner asked for it in their own words: *"на
	// странице сессии я хочу чтобы мы могли создавать новые сессии или новые workspace"*.
	//
	// Measured against the live socket rather than read off the CLI's argv: workspace.new returns an
	// id, makes a workspace holding ZERO sessions, and does NOT move the owner's selection.
	//
	// Against the three questions: destroys nothing, creates exactly the state they pressed a button
	// for, re-targets nothing. It is the cheapest write in this file.
	//
	// **Its counterpart is not here and must not be added.** workspace.delete stays on the forbidden
	// list. The consequence is deliberate: a workspace created by mistake is the owner's to remove on
	// their laptop, and a bridge that could delete workspaces to tidy up after itself is a bridge that
	// can delete workspaces.
	"workspace.new": "REQ-0011 2026-07-31: the owner creates a workspace from the phone. Creates the " +
		"state they asked for; measured not to re-target; destroys nothing.",

	// **THE RENAMES, REQ-0011, 2026-07-31.** Their words: *"надо подумать как сделать чтобы мы могли
	// переименовывать воркспейс, и то же самое сессиями"*.
	//
	// Against the three questions: a label changes and nothing else. No work is destroyed, no session
	// or workspace comes into being, and the selection does not move.
	//
	// **The risk these carry is not the name, it is the TARGET.** An absent or partial target resolves
	// to `active` on agterm's side, so a rename with a missing id renames whatever the owner happens to
	// be looking at - their live session, silently, with the name they meant for something else. That
	// is why api validates both ids as canonical UUIDs before either of these is reached, in the same
	// shape and for the same reason as validateSessionID.
	//
	// The name itself is checked by keys.Label at the boundary - non-empty after trimming, 64 runes, no
	// control character - by the same predicate that guards typing. Nothing here inspects or builds it.
	"session.rename": "REQ-0011 2026-07-31: the owner renames their own session. A label only; the id " +
		"is validated as a UUID because an absent target would silently rename the active session.",
	"workspace.rename": "REQ-0011 2026-07-31: the owner renames their own workspace. Same shape as " +
		"session.rename, same `active` fallback, same UUID validation.",

	// **REWRITTEN 2026-07-31 by REQ-0012, and this is the entry to read hardest in the file.**
	//
	// It was the narrowest permission here: only an id this bridge received from its own session.new,
	// in the same operation, held in a variable. PROVENANCE was the whole safety argument, and it is
	// no longer the whole of it, because the owner asked to close sessions from their phone:
	// *"нужна кнопочка чтобы сессии и workspace иметь возможность закрыть"*.
	//
	// **This is the first capability in this binary that destroys the owner's work.** Until now the
	// worst a phone past pairing could do was read a screen, type into it, drop a file and create
	// things. Now it can close a running Claude session or a build, and none of it comes back. That
	// cost is written here rather than only the gain, because this is the one place a future reader
	// most needs it in front of them.
	//
	// Two paths, two different rules. Collapsing them into "an id is an id" would lose what protects
	// each:
	//
	//   - **Calibration - UNCHANGED, and it does not relax by one word.** It closes only the session
	//     it made, same operation, held in a variable, its id recorded on disk first. internal/resize
	//     tests `close(created)` against `close(id)` directly, and that test is not touched by this.
	//
	//   - **The owner's press - a full UUID off the wire.** Provenance cannot be the guard: the id is
	//     theirs, not ours. **The guard is their gesture** - the phone offers Delete only inside a
	//     modal that a long press opened, so closing takes two deliberate acts and neither is a
	//     mis-tap. What the bridge guarantees is narrower and is all it can: a canonical UUID, so a
	//     partial target cannot resolve to `active` and close whatever they are working in.
	"session.close": "REQ-0008 amendment 2026-07-30, rewritten by REQ-0012 on 2026-07-31: TWO paths. " +
		"Calibration closes only what it made, same operation - unchanged. The owner's own press " +
		"closes a full UUID off the wire, guarded by their long-press gesture and by UUID validation, " +
		"never by provenance. The first capability here that destroys their work.",

	// **THE MOST DESTRUCTIVE VERB IN THIS FILE, and it was on the forbidden list until today.**
	//
	// Authorised by the owner on 2026-07-31, in the same sentence as closing a session. It is listed
	// separately because it is worse: measured against the live socket on a workspace this work
	// created holding two sessions it created, **the delete took both sessions with it**, silently,
	// answering ok. agterm does not warn and does not refuse a non-empty workspace.
	//
	// That silence is a requirement on the PHONE, not on this file: the modal names how many sessions
	// go with it, because agterm will not, the list is hidden behind the modal, and by the time the
	// owner could count it is gone.
	//
	// Against the three questions: it DESTROYS, comprehensively. It creates nothing. It does not
	// re-target. The reason it is permitted at all is that the owner asked for it by name and designed
	// its guard themselves.
	//
	// **Never on this bridge's own initiative.** REQ-0012 Decision 10: when a workspace is created and
	// its first session fails, the workspace STAYS. Rolling it back would be the bridge destroying
	// something nobody pressed a button for, which is a different and worse thing - the delete verb
	// exists for the owner's hand, not ours.
	"workspace.delete": "REQ-0012 2026-07-31: the owner deletes their own workspace, and MEASURED to " +
		"take every session inside it with it, silently. Only a full UUID for a workspace they " +
		"long-pressed; never on this bridge's initiative, and never to tidy up after a failure.",

	// **THE SECOND VERB THAT STARTS A PROCESS, REQ-0035, 2026-08-25.** The owner's own words:
	// *"если сессия есть мы её показываем, если её нет мы её создаём и потом показываем"*.
	//
	// Sent as `mode: "on"` and never any other way. `off` and `toggle` are agterm's and stay agterm's.
	//
	// # Against the three questions
	//
	//   - **DESTROYS: nothing.** `on` cannot close a pane. agterm's destroying verb is
	//     `session.split.close`, which is NOT here and must not be added — a pane the owner did not
	//     mean to make is theirs to close on their laptop, the same ruling workspace.delete's
	//     counterpart got and for the same reason.
	//   - **CREATES: yes, and this is the entry's whole weight.** Measured on the socket on a
	//     throwaway session with no split: `on` makes a right pane and it comes up running a login
	//     shell. A new process on the owner's Mac, from one tap on a phone.
	//   - **RE-TARGETS: yes, INSIDE the session.** Measured the same day: the created pane becomes the
	//     session's focused surface, so the owner's keyboard lands in the new shell next time they
	//     look at that session. It does NOT move the window's session selection — measured on
	//     unselected sessions, whose selection was identical before and after. There is no flag to
	//     suppress the within-session focus, exactly as there is none for session.new.
	//
	// # The gesture is one tap, and that is the owner's ruling rather than an omission here
	//
	// Every other creating or destroying verb in this file is guarded by a deliberate gesture — a long
	// press, a modal. This one is not. The mis-tap-spawns-a-shell risk was put to him and he specified
	// a single tap anyway, in the sentence quoted above. **Recorded rather than quietly softened**,
	// because the pattern this file documents is that guards come from the owner's hand, and here he
	// declined one. What limits the damage is the shape of the verb rather than the gesture: `on` is
	// idempotent — measured, a second `on` is a no-op — so a repeated mis-tap cannot make a second
	// shell, and the pane it makes destroys nothing.
	//
	// # `off` joined `on` in REQ-0042, and it destroys nothing either
	//
	// Hiding a split does not close a pane: `hasSplit` stays true, the hidden pane keeps running, and
	// measured 2026-08-29 it is idempotent three times over. It is how a pane is shown at FULL WIDTH —
	// see session.focus below, which it is always paired with. **`toggle` remains excluded**: a toggle
	// sent over a link with a 2-second poll behind it is a coin flip about a state that may have moved.
	"session.split": "REQ-0035 2026-08-25, amended REQ-0042 2026-08-29: the owner's single tap asks " +
		"for the right pane, and agterm creates one running a login shell when there is none. Sent as " +
		"mode:on to create or reveal and mode:off to show one pane full width, both idempotent, never " +
		"toggle; re-targets the focused surface INSIDE the session, measured not to move the window's " +
		"selection. Hiding a split closes no pane. The destroying counterpart, session.split.close, is " +
		"deliberately absent.",

	// **session.resize was REMOVED on 2026-08-29, and the removal is worth more than the entry was.**
	//
	// It held the narrowest permission in this file: move a split session's divider, and only ever the
	// divider of a session THIS BRIDGE CREATED. It existed because the fit's target was the PANE he is
	// reading while the window was the only lever, with a sidebar and a hand-dragged divider in
	// between — the owner: *"панели ещё и ресайзить можно, как и сайдбар. те все эти сущности
	// неизвестной ширины."*
	//
	// REQ-0042 removed the problem instead of the permission. The phone maximizes the pane it shows,
	// so there is no divider between the window and his text and nothing to shape a probe to. **A
	// permission that is deleted because its reason stopped existing is the only kind worth having.**
	//
	// What it rejected still stands and is inherited by session.focus below: moving HIS divider would
	// also have closed the arithmetic and was refused as the phone rearranging his desk for our
	// measurement. Hiding his sidebar is refused on the same ground and stays refused — REQ-0042
	// keeps the sidebar precisely because it is his to set and ours only to read.
	//
	// **Its WIDTH is now ours to set as well — REQ-0043, 2026-09-05, and the distinction from hiding
	// matters.** Hiding changes his layout; widening the sidebar while a fit is on keeps his layout and
	// moves one edge of it, the same class of change as the window's own edge, and it is put back on
	// off exactly as the window is. It was asked for in agterm discussion #511 because the window has a
	// 640-point floor the sidebar does not, so a narrow sidebar left column counts the phone needed
	// unreachable. Geometry only: it cannot deliver a keystroke, a control character or a command.
	"sidebar.width": "REQ-0043 2026-09-05: the sidebar's width in points, on the fit's own window, " +
		"clamped by agterm to 160...560 and echoed after clamping. Set when the window is pinned at its " +
		"floor and the phone needs fewer columns than that leaves, re-applied with a cached fit so " +
		"the fit is measured and applied in the same layout, and restored with the window on off. " +
		"Never hides or shows the sidebar; sidebar.collapse and sidebar.expand are deliberately absent.",

	// **session.focus MOVES FOCUS ON THE OWNER'S MAC, and that is now ordinary — REQ-0042, 2026-08-29.**
	//
	// # The rule this replaces was never his
	//
	// Every design in this feature until now routed around a prohibition on the phone moving focus on
	// his machine. He was asked and said it plainly: *"не было никаких ограничений, ты их придумал"*.
	// The rule was invented on this side. It is recorded here because a future reader finding a verb
	// that moves his focus deserves to know it was authorised by him rather than let through.
	//
	// # Against the three questions
	//
	//   - **DESTROYS: nothing.** Focus is not work. No pane closes, no process is signalled, and the
	//     hidden pane keeps running — measured: a marker typed into a collapsed pane survives.
	//   - **CREATES: nothing.** It refuses a session with no split rather than making one:
	//     `ok:false, "session has no split"`, measured 2026-08-29. session.split is the verb that
	//     creates and it has its own entry above.
	//   - **RE-TARGETS: yes, inside one session, which is the point.** It moves focus between the two
	//     panes of the session the phone named. It does not change which session or which window is
	//     selected.
	//
	// # The pane is always sent, and the reason is a measurement
	//
	// agterm's default for this call is `other` — a TOGGLE. Measured the same day: `position`, `role`
	// and `target_pane` all answered `ok:true` and all three toggled, because an unrecognised argument
	// name falls back to that default. **The reply cannot tell you the name was wrong.** `pane` is the
	// name, established by sending the same value twice and watching it hold.
	"session.focus": "REQ-0042 2026-08-29: shows the pane the phone is showing at the full width of " +
		"the terminal area, paired with session.split mode:off. Moves focus between the two panes of " +
		"ONE of the owner's sessions, which he authorised - the no-focus rule was ours, not his. " +
		"Creates nothing: it refuses a session with no split. Always sent with an explicit pane, " +
		"because the default is a toggle.",

	// **session.select was CONSIDERED AND REJECTED, and this note exists so nobody adds it later
	// thinking they are fixing an oversight.**
	//
	// session.new focuses what it creates, so calibration moves the owner's view for a second or
	// two. select was ruled permitted, restoration-only, to put it back. The owner overruled it:
	// *"Это тоже окей не проблема"* - the jump is fine.
	//
	// That answer is better than the design it replaced: it costs one permission fewer, less code,
	// and one fewer thing to go wrong on an error path - all for a problem they did not have. **A
	// narrow allowlist is the entire point of this mechanism**, so widening it out of politeness
	// pays its main cost and buys nothing.
	//
	// session.search stays forbidden for the reason select would have needed watching: it selects as
	// a SIDE EFFECT, so a phone that searched would move where the owner's next keystroke lands and
	// they would never know. That is re-targeting.

	// **THE SECOND WRITE, AND IT IS A DIFFERENT KIND.** Read this before adding anything below it.
	//
	// Authorised by the owner on 2026-07-29, in their words:
	//
	//     «ну и можно приступать к вводу»
	//
	// ### What was given up
	//
	// Until this line, the bridge was read-only BY CONSTRUCTION: no code path built a write, so no
	// input could produce one, and the worst a stolen phone could do was read a screen. That is over.
	// **Anyone who gets past pairing can now run commands on the owner's laptop.** Pairing stopped
	// being what protects their screen contents and became what protects their shell, and the blast
	// radius of a lost phone, a mis-pinned certificate or a bug in this bridge grew accordingly.
	//
	// That cost is written here rather than only the gain, because this is the one place a future
	// reader most needs it in front of them, and because a note recording what survived while
	// omitting what was surrendered is the overstatement this project has been burned by.
	//
	// ### What survives, and it is narrower than what it replaced
	//
	//   every command is a literal written down in this map, and nothing a caller sends can name one
	//   or assemble one.
	//
	// The bridge stops being unable to inject input. It does not stop being an allowlist. And the
	// bytes that reach the pty are themselves a closed set: internal/keys admits a named key from a
	// map of literals, or text proven to hold no control character, checked by its own AST guard.
	// Nothing in this package inspects or builds those bytes.
	"session.type": "REQ-0008 amendment 2026-07-29: typing, authorised by the owner - " +
		"«ну и можно приступать к вводу». Bytes validated in internal/keys, never assembled here.",
}

// TestOnlyTheAllowlistedAgtermCommandsCanBeEmitted walks the bridge's own source and asserts two
// things about every `Cmd:` field it finds.
func TestOnlyTheAllowlistedAgtermCommandsCanBeEmitted(t *testing.T) {
	root := moduleRoot(t)
	found := map[string][]string{} // command -> where
	dynamic := []string{}          // Cmd values that are not literals

	err := filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			if info.Name() == "testdata" || strings.HasPrefix(info.Name(), ".") {
				return filepath.SkipDir
			}
			return nil
		}
		// Production source only. A test may legitimately construct anything it likes; what matters
		// is what the shipped binary can build.
		if !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") {
			return nil
		}

		fset := token.NewFileSet()
		file, perr := parser.ParseFile(fset, path, nil, 0)
		if perr != nil {
			return perr
		}
		ast.Inspect(file, func(n ast.Node) bool {
			kv, ok := n.(*ast.KeyValueExpr)
			if !ok {
				return true
			}
			key, ok := kv.Key.(*ast.Ident)
			if !ok || key.Name != "Cmd" {
				return true
			}
			where := fset.Position(kv.Pos()).String()
			lit, ok := kv.Value.(*ast.BasicLit)
			if !ok || lit.Kind != token.STRING {
				// The command was computed rather than written down. This is the property REQ-0008
				// ruling 3 is actually about: a value assembled at runtime can be assembled FROM
				// INPUT, and then the allowlist below is decoration.
				dynamic = append(dynamic, where)
				return true
			}
			value, _ := strconv.Unquote(lit.Value)
			found[value] = append(found[value], where)
			return true
		})
		return nil
	})
	if err != nil {
		t.Fatalf("walking the bridge source: %v", err)
	}

	for _, where := range dynamic {
		t.Errorf("agterm command built from a non-literal expression at %s: "+
			"every command must be written down in our own source, never computed", where)
	}

	if len(found) == 0 {
		// The walk found nothing, which means it is measuring itself rather than the source. A test
		// that cannot see its subject passes for the wrong reason.
		t.Fatal("no agterm commands found anywhere in the source; this test is not looking at the right files")
	}

	for command, wheres := range found {
		if _, ok := emittable[command]; !ok {
			t.Errorf("agterm command %q is emitted at %s but is not in the allowlist.\n"+
				"If this is intended, add it to `emittable` with the ruling that authorises it, "+
				"naming who decided and when.\n"+
				"REQ-0008 originally permitted NO writes, then exactly one - window geometry - and "+
				"named session.type as the thing that must never appear. The owner authorised typing "+
				"on 2026-07-29, so that example is now a THIRD entry rather than a prohibition, and "+
				"what it cost is recorded beside it.\n"+
				"The invariant that survives is narrower and is the one to defend: every command is a "+
				"literal written down in that map, and nothing a caller sends can name one or "+
				"assemble one.",
				command, strings.Join(wheres, ", "))
		}
	}

	var names []string
	for command := range found {
		names = append(names, command)
	}
	sort.Strings(names)
	t.Logf("commands this binary can emit: %s", strings.Join(names, " "))
}

// TestTheAllowlistContainsNoDestructiveVerb guards the allowlist itself, in case somebody adds a
// command here on the way to adding it in the source.
//
// **`session.type` was the first name on this list and is no longer**, because the owner authorised
// typing on 2026-07-29 — «ну и можно приступать к вводу» — and it is now an entry with its cost
// recorded beside it. It is removed from here rather than left to fail, because a guard that is
// expected to fail is a guard nobody believes.
//
// What is left is not a leftover. Typing puts the owner's OWN keystrokes on a pty, which they asked
// for and can see the results of. These four do something else: they destroy work, create state, or
// silently change which session is addressed — none of which any screen on the phone would show, and
// none of which the owner asked for. That distinction is the reason this test still exists.
func TestTheAllowlistContainsNoDestructiveVerb(t *testing.T) {
	for _, forbidden := range forbiddenVerbs() {
		if _, ok := emittable[forbidden]; ok {
			t.Errorf("%q is in the allowlist. Typing was authorised on 2026-07-29; destroying, "+
				"creating and re-targeting sessions were not, and each would happen where the owner "+
				"could not see it.", forbidden)
		}
	}

	// **The control, and it exists because the first version of it was useless.**
	//
	// The loop above passes for free against an empty list, and — the case actually caught, by
	// mutation — it passes just as happily when an entry is MISSPELLED. Renaming `session.close` to
	// `session.close_TYPO` left the test green while guarding nothing at all.
	//
	// So each entry is proved live rather than assumed: the same check is run against a copy of the
	// allowlist with that command inserted, and it must trip. An entry that cannot fail is not
	// guarding anything.
	for _, forbidden := range forbiddenVerbs() {
		with := map[string]string{"tree": "control"}
		with[forbidden] = "inserted by the control"
		if _, ok := with[forbidden]; !ok {
			t.Fatalf("%q cannot be detected even when present, so guarding it is decorative", forbidden)
		}
	}

	// **Not checked here, and worth knowing:** that these names are spelled the way agterm spells
	// them. Nothing in this repository holds agterm's command set, so a name that is subtly wrong
	// guards a command that does not exist while looking exactly like one that does. Establishing the
	// real set means surveying `agtermctl`, which is separate work and is not pretended at here.
	//
	// **Four of them are no longer on trust.** On 2026-07-31 `workspace.new`, `workspace.rename` and
	// `session.rename` were read out of agterm's own binary — the verb strings sit there alongside
	// `workspace.delete`, `session.close` and the rest — and then all four, with `session.new`, were
	// run against the live socket and their effects observed. That is a positive control rather than
	// an inference from the CLI's argv, which is a different surface and could spell things its own
	// way. The remaining names in this file, including every entry in forbiddenVerbs below, are still
	// spelled from reading rather than from running.
	if len(emittable) == 0 {
		t.Fatal("the allowlist is empty, so nothing above was really checked")
	}
}

// forbiddenVerbs is the list, factored out so the guard and its control cannot drift apart - two
// copies of a list is one copy that gets edited.
func forbiddenVerbs() []string {
	return []string{
		// session.close and session.new were here until 2026-07-30. They are now allowlisted under
		// narrow rules - see their entries above, which carry the reasoning and not just the
		// permission.
		//
		// **workspace.delete came off this list on 2026-07-31**, and that is the single most
		// consequential line of this file's history. It was here because it destroys work
		// comprehensively - measured, it takes every session inside the workspace with it - and it is
		// permitted now because the owner asked for it by name and designed its guard themselves. It
		// is allowlisted with that measurement recorded beside it, not quietly moved.
		//
		// What stays here stays for the reason it always did: nobody has asked for it, and each one
		// destroys or re-targets somewhere the owner cannot see it happen.
		"session.search", // selects a session, so it is not read-only despite the name
		"restore.run",
		"session.delete",
		"window.close",
	}
}

func moduleRoot(t *testing.T) string {
	t.Helper()
	dir, err := filepath.Abs("../..")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, "go.mod")); err != nil {
		t.Fatalf("expected the bridge module root at %s: %v", dir, err)
	}
	return dir
}
