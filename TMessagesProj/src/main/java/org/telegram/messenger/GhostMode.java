package org.telegram.messenger;

/**
 * User-facing ghost-mode preference. Suppression is applied only in
 * high-level message read paths, never in the generic network layer.
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
}
