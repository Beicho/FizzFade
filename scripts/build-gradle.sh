#!/usr/bin/env bash
# Build a distributable APK with a caller-supplied keystore.
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
: "${SIGNING_KEY:?Set SIGNING_KEY to the absolute path of your keystore}"
: "${SIGNING_ALIAS:?Set SIGNING_ALIAS to the signing key alias}"
: "${SIGNING_STORE_PASSWORD:?Set SIGNING_STORE_PASSWORD}"
export SIGNING_KEY_PASSWORD=${SIGNING_KEY_PASSWORD:-$SIGNING_STORE_PASSWORD}
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$project_dir"
./gradlew :app:assembleRelease "$@"
apk_dir=app/build/outputs/apk/release
read -r version file < <(python3 - "$apk_dir/output-metadata.json" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as metadata:
    output, = json.load(metadata)["elements"]
print(output["versionName"], output["outputFile"])
PY
)
mkdir -p dist
apk="dist/FizzFade-$version.apk"
apksigner="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/36.0.0}/apksigner"
"$apksigner" sign --ks "$SIGNING_KEY" --ks-key-alias "$SIGNING_ALIAS" \
    --ks-pass env:SIGNING_STORE_PASSWORD --key-pass env:SIGNING_KEY_PASSWORD \
    --out "$apk" "$apk_dir/$file"
"$apksigner" verify --print-certs "$apk"
(cd dist && sha256sum "FizzFade-$version.apk" > SHA256SUMS)
