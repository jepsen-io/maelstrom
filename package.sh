#!/bin/bash

# Builds the Maelstrom tarball for release.
BUILD_DIR="maelstrom"
TARBALL="target/maelstrom.tar.bz2"

# Clean up
rm -rf "$BUILD_DIR" &&
rm -f "$TARBALL" &&

# Build
lein do clean, run doc, test, uberjar &&

# Construct directory
mkdir -p "$BUILD_DIR" &&
mkdir "$BUILD_DIR/lib" &&
cp target/maelstrom-*-standalone.jar "$BUILD_DIR/lib/maelstrom.jar" &&
cp pkg/maelstrom "$BUILD_DIR/" &&
cp -r README.md "$BUILD_DIR/" &&
cp -r doc/ "$BUILD_DIR/" &&
cp -r demo "$BUILD_DIR/" &&

# Tar up
tar cjf "$TARBALL" "$BUILD_DIR" &&

# Clean up
rm -rf "$BUILD_DIR"
