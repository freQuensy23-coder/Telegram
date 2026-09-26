# Android APK builds

Pushes outside `master` build an ARM64 debug APK (`org.telegram.messenger.beta`).
Pushes and merges into `master` build the release variant for arm64-v8a,
armeabi-v7a, x86_64 and x86. External pull requests build debug only.
Artifacts are attached to each Actions run; this workflow does not publish to a store.

The workflow installs SDK 36, Build Tools 36.0.0, NDK 27.2.12479018 and CMake 3.22.1.
It validates native archives before compiling, rebuilds missing TDLib archives,
restricts debug ABIs in both the application and its native library, disables full
LTO for debug objects, saves Gradle/ccache state on branch pushes, and cancels
superseded runs. Release optimizations remain unchanged. Cold builds are still
more expensive than cached builds; consult the uploaded Gradle profile rather
than assuming a fixed build time.

## Production signing

The upstream `release.keystore` is a public dummy key. CI must not sign a
production artifact with that key. Configure these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: base64 of your existing private release keystore.
- `ANDROID_KEY_ALIAS`: signing alias.
- `ANDROID_KEY_PASSWORD`: key password.
- `ANDROID_STORE_PASSWORD`: keystore password.

Release fails before compilation when these are absent. The keystore is decoded
only in runner temporary storage, never committed or uploaded, and removed at the
end of the job. Debug needs no signing secrets. Keep the same release key for
updates. Before distributing a fork, also replace upstream Telegram API and
Firebase configuration as explained in the root README.

## Verification

After Gradle succeeds, CI opens the APK as a ZIP, checks its manifest/classes and
exact native ABI payload, and runs `apksigner verify`. Build diagnostics and the
native archive inventory are uploaded even when compilation fails.
