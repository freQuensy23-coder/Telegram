package org.telegram.messenger;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Per-account, in-memory read boundaries. No network or Android dependencies. */
final class ReplyReadTracker {
    static final class Boundary {
        final long dialogId, threadId, monoForumPeerId;
        final int maxId, maxDate;

        Boundary(long dialogId, long threadId, long monoForumPeerId, int maxId, int maxDate) {
            this.dialogId = dialogId;
            this.threadId = threadId;
            this.monoForumPeerId = monoForumPeerId;
            this.maxId = maxId;
            this.maxDate = maxDate;
        }
    }

    private static final class Scope {
        final long dialogId, threadId;

        Scope(long dialogId, long threadId) {
            this.dialogId = dialogId;
            this.threadId = threadId;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Scope)) return false;
            Scope scope = (Scope) other;
            return dialogId == scope.dialogId && threadId == scope.threadId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(dialogId, threadId);
        }
    }

    private final Map<Scope, Boundary> viewed = new HashMap<>();
    // null is a deliberate empty snapshot: a later upload callback must not widen it.
    private final Map<Integer, Boundary> outgoing = new HashMap<>();

    synchronized void viewed(long dialogId, long threadId, long monoForumPeerId,
                             int maxId, int maxDate, boolean encrypted) {
        if (dialogId == 0 || (encrypted ? maxDate <= 0 || maxDate == Integer.MAX_VALUE
                : maxId <= 0 || maxId == Integer.MAX_VALUE)) {
            return;
        }
        Scope scope = new Scope(dialogId, threadId);
        Boundary old = viewed.get(scope);
        int id = encrypted ? 0 : Math.max(maxId, old == null ? 0 : old.maxId);
        int date = Math.max(Math.max(0, maxDate), old == null ? 0 : old.maxDate);
        viewed.put(scope, new Boundary(dialogId, threadId, monoForumPeerId, id, date));
    }

    synchronized boolean hasViewed(long dialogId, long threadId) {
        return threadId != 0 && viewed.containsKey(new Scope(dialogId, threadId));
    }

    synchronized void begin(int localMessageId, long dialogId, long threadId, boolean enabled) {
        if (localMessageId >= 0 || outgoing.containsKey(localMessageId)) return;
        outgoing.put(localMessageId, enabled ? viewed.get(new Scope(dialogId, threadId)) : null);
    }

    synchronized Boundary complete(int localMessageId, long dialogId) {
        Boundary boundary = outgoing.get(localMessageId);
        if (boundary != null && boundary.dialogId != dialogId) return null;
        outgoing.remove(localMessageId);
        return boundary;
    }

    synchronized void forget(int localMessageId) {
        outgoing.remove(localMessageId);
    }

    synchronized void clear() {
        viewed.clear();
        outgoing.clear();
    }
}
