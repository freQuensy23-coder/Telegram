package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.ui.Components.TranscribeButton;

import java.util.ArrayList;

/**
 * Premium-only automatic transcription. Private cloud chats are always eligible;
 * groups/channels require three read sessions in the last 14 days, 30 minutes apart.
 */
public final class AutoTranscribeController implements NotificationCenter.NotificationCenterDelegate {
    private static final int MIN_READ_SESSIONS = 3;
    private static final long READ_WINDOW_MS = 14L * 24L * 60L * 60L * 1000L;
    private static final long READ_SESSION_GAP_MS = 30L * 60L * 1000L;
    private static final AutoTranscribeController[] instances =
            new AutoTranscribeController[UserConfig.MAX_ACCOUNT_COUNT];

    public static synchronized AutoTranscribeController getInstance(int account) {
        if (instances[account] == null) {
            instances[account] = new AutoTranscribeController(account);
        }
        return instances[account];
    }

    private final int currentAccount;
    private final SharedPreferences preferences;

    private AutoTranscribeController(int account) {
        currentAccount = account;
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(
                "auto_transcribe_" + account, Context.MODE_PRIVATE);
        // MessagesController can be initialized by a background receiver.
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter center = NotificationCenter.getInstance(account);
            center.addObserver(this, NotificationCenter.didReceiveNewMessages);
            center.addObserver(this, NotificationCenter.appDidLogout);
        });
    }

    public synchronized void recordDialogRead(long dialogId) {
        if (!DialogObject.isChatDialog(dialogId)) return;
        long now = System.currentTimeMillis();
        ArrayList<Long> reads = getRecentReads(dialogId, now);
        if (!reads.isEmpty() && now - reads.get(reads.size() - 1) < READ_SESSION_GAP_MS) return;
        reads.add(now);
        saveReads(dialogId, reads);
    }

    private synchronized boolean isRegularDialog(long dialogId) {
        if (!DialogObject.isChatDialog(dialogId)) return false;
        ArrayList<Long> reads = getRecentReads(dialogId, System.currentTimeMillis());
        saveReads(dialogId, reads);
        return reads.size() >= MIN_READ_SESSIONS;
    }

    private ArrayList<Long> getRecentReads(long dialogId, long now) {
        String stored = preferences.getString(readsKey(dialogId), "");
        ArrayList<Long> result = new ArrayList<>();
        if (TextUtils.isEmpty(stored)) return result;
        long cutoff = now - READ_WINDOW_MS;
        for (String value : stored.split(",")) {
            try {
                long timestamp = Long.parseLong(value);
                if (timestamp >= cutoff && timestamp <= now) result.add(timestamp);
            } catch (NumberFormatException ignore) {
            }
        }
        return result;
    }

    private void saveReads(long dialogId, ArrayList<Long> reads) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < reads.size(); i++) {
            if (i > 0) builder.append(',');
            builder.append(reads.get(i));
        }
        preferences.edit().putString(readsKey(dialogId), builder.toString()).apply();
    }

    private String readsKey(long dialogId) {
        return "dialog_" + dialogId + "_read_sessions";
    }

    private boolean shouldAutoTranscribe(long dialogId) {
        if (DialogObject.isUserDialog(dialogId)) {
            return dialogId != UserConfig.getInstance(currentAccount).getClientUserId();
        }
        return isRegularDialog(dialogId);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (account != currentAccount) return;
        if (id == NotificationCenter.appDidLogout) {
            // An account slot may later belong to a different Telegram user.
            synchronized (this) {
                preferences.edit().clear().apply();
            }
            return;
        }
        if (id != NotificationCenter.didReceiveNewMessages
                || args.length < 3 || Boolean.TRUE.equals(args[2])
                || !UserConfig.getInstance(currentAccount).isPremium()) return;
        long dialogId = (Long) args[0];
        if (DialogObject.isEncryptedDialog(dialogId) || !shouldAutoTranscribe(dialogId)) return;
        ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
        if (messages == null) return;
        for (MessageObject message : messages) {
            if (message == null || message.messageOwner == null
                    || message.currentAccount != currentAccount || message.getDialogId() != dialogId
                    || message.scheduled || message.isOut() || !message.isVoice() || !message.isSent()
                    || message.messageOwner.voiceTranscriptionFinal
                    || !TextUtils.isEmpty(message.messageOwner.voiceTranscription)
                    || TranscribeButton.isTranscribing(message)) continue;
            TranscribeButton.requestTranscription(message);
        }
    }
}
