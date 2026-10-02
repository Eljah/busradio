#!/usr/bin/env bash
# Pure-JDK verification and demo packaging. Does NOT compile or include Pi4J.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .local/classes dist
find busradio-core busradio-server busradio-edge busradio-tests -name '*.java' > .local/sources.txt
javac --release 21 -encoding UTF-8 -d .local/classes @.local/sources.txt
java -Dbusradio.reports=busradio-tests/target/test-results -cp ".local/classes:busradio-server/src/main/resources" io.github.eljah.busradio.tests.SelfTest
jar --create --file dist/busradio-server-0.1.0-standalone.jar --main-class io.github.eljah.busradio.server.ServerMain -C .local/classes io/github/eljah/busradio/core -C .local/classes io/github/eljah/busradio/server -C busradio-server/src/main/resources .
jar --create --file dist/busradio-edge-0.1.0-simulator.jar --main-class io.github.eljah.busradio.edge.EdgeMain -C .local/classes io/github/eljah/busradio/core -C .local/classes io/github/eljah/busradio/edge
printf '\nBuilt standalone server and non-Pi4J simulator in dist/.\nUse Maven verify for the full Pi4J distribution.\n'
