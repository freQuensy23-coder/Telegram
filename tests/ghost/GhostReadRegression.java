package org.telegram.messenger;

import java.util.*;

/** Test doubles model side effects; tested controller method bodies come from production. */
public final class GhostReadRegression {
    private static int passed;
    private static final long SECRET = 1L << 40;
    private final int currentAccount = 0;
    private final ReplyReadTracker replyReadTracker = new ReplyReadTracker();
    private final Storage storage = new Storage();
    private final Network network = new Network();
    private final Set<Long> forums = new HashSet<>();
    private TLRPC.EncryptedChat encryptedChat;
    private int differenceUpdates;

    private Storage getMessagesStorage() { return storage; }
    private Network getConnectionsManager() { return network; }
    private boolean isForum(long dialogId) { return forums.contains(dialogId); }
    private TLRPC.InputChannel getInputChannel(long id) { return new TLRPC.InputChannel(id); }
    private TLRPC.InputPeer getInputPeer(long id) {
        return id < 0 ? new TLRPC.TL_inputPeerChannel(id) : new TLRPC.InputPeer(id);
    }
    private TLRPC.EncryptedChat getEncryptedChat(int id) { return encryptedChat; }
    private void processNewDifferenceParams(int a, int b, int c, int d) { differenceUpdates++; }

    private static class ReadTask {
        long dialogId, replyId, monoForumPeerId;
        int maxId, maxDate;
    }

    /* PRODUCTION_METHODS */

    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    private static void test(String name, Runnable body) {
        GhostMode.enabled = true;
        body.run();
        passed++;
        System.out.println("PASS: " + name);
    }
    private static TLRPC.Message outgoing(int id, long dialogId) {
        TLRPC.Message m = new TLRPC.Message();
        m.id = id; m.dialog_id = dialogId; m.out = true;
        return m;
    }
    private static ReadTask task(long dialogId, long threadId, long monoId, int maxId) {
        ReadTask t = new ReadTask();
        t.dialogId = dialogId; t.replyId = threadId; t.monoForumPeerId = monoId;
        t.maxId = maxId; t.maxDate = 100;
        return t;
    }
    private void seen(long dialogId, long threadId, long monoId, int maxId) {
        replyReadTracker.viewed(dialogId, threadId, monoId, maxId, 100,
                                DialogObject.isEncryptedDialog(dialogId));
    }
    private void accepted(int id, long dialog) {
        acknowledgeReadAfterSuccessfulSend(id, dialog, false);
    }
    private TLObject onlyRequest() {
        check(network.requests.size() == 1, "Expected exactly one request, got " + network.requests.size());
        return network.requests.get(0);
    }

    public static void main(String[] args) {
        test("timed DM: local expiration runs without a receipt or retry task", () -> {
            GhostReadRegression c = new GhostReadRegression();
            c.markMessageAsRead2(12, 70, null, 30, 0, true);
            check(c.storage.timers == 1 && c.storage.lastTtl == 30 && c.storage.lastMid == 70, "TTL lost");
            check(c.network.requests.isEmpty() && c.storage.createdTasks == 0, "Receipt leaked");
        });
        test("timed channel: local expiration runs without channel receipt", () -> {
            GhostReadRegression c = new GhostReadRegression();
            c.markMessageAsRead2(-12, 70, new TLRPC.InputChannel(12), 30, 0, true);
            check(c.storage.timers == 1 && c.network.requests.isEmpty(), "Channel media leaked");
        });
        test("view-once: no read receipt and close still deletes media", () -> {
            GhostReadRegression c = new GhostReadRegression();
            c.markMessageAsRead2(12, 70, null, 0, 0, false);
            check(c.storage.timers == 0 && c.storage.createdTasks == 0 && c.network.requests.isEmpty(), "View-once receipt leaked");
            long taskId = c.createDeleteShowOnceTask(12, 70);
            c.doDeleteShowOnceTask(taskId, 12, 70);
            check(c.storage.emptiedMids.equals(Arrays.asList(70)), "Close no longer deletes media");
            check(c.storage.removedTasks.contains(taskId), "Local deletion task not consumed");
        });
        test("persisted receipt: discard retry without restarting expiration", () -> {
            GhostReadRegression c = new GhostReadRegression();
            c.markMessageAsRead2(12, 70, null, 30, 81, true);
            check(c.storage.removedTasks.equals(Arrays.asList(81L)), "Pending network task remains");
            check(c.storage.timers == 0 && c.storage.createdTasks == 0 && c.network.requests.isEmpty(), "Retry reset timer or sent receipt");
        });
        test("discarded view-once retry cannot replay after ghost is disabled", () -> {
            GhostReadRegression c = new GhostReadRegression();
            c.markMessageAsRead2(12, 70, null, 0, 82, false);
            check(c.storage.removedTasks.equals(Arrays.asList(82L)) && c.network.requests.isEmpty(), "Saved retry retained");
            GhostMode.enabled = false;
            check(c.storage.createdTasks == 0, "Suppressed receipt was queued for later");
        });
        test("ordinary DM content read still sends and completes its task", () -> {
            GhostMode.enabled = false;
            GhostReadRegression c = new GhostReadRegression();
            c.markMessageAsRead2(12, 70, null, 30, 0, true);
            check(c.onlyRequest() instanceof TLRPC.TL_messages_readMessageContents, "Wrong RPC");
            check(c.storage.timers == 1 && c.storage.createdTasks == 1 && c.storage.removedTasks.size() == 1, "Task callback broken");
            check(c.differenceUpdates == 1, "Difference update missing");
        });
        test("ordinary channel content read still completes its task", () -> {
            GhostMode.enabled = false;
            GhostReadRegression c = new GhostReadRegression();
            c.markMessageAsRead2(-12, 70, new TLRPC.InputChannel(12), 0, 88, false);
            check(c.onlyRequest() instanceof TLRPC.TL_channels_readMessageContents, "Wrong RPC");
            check(c.storage.removedTasks.equals(Arrays.asList(88L)), "Channel callback missing");
        });
        test("invalid media IDs or negative TTL have no side effects", () -> {
            GhostReadRegression c = new GhostReadRegression();
            c.markMessageAsRead2(12, 0, null, 1, 0, true);
            c.markMessageAsRead2(12, 70, null, -1, 0, true);
            check(c.storage.timers == 0 && c.network.requests.isEmpty(), "Invalid input accepted");
        });
        test("ordinary viewing blocks all five history routes", () -> {
            GhostReadRegression c = new GhostReadRegression();
            for (ReadTask t : Arrays.asList(task(12, 0, 0, 30), task(-12, 0, 0, 30),
                    task(-12, 4, 0, 30), task(-12, 22, 22, 30), task(SECRET, 0, 0, 0))) {
                c.completeReadTask(t);
            }
            check(c.network.requests.isEmpty(), "Viewing leaked history RPC");
        });
        test("text/media shared enqueue does not ack until success", () -> {
            GhostReadRegression c = new GhostReadRegression();
            c.seen(12, 0, 0, 30);
            c.captureReadAckForOutgoingMessage(outgoing(-1, 12), false);
            check(c.network.requests.isEmpty(), "Enqueue sent acknowledgement before success");
            c.accepted(-1, 12);
            TLRPC.TL_messages_readHistory req = (TLRPC.TL_messages_readHistory) c.onlyRequest();
            check(req.max_id == 30 && req.peer.id == 12, "Wrong history boundary");
            c.accepted(-1, 12);
            check(c.network.requests.size() == 1, "Duplicate success sent duplicate acknowledgement");
        });
        test("upload snapshot excludes newly seen messages after enqueue", () -> {
            GhostReadRegression c = new GhostReadRegression();
            c.seen(12, 0, 0, 30);
            TLRPC.Message m = outgoing(-1, 12);
            c.captureReadAckForOutgoingMessage(m, false);
            c.seen(12, 0, 0, 90);
            c.captureReadAckForOutgoingMessage(m, false);
            c.accepted(-1, 12);
            check(((TLRPC.TL_messages_readHistory)c.onlyRequest()).max_id == 30, "Upload widened snapshot");
        });
        test("failed or cancelled sends do not acknowledge history", () -> {
            GhostReadRegression c = new GhostReadRegression();
            c.seen(12, 0, 0, 30);
            c.captureReadAckForOutgoingMessage(outgoing(-1, 12), false);
            c.forgetOutgoingReadAck(-1);
            c.accepted(-1, 12);
            check(c.network.requests.isEmpty(), "Failed message caused read acknowledgement");
        });
        test("scheduled, edited, incoming, service and template messages are ignored", () -> {
            for (int variant = 0; variant < 8; variant++) {
                GhostReadRegression c = new GhostReadRegression();
                c.seen(12, 0, 0, 30);
                TLRPC.Message m = outgoing(-1, 12);
                if (variant == 1) m.id = 50;
                if (variant == 2) m.from_scheduled = true;
                if (variant == 3) m.out = false;
                if (variant == 4) m.action = new Object();
                if (variant == 5) m.quick_reply_shortcut = new Object();
                if (variant == 6) m.quick_reply_shortcut_id = 4;
                if (variant == 7) m.ephemeral = true;
                c.captureReadAckForOutgoingMessage(m, variant == 0);
                c.accepted(m.id, 12);
                check(c.network.requests.isEmpty(), "Excluded message type " + variant + " caused acknowledgement");
            }
        });
        test("a scheduled server response cannot acknowledge a captured ordinary send", () -> {
            GhostReadRegression c = new GhostReadRegression();
            c.seen(12, 0, 0, 30);
            c.captureReadAckForOutgoingMessage(outgoing(-1, 12), false);
            c.acknowledgeReadAfterSuccessfulSend(-1, 12, true);
            c.accepted(-1, 12);
            check(c.network.requests.isEmpty(), "Schedule response leaked receipt");
        });
        test("migrated history IDs cannot widen current chat acknowledgement", () -> {
            GhostReadRegression c = new GhostReadRegression();
            c.seen(-11, 0, 0, 50000); // old basic group
            c.seen(-12, 0, 0, 30); // current supergroup
            c.captureReadAckForOutgoingMessage(outgoing(-1, -12), false);
            c.accepted(-1, -12);
            TLRPC.TL_channels_readHistory req = (TLRPC.TL_channels_readHistory)c.onlyRequest();
            check(req.channel.id == 12 && req.max_id == 30, "Used old group's message ID");
        });
        test("forum reply reads only its own topic", () -> {
            GhostReadRegression c = new GhostReadRegression();
            c.forums.add(-12L);
            c.seen(-12, 4, 0, 30); c.seen(-12, 5, 0, 900);
            TLRPC.Message m = outgoing(-1, -12); m.topic = 4;
            c.captureReadAckForOutgoingMessage(m, false); c.accepted(-1, -12);
            TLRPC.TL_messages_readDiscussion req = (TLRPC.TL_messages_readDiscussion)c.onlyRequest();
            check(req.msg_id == 4 && req.read_max_id == 30 && req.peer.id == -12, "Wrong forum scope");
        });
        test("discussion starter reply with no top ID retains thread scope", () -> {
            GhostReadRegression c = new GhostReadRegression();
            c.seen(-12, 4, 0, 30);
            TLRPC.Message m = outgoing(-1, -12);
            m.reply_to = new TLRPC.TL_messageReplyHeader(); m.reply_to.reply_to_msg_id = 4;
            c.captureReadAckForOutgoingMessage(m, false); c.accepted(-1, -12);
            TLRPC.TL_messages_readDiscussion req = (TLRPC.TL_messages_readDiscussion)c.onlyRequest();
            check(req.msg_id == 4 && req.read_max_id == 30, "Direct starter reply lost its thread");
        });
        test("explicit discussion top ID retains thread scope", () -> {
            GhostReadRegression c = new GhostReadRegression(); c.seen(-12, 4, 0, 30);
            TLRPC.Message m = outgoing(-1, -12); m.reply_to = new TLRPC.TL_messageReplyHeader();
            m.reply_to.reply_to_top_id = 4;
            c.captureReadAckForOutgoingMessage(m, false); c.accepted(-1, -12);
            check(((TLRPC.TL_messages_readDiscussion)c.onlyRequest()).msg_id == 4, "Wrong discussion");
        });
        test("cross-peer quoted reply does not read that peer's thread", () -> {
            GhostReadRegression c = new GhostReadRegression(); c.seen(12, 0, 0, 30); c.seen(99, 4, 0, 900);
            TLRPC.Message m = outgoing(-1, 12); m.reply_to = new TLRPC.TL_messageReplyHeader();
            m.reply_to.reply_to_peer_id = new TLRPC.InputPeer(99); m.reply_to.reply_to_top_id = 4;
            c.captureReadAckForOutgoingMessage(m, false); c.accepted(-1, 12);
            check(((TLRPC.TL_messages_readHistory)c.onlyRequest()).max_id == 30, "Cross-peer reply changed scope");
        });
        test("channel direct-message reply uses saved-history peer", () -> {
            GhostReadRegression c = new GhostReadRegression(); c.storage.mono.add(-12L);
            c.seen(-12, 22, 22, 30); c.seen(-12, 23, 23, 900);
            TLRPC.Message m = outgoing(-1, -12); m.monoPeer = 22;
            c.captureReadAckForOutgoingMessage(m, false); c.accepted(-1, -12);
            TLRPC.TL_messages_readSavedHistory req = (TLRPC.TL_messages_readSavedHistory)c.onlyRequest();
            check(req.parent_peer.id == -12 && req.peer.id == 22 && req.max_id == 30, "Wrong direct-message subdialog");
        });
        test("secret-chat successful reply uses captured date", () -> {
            GhostReadRegression c = new GhostReadRegression(); c.encryptedChat = new TLRPC.TL_encryptedChat();
            c.seen(SECRET, 0, 0, 0);
            c.captureReadAckForOutgoingMessage(outgoing(-1, SECRET), false); c.accepted(-1, SECRET);
            check(((TLRPC.TL_messages_readEncryptedHistory)c.onlyRequest()).max_date == 100, "Wrong encrypted boundary");
        });
        test("deleted secret chat does not crash success callback", () -> {
            GhostReadRegression c = new GhostReadRegression(); c.seen(SECRET, 0, 0, 0);
            c.captureReadAckForOutgoingMessage(outgoing(-1, SECRET), false); c.accepted(-1, SECRET);
            check(c.network.requests.isEmpty(), "Deleted secret chat sent a receipt");
        });
        test("absent read boundary cannot be filled by a later upload callback", () -> {
            GhostReadRegression c = new GhostReadRegression(); TLRPC.Message m = outgoing(-1, 12);
            c.captureReadAckForOutgoingMessage(m, false); c.seen(12, 0, 0, 90);
            c.captureReadAckForOutgoingMessage(m, false); c.accepted(-1, 12);
            check(c.network.requests.isEmpty(), "Empty snapshot widened later");
        });
        test("another account and a mismatched dialog cannot consume snapshot", () -> {
            GhostReadRegression a = new GhostReadRegression(), b = new GhostReadRegression();
            a.seen(12, 0, 0, 30); a.captureReadAckForOutgoingMessage(outgoing(-1, 12), false);
            b.accepted(-1, 12); a.accepted(-1, 99);
            check(b.network.requests.isEmpty() && a.network.requests.isEmpty(), "Cross-account/dialog acknowledgement");
            a.accepted(-1, 12); check(a.network.requests.size() == 1, "Mismatched callback consumed valid snapshot");
        });
        test("sentinel and out-of-order boundaries never acknowledge all history", () -> {
            GhostReadRegression c = new GhostReadRegression();
            c.seen(12, 0, 0, 30); c.seen(12, 0, 0, Integer.MAX_VALUE); c.seen(12, 0, 0, 9);
            c.captureReadAckForOutgoingMessage(outgoing(-1, 12), false); c.accepted(-1, 12);
            check(((TLRPC.TL_messages_readHistory)c.onlyRequest()).max_id == 30, "Invalid or older boundary replaced read max");
        });
        test("logout clears observed and pending read boundaries", () -> {
            GhostReadRegression c = new GhostReadRegression(); c.seen(12, 0, 0, 30);
            c.captureReadAckForOutgoingMessage(outgoing(-1, 12), false); c.replyReadTracker.clear(); c.accepted(-1, 12);
            c.captureReadAckForOutgoingMessage(outgoing(-2, 12), false); c.accepted(-2, 12);
            check(c.network.requests.isEmpty(), "Account slot retained another user's read state");
        });
        System.out.println("Passed " + passed + " behavioral regressions (production methods + boundary tracker).");
    }

    private interface Callback { void done(TLObject response, Object error); }
    private static class Network {
        final List<TLObject> requests = new ArrayList<>();
        int getCurrentTime() { return 1000; }
        void sendRequest(TLObject request, Callback callback) {
            requests.add(request);
            callback.done(new TLRPC.TL_messages_affectedMessages(), null);
        }
    }
    private static class Storage {
        int timers, lastTtl, lastMid, createdTasks;
        final List<Long> removedTasks = new ArrayList<>();
        final List<Integer> emptiedMids = new ArrayList<>();
        final Set<Long> mono = new HashSet<>();
        void createTaskForMid(long dialog, int mid, int a, int b, int ttl, boolean unused) {
            timers++; lastTtl = ttl; lastMid = mid;
        }
        long createPendingTask(NativeByteBuffer ignored) { createdTasks++; return 123; }
        void removePendingTask(long id) { removedTasks.add(id); }
        boolean isMonoForum(long id) { return mono.contains(id); }
        void emptyMessagesMedia(long id, ArrayList<Integer> mids) { emptiedMids.addAll(mids); }
    }
    private static class TLObject { }
    private static class TLRPC {
        static class InputPeer extends TLObject { long id; InputPeer(long id) { this.id = id; } }
        static class TL_inputPeerChannel extends InputPeer { TL_inputPeerChannel(long id) { super(id); } }
        static class InputChannel extends TLObject {
            long id; InputChannel(long id) { this.id = id; }
            int getObjectSize() { return 8; }
            void serializeToStream(NativeByteBuffer ignored) { }
        }
        static class Message {
            int id, quick_reply_shortcut_id;
            long dialog_id, topic, monoPeer;
            boolean out, from_scheduled, ephemeral;
            Object action, quick_reply_shortcut;
            TL_messageReplyHeader reply_to;
        }
        static class TL_messageReplyHeader {
            long reply_to_top_id, reply_to_msg_id;
            InputPeer reply_to_peer_id;
        }
        static class TL_messageActionEmpty { }
        static class TL_messages_readMessageContents extends TLObject { ArrayList<Integer> id = new ArrayList<>(); }
        static class TL_channels_readMessageContents extends TL_messages_readMessageContents { InputChannel channel; }
        static class TL_messages_affectedMessages extends TLObject { int pts, pts_count; }
        static class TL_messages_readDiscussion extends TLObject { int msg_id, read_max_id; InputPeer peer; }
        static class TL_messages_readHistory extends TLObject { int max_id; InputPeer peer; }
        static class TL_messages_readSavedHistory extends TL_messages_readHistory { InputPeer parent_peer; }
        static class TL_channels_readHistory extends TLObject { int max_id; InputChannel channel; }
        static class TL_messages_readEncryptedHistory extends TLObject { int max_date; TL_inputEncryptedChat peer; }
        static class TL_inputEncryptedChat { int chat_id; long access_hash; }
        static class EncryptedChat { int id = 5; long access_hash; byte[] auth_key = new byte[256]; }
        static class TL_encryptedChat extends EncryptedChat { }
    }
    private static class NativeByteBuffer {
        NativeByteBuffer(int size) { }
        void writeInt32(int value) { }
        void writeInt64(long value) { }
    }
    private static class DialogObject {
        static boolean isChatDialog(long id) { return id < 0; }
        static boolean isEncryptedDialog(long id) { return id == SECRET; }
        static int getEncryptedChatId(long id) { return 5; }
        static long getPeerDialogId(TLRPC.InputPeer peer) { return peer.id; }
    }
    private static class MessageObject {
        static long getDialogId(TLRPC.Message message) { return message.dialog_id; }
        static long getMonoForumTopicId(TLRPC.Message message) { return message.monoPeer; }
        static long getTopicId(int account, TLRPC.Message message, boolean ignored) { return message.topic; }
        static boolean isEphemeral(TLRPC.Message message) { return message.ephemeral; }
    }
    private static class GhostMode { static boolean enabled; static boolean isEnabled(int account) { return enabled; } }
    private static class FileLog { static void e(Exception error) { throw new AssertionError(error); } }
    private static class Utilities {
        static final Queue stageQueue = new Queue();
        static class Queue { void postRunnable(Runnable runnable) { runnable.run(); } }
    }
}
