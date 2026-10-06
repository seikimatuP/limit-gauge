#!/usr/bin/env bash
# Runs tools/test/LogicTest.java on a plain JVM against UsageData and SetupLink.
# Needs an org.json implementation, e.g. a checkout of https://github.com/stleary/JSON-java:
#   ORG_JSON_SRC=/path/to/JSON-java/src/main/java ./tools/run-logic-tests.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
: "${ORG_JSON_SRC:?set ORG_JSON_SRC to a directory containing org/json/*.java}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
PKG="$HERE/app/src/main/java/com/ynozue/limitgauge"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  $(find "$ORG_JSON_SRC/org/json" -name '*.java') \
  "$PKG/UsageData.java" "$PKG/SetupLink.java" "$HERE/tools/test/LogicTest.java"
java -cp "$OUT" com.ynozue.limitgauge.LogicTest
