package api

import "testing"

// **The asymmetry, as a test.** One fewer is a sliver nobody notices; one more is a scroll on every
// line. The owner hit the exact-fill case: 440dp of box, 11.0dp per character, floor gives 40, and
// 40 x 11.0 is exactly 440 - the boundary, which fails toward scrolling.
func TestAnExactFillIsNotTreatedAsAFit(t *testing.T) {
	// The owner's own numbers, measured on their phone's model.
	if got := columnsThatStrictlyFit(440, 11000); got != 39 {
		t.Errorf("440dp at 11.0dp per character gave %d columns; 40 exactly fills the box and scrolls", got)
	}
}

// The control: a box that is NOT an exact multiple must be unaffected, or this is a blanket minus one.
func TestANonExactBoxIsUnchanged(t *testing.T) {
	for _, c := range []struct{ box, char, want int }{
		{403, 10840, 37}, // 37 x 10.84 = 401.08, comfortably inside
		{445, 11000, 40}, // 40 x 11.0 = 440 < 445
		{439, 11000, 39},
	} {
		if got := columnsThatStrictlyFit(c.box, c.char); got != c.want {
			t.Errorf("box %d char %d gave %d, want %d", c.box, c.char, got, c.want)
		}
	}
}

// **Whatever it returns must fit strictly**, across a wide sweep - the property, not three examples.
func TestTheResultIsAlwaysStrictlyNarrowerThanTheBox(t *testing.T) {
	for box := 100; box <= 900; box++ {
		for _, char := range []int{9000, 10840, 11000, 12500} {
			cols := columnsThatStrictlyFit(box, char)
			if cols*char >= box*1000 {
				t.Fatalf("box %ddp char %d: %d columns need %d, box holds %d - it would scroll",
					box, char, cols, cols*char, box*1000)
			}
		}
	}
}
