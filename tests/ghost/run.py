#!/usr/bin/env python3
"""Run JVM regressions against production read methods without an Android SDK.

Only Android/network/storage dependencies are replaced. The controller methods
are extracted verbatim from the working tree; the state tracker is compiled as-is.
This is not an APK build or a device test.
"""
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'TMessagesProj/src/main/java/org/telegram'


def method(source: str, signature: str) -> str:
    start = source.index(signature)
    opening = source.index('{', start)
    # Ignore literals/comments when counting braces, preserving original offsets.
    lexical = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/')
    masked = lexical.sub(lambda m: ' ' * len(m.group()), source)
    depth = 0
    for pos in range(opening, len(source)):
        depth += (masked[pos] == '{') - (masked[pos] == '}')
        if depth == 0:
            return source[start:pos + 1]
    raise AssertionError(f'Unbalanced method: {signature}')


def wiring_checks() -> None:
    mc = (JAVA / 'messenger/MessagesController.java').read_text()
    sh = (JAVA / 'messenger/SendMessagesHelper.java').read_text()
    ca = (JAVA / 'ui/ChatActivity.java').read_text()
    assert 'sendReadAckAfterReply' not in ca, 'Composer-only acknowledgement returned'
    for signature in [
        'protected void putToSendingMessages(TLRPC.Message message, boolean scheduled, boolean notify)',
        'protected void putToUploadingMessages(MessageObject obj)',
    ]:
        assert 'captureReadAckForOutgoingMessage' in method(sh, signature), signature
    for signature in ['protected TLRPC.Message removeFromSendingMessages(',
                      'protected void processSentMessage(']:
        assert 'forgetOutgoingReadAck' in method(sh, signature), signature
    event = mc.index('} else if (id == NotificationCenter.messageReceivedByServer2)')
    assert 'acknowledgeReadAfterSuccessfulSend((Integer) args[0], (Long) args[3], (Boolean) args[6])' in mc[event:event + 450]
    assert 'addObserver(messagesController, NotificationCenter.messageReceivedByServer2)' in mc
    notifications = (JAVA / 'messenger/NotificationCenter.java').read_text()
    assert re.search(r'boolean allowDuringAnimation = [^;]*id == messageReceivedByServer2;', notifications)
    assert 'replyReadTracker.viewed(dialogId, threadId, monoForumPeerId, maxPositiveId, maxDate,' in method(mc, 'public void markDialogAsRead(')
    assert 'replyReadTracker.clear();' in method(mc, 'public void cleanup()')
    # A migrated group's old history must not enter the current chat's read boundary.
    assert 'if (messageObject.getDialogId() == dialog_id && (' in ca
    assert 'if (msg.getDialogId() != dialog_id)' in ca
    assert 'GhostMode' not in (JAVA / 'tgnet/ConnectionsManager.java').read_text()
    assert 'GhostMode' not in (JAVA / 'messenger/LocationController.java').read_text()
    print('PASS: shared send, completion, cancellation, migration and transport wiring')


def main() -> None:
    wiring_checks()
    source = (JAVA / 'messenger/MessagesController.java').read_text()
    signatures = [
        'public void markMessageAsRead2(long dialogId, int mid, TLRPC.InputChannel inputChannel, int ttl, long taskId, boolean createDeleteTask)',
        'public long createDeleteShowOnceTask(',
        'public void doDeleteShowOnceTask(',
        'private void completeReadTask(ReadTask task)',
        'private void completeReadTask(ReadTask task, boolean forceServerRead)',
        'public void captureReadAckForOutgoingMessage(',
        'public void forgetOutgoingReadAck(',
        'private void acknowledgeReadAfterSuccessfulSend(',
    ]
    methods = '\n\n'.join(method(source, s) for s in signatures)
    template = (Path(__file__).parent / 'GhostReadRegression.java').read_text()
    assert template.count('/* PRODUCTION_METHODS */') == 1
    with tempfile.TemporaryDirectory(prefix='telegram-ghost-tests-') as directory:
        work = Path(directory)
        harness = work / 'GhostReadRegression.java'
        harness.write_text(template.replace('/* PRODUCTION_METHODS */', methods))
        subprocess.run(['javac', '--release', '8', '-d', str(work), str(harness),
                        str(JAVA / 'messenger/ReplyReadTracker.java')], check=True)
        subprocess.run(['java', '-ea', '-cp', str(work),
                        'org.telegram.messenger.GhostReadRegression'], check=True)
    subprocess.run([sys.executable, str(Path(__file__).with_name('auto_transcribe.py'))], check=True)


if __name__ == '__main__':
    main()
