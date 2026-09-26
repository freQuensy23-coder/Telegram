# Ghost-mode regressions

Run `python3 tests/ghost/run.py` with Python 3 and JDK 17 or later. No Android SDK
is needed for this focused suite. The runner extracts the relevant production
MessagesController methods verbatim, compiles them with network/storage spies,
and compiles the production ReplyReadTracker unchanged. It executes 26 behavioral
cases plus call-site wiring checks. The separate Actions compile job compiles the
actual Android sources; the JVM suite is not an APK or device test.

Coverage includes TTL and view-once media, persisted receipt cleanup without
extending deletion timers, ordinary-mode callbacks, ghost suppression for all
history routes, successful versus failed/scheduled/edited sends, duplicate success
events, upload-time snapshot stability, migrated dialogs, forum/discussion and
channel direct-message scopes, encrypted-chat dates, and account cleanup.

The history snapshot is in-memory, account-local and frozen when an outgoing
message first enters the shared send/upload path. It contains only locally
observed read boundaries for that dialog/topic, never a maximum over loaded
messages from multiple histories. A successful `messageReceivedByServer2` event
consumes it once. This event is delivered during UI animations; failures and
cancellations discard it. No content-played acknowledgement is forced by replying.

After process restart, a send without an observed boundary deliberately does not
infer one from unread or unrelated history. Background live-location read requests
remain outside ghost filtering. The temporary source-transfer/apply workflows used
to prepare this change are removed from the final tree.
