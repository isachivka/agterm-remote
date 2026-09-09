// Its own module, so the bridge's "zero third-party dependencies" guard keeps meaning what it says
// about the bridge. This has no dependencies either; it is separate because it is a test harness and
// not part of the product.
module github.com/isachivka/agterm-remote/scripts/tls-proxy

go 1.24
