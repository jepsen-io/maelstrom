maelstrom-go
============

This is a Go implementation of the Maelstrom Node. This provides basic message
handling, an event loop, & a client interface to the key/value store. It's a
good starting point for implementing a Maelstrom node as it helps to avoid a
lot of boilerplate.

## Usage

Binaries run by `maelstrom` need to be referenced by absolute or relative path.
The easiest way to use Go with Maelstrom is to `go install` and then specify
the relative path to the `--bin` flag:

```sh
$ cd /path/to/maelstrom-echo
$ go install .
$ maelstrom test --bin ~/go/bin/maelstrom-echo ...
```

