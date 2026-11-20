#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
mkdir -p "$project_dir/build/tests"
"${JAVA_HOME:?Set JAVA_HOME}/bin/javac" -encoding UTF-8 --release 8 -d "$project_dir/build/tests" \
    "$project_dir/app/src/main/java/io/github/qishui/automix/MixConfig.java" \
    "$project_dir/app/src/main/java/io/github/qishui/automix/MixSession.java" \
    "$project_dir/app/src/main/java/io/github/qishui/automix/DiagnosticText.java" \
    "$project_dir/app/src/main/java/io/github/qishui/automix/MediaEligibility.java" \
    "$project_dir/app/src/main/java/io/github/qishui/automix/PcmProbe.java" \
    "$project_dir/app/src/main/java/io/github/qishui/automix/BeatGrid.java" \
    "$project_dir/app/src/main/java/io/github/qishui/automix/BeatMatch.java" \
    "$project_dir/tests/BeatMatchTest.java" \
    "$project_dir/tests/MixSessionTest.java" "$project_dir/tests/DiagnosticTextTest.java" "$project_dir/tests/AudioAnalysisTest.java"
"$JAVA_HOME/bin/java" -XX:ActiveProcessorCount=2 -cp "$project_dir/build/tests" io.github.qishui.automix.MixSessionTest
"$JAVA_HOME/bin/java" -XX:ActiveProcessorCount=2 -cp "$project_dir/build/tests" io.github.qishui.automix.DiagnosticTextTest
"$JAVA_HOME/bin/java" -XX:ActiveProcessorCount=2 -cp "$project_dir/build/tests" io.github.qishui.automix.AudioAnalysisTest

"$JAVA_HOME/bin/java" -XX:ActiveProcessorCount=2 -cp "$project_dir/build/tests" io.github.qishui.automix.BeatMatchTest

# Lyric model, clock and the shape-matched host contract (fixtures mirror the 21.6.0 field shapes).
mkdir -p "$project_dir/build/lyrics-tests"
"$JAVA_HOME/bin/javac" -encoding UTF-8 --release 8 -d "$project_dir/build/lyrics-tests" \
    $(find "$project_dir/tests/lyrics-fixtures" -name '*.java') \
    "$project_dir/app/src/main/java/io/github/qishui/automix/LyricsModel.java" \
    "$project_dir/app/src/main/java/io/github/qishui/automix/LyricsClock.java" \
    "$project_dir/app/src/main/java/io/github/qishui/automix/LyricsHost.java" \
    "$project_dir/tests/LyricsTest.java"
"$JAVA_HOME/bin/java" -XX:ActiveProcessorCount=2 -cp "$project_dir/build/lyrics-tests" io.github.qishui.automix.LyricsTest

mkdir -p "$project_dir/build/player-tests"
"$JAVA_HOME/bin/javac" -encoding UTF-8 --release 8 -d "$project_dir/build/player-tests" \
    $(find "$project_dir/tests/lyrics-fixtures" "$project_dir/tests/player-fixtures" -name '*.java') \
    "$project_dir/app/src/main/java/io/github/qishui/automix/LyricsHost.java" \
    "$project_dir/app/src/main/java/io/github/qishui/automix/LyricsModel.java" \
    "$project_dir/app/src/main/java/io/github/qishui/automix/PlayerModel.java" \
    "$project_dir/app/src/main/java/io/github/qishui/automix/PlayerHost.java" \
    "$project_dir/app/src/main/java/io/github/qishui/automix/LyricsShortHost.java" \
    "$project_dir/tests/PlayerTest.java"
"$JAVA_HOME/bin/java" -XX:ActiveProcessorCount=2 -cp "$project_dir/build/player-tests" io.github.qishui.automix.PlayerTest

# Floating bar: glyph reader (Material Symbols paths) and the selection bubble's motion.
mkdir -p "$project_dir/build/tab-tests"
"$JAVA_HOME/bin/javac" -encoding UTF-8 --release 8 -d "$project_dir/build/tab-tests" \
    "$project_dir/app/src/main/java/io/github/qishui/automix/TabIcons.java" \
    "$project_dir/app/src/main/java/io/github/qishui/automix/TabMotion.java" \
    "$project_dir/tests/TabTest.java"
"$JAVA_HOME/bin/java" -XX:ActiveProcessorCount=2 -cp "$project_dir/build/tab-tests" io.github.qishui.automix.TabTest
