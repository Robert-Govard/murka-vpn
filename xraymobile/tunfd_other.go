//go:build !darwin

package xraymobile

// TunFD is only meaningful inside an Apple packet tunnel extension.
func TunFD() int { return -1 }
