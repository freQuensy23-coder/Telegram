"""Verify the build produced a real, signed APK with precisely the requested ABIs."""
import os
from pathlib import Path
import subprocess
from zipfile import ZipFile

variant = os.environ['BUILD_TYPE']
expected = set(os.environ['ANDROID_ABIS'].split())
apk = Path(f'TMessagesProj_App/build/outputs/apk/afat/{variant}/app.apk')
with ZipFile(apk) as archive:
    names = archive.namelist()
    if 'AndroidManifest.xml' not in names or 'classes.dex' not in names:
        raise SystemExit('APK does not contain an Android manifest and executable classes')
    actual = {name.split('/')[1] for name in names
              if name.startswith('lib/') and name.endswith('/libtmessages.49.so')}
    if actual != expected:
        raise SystemExit(f'Incorrect native payload: expected {expected}, got {actual}')
signer = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0/apksigner'
subprocess.run([str(signer), 'verify', '--verbose', str(apk)], check=True)
print(f'Verified {variant} APK: {apk.stat().st_size} bytes, ABIs={sorted(actual)}')
