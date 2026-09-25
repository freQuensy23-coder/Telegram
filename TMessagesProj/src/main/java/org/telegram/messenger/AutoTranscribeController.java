package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.TranscribeButton;

import java.util.ArrayList;

/**
 * Automatically transcribes incoming voice messages for Premium accounts in
 * private dialogs that the user consistently keeps caught up with.
 *
 * A dialog becomes "regular" after three separate incoming-message cycles
 * within 14 days where there was no unread backlog before the new batch.
 * Engagement metadata is stored only on-device.
 */
public final class AutoTranscribeController implements NotificationCenter.NotificationCenterDelegate {

    private static final int MIN_ENGAGEMENT_SCORE = 3;
    private static final long ENGAGEMENT_WINDOW_MS = 14L * 24L * 60L * 60L * 1000L;

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
        if (!DialogObject.isUserDialog(dialogId)
                || dialogId == UserConfig.getInstance(currentAccount).getClientUserId()) {
            return;
        }

        ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
        if (messages == null || messages.isEmpty()) {
            return;
        }

        int incomingCount = 0;
        for (MessageObject message : messages) {
            if (message != null && !message.isOut()) {
                incomingCount++;
            }
        }
        if (incomingCount == 0) {
            return;
        }

        TLRPC.Dialog dialog = MessagesController.getInstance(currentAccount).dialogs_dict.get(dialogId);
        if (dialog == null) {
            return;
        }

        String key = "dialog_" + dialogId + "_";
        long now = System.currentTimeMillis();
        long lastCaughtUpAt = preferences.getLong(key + "last", 0L);
        int score = preferences.getInt(key + "score", 0);
        if (lastCaughtUpAt == 0L || now - lastCaughtUpAt > ENGAGEMENT_WINDOW_MS) {
            score = 0;
        }

        // If unread_count contains only this just-arrived batch (or the event
        // arrived before the count was incremented), there was no older backlog.
        if (dialog.unread_count <= incomingCount) {
            score = Math.min(MIN_ENGAGEMENT_SCORE, score + 1);
            preferences.edit()
                    .putInt(key + "score", score)
                    .putLong(key + "last", now)
                    .apply();
        }

        if (score < MIN_ENGAGEMENT_SCORE) {
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
