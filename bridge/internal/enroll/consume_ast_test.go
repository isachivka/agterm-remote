package enroll_test

import (
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// Consume must compare with crypto/subtle, and this reads the syntax tree to check it.
//
// # Why this exists next to a shell guard that looks like it does the same thing
//
// scripts/check-constant-time-tokens.sh greps text, which is why it can say "you wrote bytes.Equal
// on line 155" - and also why it is blind to three edits that change nothing about the danger: a
// comparison split across two lines, one moved into a helper Consume calls, and one performed on a
// copy of the token under another name. None of those is exotic; the first is what gofmt does to a
// long line.
//
// This test is the other half. It does not care what the comparison looks like or where the bytes
// came from; it asserts the one structural fact that the whole property rests on - that the body of
// Consume, itself and not something it delegates to, calls subtle.ConstantTimeCompare. Between the
// two, the text scan catches the wrong comparison and this catches the missing one.
//
// It is deliberately not a vet analyzer. Twenty lines of go/parser need no plugin, no analysis
// harness and no place in the build to run from; a custom analyzer would be a tool this repository
// then has to own.
//
// # What it does not claim
//
// It asserts the call is PRESENT, not that it is the only comparison and not that it decides
// anything. `if false { _ = subtle.ConstantTimeCompare(w.token[:], token) }` beside a helper that
// compares a renamed copy leaves this test passing, the text guard reporting OK and the suite green,
// with a variable-time comparison in production. That takes a deliberate dead block, which is
// outside what either mechanism claims to stop - neither is a defence against somebody who means it,
// and this note is here so nobody discovers that boundary by trusting the green tick.
//
// The scanned != 1 check below also fails loudly for the wrong reason if a second type in this
// package ever grows a Consume method: the message will say "expected exactly one" when the real
// answer is "teach this test which one". A loud wrong reason is the right failure mode here, but it
// is a wrong reason.
func TestConsumeComparesWithConstantTime(t *testing.T) {
	fset := token.NewFileSet()
	entries, err := os.ReadDir(".")
	if err != nil {
		t.Fatal(err)
	}

	var found, scanned int
	var files []string
	for _, e := range entries {
		name := e.Name()
		if e.IsDir() || !strings.HasSuffix(name, ".go") || strings.HasSuffix(name, "_test.go") {
			continue
		}
		file, err := parser.ParseFile(fset, filepath.Join(".", name), nil, 0)
		if err != nil {
			t.Fatalf("parsing %s: %v", name, err)
		}
		files = append(files, name)
		for _, decl := range file.Decls {
			fn, ok := decl.(*ast.FuncDecl)
			if !ok || fn.Name.Name != "Consume" || fn.Recv == nil || fn.Body == nil {
				continue
			}
			scanned++
			ast.Inspect(fn.Body, func(n ast.Node) bool {
				call, ok := n.(*ast.CallExpr)
				if !ok {
					return true
				}
				sel, ok := call.Fun.(*ast.SelectorExpr)
				if !ok {
					return true
				}
				pkg, ok := sel.X.(*ast.Ident)
				if !ok {
					return true
				}
				if pkg.Name == "subtle" && sel.Sel.Name == "ConstantTimeCompare" {
					found++
				}
				return true
			})
		}
	}

	// A test that found no Consume at all would otherwise pass by vacuity - which is exactly what
	// happens the day the method is renamed or moved, i.e. the day it most needs to be read.
	if scanned != 1 {
		t.Fatalf("expected exactly one Consume method in %v, found %d", files, scanned)
	}
	if found == 0 {
		t.Fatal("Consume must call subtle.ConstantTimeCompare in its own body.\n" +
			"    A comparison moved into a helper, spelled with ==, or done on a renamed copy of the\n" +
			"    token is an early-exit comparison against a caller who can retry.")
	}
}
