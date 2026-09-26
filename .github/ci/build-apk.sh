#!/usr/bin/env bash
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
: "${BUILD_TYPE:?BUILD_TYPE must be debug or release}"
: "${ANDROID_ABIS:?ANDROID_ABIS must list target architectures}"
case "$BUILD_TYPE" in
  debug)
    variant=Debug; heap=6g
    unset CI_KEY_ALIAS CI_KEY_PASSWORD CI_STORE_PASSWORD CI_KEYSTORE_PATH
    ;;
  release) variant=Release; heap=8g ;;
  *) echo "Unsupported build type: $BUILD_TYPE" >&2; exit 1 ;;
esac
common=(--init-script .github/ci/android.init.gradle
        "-PciAbis=${ANDROID_ABIS// /,}"
        "-Dorg.gradle.jvmargs=-Xmx$heap -XX:MaxMetaspaceSize=1g"
        --build-cache --max-workers=2 --console=plain --profile --stacktrace)

# Fail on Java/resource errors before spending time compiling native code.
# The following assemble still runs the complete native build and packaging.
./gradlew ":TMessagesProj:compile${variant}JavaWithJavac" \
  ":TMessagesProj_App:compileAfat${variant}JavaWithJavac" "${common[@]}"
./gradlew ":TMessagesProj_App:assembleAfat${variant}" "${common[@]}"
python3 .github/ci/verify-apk.py

python3 - <<'PY'
import hashlib
import json
import os
from pathlib import Path
import subprocess

output = Path('TMessagesProj_App/build/outputs/apk/afat') / os.environ['BUILD_TYPE']
apk = output / 'app.apk'
sha256 = hashlib.sha256(apk.read_bytes()).hexdigest()
(output / 'app.apk.sha256').write_text(sha256 + '  app.apk\n')
def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()
metadata = {
    'commit': git('rev-parse', 'HEAD'),
    'tree': git('rev-parse', 'HEAD^{tree}'),
    'parents': git('show', '-s', '--format=%P', 'HEAD').split(),
    'variant': os.environ['BUILD_TYPE'],
    'abis': os.environ['ANDROID_ABIS'].split(),
    'apk_sha256': sha256,
    'run_url': f"{os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}/"
               f"{os.environ.get('GITHUB_REPOSITORY', '')}/actions/runs/"
               f"{os.environ.get('GITHUB_RUN_ID', '')}",
}
(output / 'build-info.json').write_text(json.dumps(metadata, indent=2) + '\n')
PY
