package org.telegram.messenger;

import org.telegram.tgnet.TLObject;

/**
 * Keeps Telegram's local read state/UI intact while suppressing server-side
 * read acknowledgements.
 */
public final class GhostMode {

    private static final String PREF_KEY = "ghostMode";

    private GhostMode() {
    }

    public static boolean isEnabled(int account) {
        return MessagesController.getMainSettings(account).getBoolean(PREF_KEY, true);
    }

    public static void setEnabled(int account, boolean enabled) {
        MessagesController.getMainSettings(account).edit().putBoolean(PREF_KEY, enabled).apply();
    }

    public static boolean shouldBlockReadRequest(int account, TLObject request) {
        if (!isEnabled(account) || request == null) {
            return false;
        }
        String requestName = request.getClass().getSimpleName();
        return "TL_messages_readHistory".equals(requestName)
                || "TL_channels_readHistory".equals(requestName)
                || "TL_messages_readEncryptedHistory".equals(requestName)
                || "TL_messages_readMessageContents".equals(requestName)
                || "TL_channels_readMessageContents".equals(requestName);
    }
}
