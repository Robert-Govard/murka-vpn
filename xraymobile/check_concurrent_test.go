package xraymobile

import (
	"sync"
	"testing"
)

// The Android app pings every server at once; each Check runs its own Xray
// instance in this one process.
func TestChecksRunConcurrently(t *testing.T) {
	srv := target(t)
	const n = 8
	var wg sync.WaitGroup
	errs := make([]error, n)
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			_, errs[i] = Check(remnawaveLike(freePort(t)), srv.URL, 8000)
		}(i)
	}
	wg.Wait()
	for i, err := range errs {
		if err != nil {
			t.Errorf("check %d: %v", i, err)
		}
	}
}
