#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/classes build/test-classes
find src/main/java -name '*.java' > build/main-sources.txt
javac --release 21 -encoding UTF-8 -d build/classes @build/main-sources.txt
cp -R src/main/resources/. build/classes/
find src/test/java -name '*.java' > build/test-sources.txt
javac --release 21 -encoding UTF-8 -cp build/classes -d build/test-classes @build/test-sources.txt
java -cp build/classes:build/test-classes org.eljah.busradio.SystemTest
jar --create --file build/busradio.jar --main-class org.eljah.busradio.Main -C build/classes .
echo 'Built build/busradio.jar (portable core, no Pi4J dependencies; use Maven -Ppi for GPIO).'
