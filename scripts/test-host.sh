#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
# Two passes over the same tests: the 21.6.0 wrapper shape (accessors H2/I2), then the 21.8.0 one
# (G2/H2). One class cannot carry both shapes, so each pass compiles its own fixture set.
python3 - "$project_dir" <<'PY'
from pathlib import Path
import sys
project = Path(sys.argv[1])
names = ['HostBridge', 'MixManager', 'MixSession', 'MixConfig', 'MediaEligibility', 'PcmProbe', 'AudioSignals', 'NativePcm', 'BeatGrid', 'BeatMatch', 'IntroAnalyzer', 'TempoRecovery']
sources = [project/'app/src/main/java/io/github/qishui/automix'/f'{name}.java' for name in names]
sources += [project/'tests/HostIntegrationTest.java', project/'tests/NativePcmTest.java']
legacy = sorted((project/'tests/fixtures').rglob('*.java'))
current = sorted((project/'tests/fixtures-21.8.0').rglob('*.java'))
# Only the wrapper fixture swaps shape; com.ss.ttvideoengine.MediaPlayerWrapper keeps its name.
swap = 'player/bach/prerender/MediaPlayerWrapper.java'
variants = {
    'host-tests': legacy,
    'host-tests-21.8.0': [path for path in legacy if not str(path).endswith(swap)] + current,
}
for variant, fixtures in variants.items():
    build = project/'build'/variant
    build.mkdir(parents=True, exist_ok=True)
    (build/'sources.txt').write_text('\n'.join('"' + str(path) + '"' for path in fixtures + sources))
PY
for variant in host-tests host-tests-21.8.0; do
    build="$project_dir/build/$variant"
    "${JAVA_HOME:?Set JAVA_HOME}/bin/javac" --release 8 -encoding UTF-8 -d "$build" @"$build/sources.txt"
    "$JAVA_HOME/bin/java" -XX:ActiveProcessorCount=2 -cp "$build" io.github.qishui.automix.HostIntegrationTest
done

"$JAVA_HOME/bin/java" -XX:ActiveProcessorCount=2 -cp "$project_dir/build/host-tests" io.github.qishui.automix.NativePcmTest
