#!/usr/bin/env bash
# extract-sqlite-native.sh
#
# Extracts the Linux-Android/aarch64 native library from the sqlite-jdbc JAR
# into harness/native/ so the Gradle test JVM can load it.
#
# Required on Termux/Android because:
#   - sqlite-jdbc extracts its .so to /tmp by default
#   - Android's linker namespace blocks dlopen() from /tmp
#   - A copy in the Termux data directory is within the trusted namespace
#
# Run once after clone, or after changing the sqlite-jdbc version.
# The extracted file is gitignored — it is not committed to the repo.
#
# Usage:
#   bash harness/scripts/extract-sqlite-native.sh
#   (run from any directory; script resolves its own location)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HARNESS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
NATIVE_DIR="$HARNESS_DIR/native"
SQLITE_VERSION="3.47.0.0"
JAR_ENTRY="org/sqlite/native/Linux-Android/aarch64/libsqlitejdbc.so"

# Locate the JAR in the Gradle cache
JAR_PATH=$(find "$HOME/.gradle/caches" -name "sqlite-jdbc-${SQLITE_VERSION}.jar" 2>/dev/null | head -1)

if [[ -z "$JAR_PATH" ]]; then
    echo "sqlite-jdbc-${SQLITE_VERSION}.jar not found in Gradle cache."
    echo "Run 'gradle dependencies' once from the harness directory to populate the cache."
    exit 1
fi

mkdir -p "$NATIVE_DIR"

echo "Extracting $JAR_ENTRY"
echo "  from: $JAR_PATH"
echo "    to: $NATIVE_DIR/"

unzip -jo "$JAR_PATH" "$JAR_ENTRY" -d "$NATIVE_DIR"

echo "Done. $NATIVE_DIR/libsqlitejdbc.so is ready."
