package trust

// This file is compiled only under `go test`, so the seam below is not in the bridge binary and
// cannot be reached by anything outside this package's own tests.

// FailAllWrites makes every subsequent write fail with err, so a test can assert what the store
// does when the disk refuses.
//
// A seam rather than a read-only directory: chmod tests measure the filesystem's opinion of the
// user running them, pass for the wrong reason under an unusual umask, and quietly stop testing
// anything at all when they run as a user who can write to a 0500 directory regardless — which is
// how CI often runs. This fails the write on demand, on every platform, for exactly the call under
// test.
func FailAllWrites(s *Store, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.write = func([]byte) error { return err }
}

// RestoreWrites puts the real filesystem back, so a test can show the store still works after a
// failure rather than only that it refused.
func RestoreWrites(s *Store) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.write = s.writeAtomically
}
