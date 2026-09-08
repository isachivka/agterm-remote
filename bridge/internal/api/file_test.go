package api

import (
	"context"
	"encoding/base64"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"dev.isachivka.bewareofsugar/bridge/internal/agterm"
	"dev.isachivka.bewareofsugar/bridge/internal/dropoff"
)

// The verb, end to end through Handle, which is what the phone actually reaches.
func TestTheFileVerbWritesAndReturnsThePathItChose(t *testing.T) {
	h := New(agterm.New("/nonexistent/agterm.sock"), t.TempDir())
	body := []byte("hello from the phone\n")

	resp := h.Handle(context.Background(), Request{
		Verb:    VerbFile,
		Name:    "notes.md",
		Content: base64.StdEncoding.EncodeToString(body),
	})

	if !resp.OK {
		t.Fatalf("refused: %s", resp.Error)
	}
	if !strings.HasPrefix(resp.Path, dropoff.Root+"/") {
		t.Errorf("path %q is not under the root the bridge chose", resp.Path)
	}
	if filepath.Base(resp.Path) != "notes.md" {
		t.Errorf("basename is %q", filepath.Base(resp.Path))
	}
	got, err := os.ReadFile(resp.Path)
	if err != nil || string(got) != string(body) {
		t.Errorf("file reads back as %q, %v", got, err)
	}
	t.Cleanup(func() { os.RemoveAll(filepath.Dir(resp.Path)) })
}

// A caller trying to name a path is refused by the verb, not merely by the package under it.
func TestTheFileVerbRefusesAPathAsAName(t *testing.T) {
	h := New(agterm.New("/nonexistent/agterm.sock"), t.TempDir())
	for _, name := range []string{"../escape.txt", "/etc/passwd", "a/b.txt", "-rf"} {
		resp := h.Handle(context.Background(), Request{
			Verb: VerbFile, Name: name,
			Content: base64.StdEncoding.EncodeToString([]byte("x")),
		})
		if resp.OK {
			t.Errorf("%q was accepted; the bridge wrote to a path a caller chose", name)
		}
	}
}

func TestTheFileVerbRefusesBadBase64AndNeverEchoesIt(t *testing.T) {
	h := New(agterm.New("/nonexistent/agterm.sock"), t.TempDir())
	secret := "not!!base64!!at!!all"

	resp := h.Handle(context.Background(), Request{Verb: VerbFile, Name: "a.txt", Content: secret})

	if resp.OK {
		t.Fatal("invalid base64 was accepted")
	}
	if strings.Contains(resp.Error, secret) {
		t.Errorf("the error quotes the content: %q", resp.Error)
	}
}

// The wire bound has to be big enough for the file bound, or the feature is refused in practice while
// looking allowed in principle.
func TestTheRequestBoundFitsTheLargestPermittedFile(t *testing.T) {
	encoded := base64.StdEncoding.EncodedLen(dropoff.MaxFileBytes)
	if MaxRequestBytes <= encoded {
		t.Errorf("a %d byte file encodes to %d and the line bound is %d; the largest allowed file "+
			"cannot be sent", dropoff.MaxFileBytes, encoded, MaxRequestBytes)
	}
}
