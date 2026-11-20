#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
test_build="$project_dir/build/host-tests"
mkdir -p "$test_build"
python3 - "$project_dir" "$test_build" <<'PY'
from pathlib import Path
import sys
project, build = map(Path, sys.argv[1:])
names = ['HostBridge', 'MixManager', 'MixSession', 'MixConfig', 'MediaEligibility', 'PcmProbe', 'AudioSignals', 'NativePcm', 'BeatGrid', 'BeatMatch', 'IntroAnalyzer', 'TempoRecovery']
sources = sorted((project/'tests/fixtures').rglob('*.java'))
sources += [project/'app/src/main/java/io/github/qishui/automix'/f'{name}.java' for name in names]
sources += [project/'tests/HostIntegrationTest.java',project/'tests/NativePcmTest.java']
(build/'sources.txt').write_text('\n'.join('"' + str(path) + '"' for path in sources))
PY
"${JAVA_HOME:?Set JAVA_HOME}/bin/javac" --release 8 -encoding UTF-8 -d "$test_build" @"$test_build/sources.txt"
"$JAVA_HOME/bin/java" -XX:ActiveProcessorCount=2 -cp "$test_build" io.github.qishui.automix.HostIntegrationTest

"$JAVA_HOME/bin/java" -XX:ActiveProcessorCount=2 -cp "$test_build" io.github.qishui.automix.NativePcmTest
