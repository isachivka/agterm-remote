//go:build !proxy

// Package main is a test harness. **The program is in main.go, behind the `proxy` build tag**, and
// this file is what an ordinary build of this module produces instead.
//
// The tag is not ceremony. See main.go: the program deliberately does not validate the certificate it
// is handed, because the router it stands in for cannot validate it either, and a build that included
// it by default would put that into every analysis of code that ships.
package main

import "fmt"

func main() {
	fmt.Println("build with -tags proxy; see main.go and scripts/enrol-end-to-end.sh --proxied")
}
