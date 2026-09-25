package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.ui.Components.TranscribeButton;

import java.util.ArrayList;

/**
 * Automatically transcribes incoming voice messages for Premium accounts.
 *
 * Private dialogs are always eligible. Group/channel dialogs are eligible
 * after at least three distinct read sessions in the previous 14 days.
 * Read-session metadata is stored only on-device.
 */
public final class AutoTranscribeController implements NotificationCenter.NotificationCenterDelegate {

    private static final int MIN_READ_SESSIONS = 3;
    private static final long READ_WINDOW_MS = 14L * 24L * 60L * 60L * 1000L;
    private static final long READ_SESSION_GAP_MS = 30L * 60L * 1000L;

    private static final AutoTranscribeController[] instances =
            new AutoTranscribeController[UserConfig.MAX_ACCOUNT_COUNT];

    public static AutoTranscribeController getInstance(int account) {
        AutoTranscribeController instance = instances[account];
        if (instance == null) {
            synchronized (AutoTranscribeController.class) {
                instance = instances[account];
                if (instance == null) {
                    instances[account] = instance = new AutoTranscribeController(account);
                }
            }
        }
        return instance;
    }

    private final int currentAccount;
    private final SharedPreferences preferences;

    private AutoTranscribeController(int account) {
        currentAccount = account;
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(
                "auto_transcribe_" + account, Context.MODE_PRIVATE);
        NotificationCenter.getInstance(account).addObserver(
                this, NotificationCenter.didReceiveNewMessages);
    }

    public synchronized void recordDialogRead(long dialogId) {
        if (!DialogObject.isChatDialog(dialogId)) {
            return;
        }

        long now = System.currentTimeMillis();
        ArrayList<Long> reads = getRecentReads(dialogId, now);
        if (!reads.isEmpty() && now - reads.get(reads.size() - 1) < READ_SESSION_GAP_MS) {
            return;
        }

        reads.add(now);
        saveReads(dialogId, reads);
    }

    private synchronized boolean isRegularDialog(long dialogId) {
        if (!DialogObject.isChatDialog(dialogId)) {
            return false;
        }

        long now = System.currentTimeMillis();
        ArrayList<Long> reads = getRecentReads(dialogId, now);
        saveReads(dialogId, reads);
        return reads.size() >= MIN_READ_SESSIONS;
    }

    private ArrayList<Long> getRecentReads(long dialogId, long now) {
        String stored = preferences.getString(readsKey(dialogId), "");
        ArrayList<Long> result = new ArrayList<>();
        if (TextUtils.isEmpty(stored)) {
            return result;
        }

        long cutoff = now - READ_WINDOW_MS;
        String[] values = stored.split(",");
        for (String value : values) {
            try {
                long timestamp = Long.parseLong(value);
                if (timestamp >= cutoff && timestamp <= now) {
                    result.add(timestamp);
                }
            } catch (NumberFormatException ignore) {
            }
        }
        return result;
    }

    private void saveReads(long dialogId, ArrayList<Long> reads) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < reads.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
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
        if (id != NotificationCenter.didReceiveNewMessages
                || account != currentAccount
                || args.length < 3
                || Boolean.TRUE.equals(args[2])
                || !UserConfig.getInstance(currentAccount).isPremium()) {
            return;
        }

        long dialogId = (Long) args[0];
        if (DialogObject.isEncryptedDialog(dialogId) || !shouldAutoTranscribe(dialogId)) {
            return;
        }

        ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
        if (messages == null || messages.isEmpty()) {
            return;
        }

        for (MessageObject message : messages) {
            if (message == null
                    || message.isOut()
                    || !message.isVoice()
                    || !message.isSent()
                    || message.messageOwner == null
                    || message.messageOwner.voiceTranscriptionFinal
                    || !TextUtils.isEmpty(message.messageOwner.voiceTranscription)
                    || TranscribeButton.isTranscribing(message)) {
                continue;
            }
            TranscribeButton.requestTranscription(message);
        }
    }
}
