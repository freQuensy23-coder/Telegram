#!/usr/bin/env bash
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
: "${ABIS:?ABIS must list the target Android architectures}"
PREBUILD="$PWD/TMessagesProj/jni/prebuild"

# The old workflow only discovered missing archives after compiling the app.
# Regenerate the newer TDLib archives when they are absent from checkout/cache.
needs_td=0
for abi in $ABIS; do
  case "$abi" in arm64-v8a|armeabi-v7a|x86_64|x86) ;; *) echo "Unsupported ABI: $abi" >&2; exit 1;; esac
  for lib in libtde2e.a libtdutils.a; do
    [[ -s "$PREBUILD/lib/$abi/$lib" ]] || needs_td=1
  done
done
if [[ "$needs_td" == 1 ]]; then
  bash "$PREBUILD/build_tdlib.sh"
fi

# Check every imported archive used by this version, not just the first linker error.
python3 - <<'PY'
import os
from pathlib import Path

root = Path('TMessagesProj/jni/prebuild/lib')
libraries = ('avutil avformat avcodec swresample swscale crypto ssl vpx '
             'dav1d opus tde2e tdutils tlottie openh264 iwasm').split()
errors, inventory = [], []
for abi in os.environ['ABIS'].split():
    for library in libraries:
        path = root / abi / f'lib{library}.a'
        if not path.is_file():
            errors.append(f'Missing native archive: {path}')
            continue
        with path.open('rb') as stream:
            magic = stream.read(8)
        if magic != b'!<arch>\n':
            errors.append(f'Invalid native archive (or unresolved LFS pointer): {path}')
        inventory.append(f'{path}\t{path.stat().st_size}')
Path('native-inventory.txt').write_text('\n'.join(inventory + errors) + '\n')
if errors:
    raise SystemExit('\n'.join(errors))
print(f'Validated {len(inventory)} native archives before Gradle compilation.')
PY
