#!/usr/bin/env python3
"""Execute the actual AutoTranscribeController with Android/network test doubles."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    'android/content/SharedPreferences.java': '''package android.content;
public interface SharedPreferences {
    String getString(String key, String fallback);
    Editor edit();
    interface Editor { Editor putString(String key, String value); Editor clear(); void apply(); }
}''',
    'android/content/Context.java': '''package android.content;
import java.util.*;
public class Context {
    public static final int MODE_PRIVATE = 0;
    private final Map<String, SharedPreferences> prefs = new HashMap<>();
    public synchronized SharedPreferences getSharedPreferences(String name, int mode) {
        return prefs.computeIfAbsent(name, n -> new Memory());
    }
    static class Memory implements SharedPreferences {
        final Map<String, String> values = new HashMap<>();
        public synchronized String getString(String key, String fallback) { return values.getOrDefault(key, fallback); }
        public Editor edit() { return new Editor() {
            final Map<String, String> writes = new HashMap<>(); boolean clear;
            public Editor putString(String key, String value) { writes.put(key, value); return this; }
            public Editor clear() { clear = true; return this; }
            public void apply() { synchronized (Memory.this) { if (clear) values.clear(); values.putAll(writes); } }
        }; }
    }
}''',
    'android/text/TextUtils.java': '''package android.text;
public class TextUtils { public static boolean isEmpty(CharSequence s) { return s == null || s.length() == 0; } }''',
    'org/telegram/messenger/MessageObject.java': '''package org.telegram.messenger;
public class MessageObject {
    public static class Owner { public boolean voiceTranscriptionFinal; public String voiceTranscription; }
    public Owner messageOwner = new Owner();
    public int currentAccount;
    public long dialogId;
    public boolean scheduled, out, transcribing, voice = true, sent = true;
    public MessageObject(int account, long dialog) { currentAccount = account; dialogId = dialog; }
    public long getDialogId() { return dialogId; }
    public boolean isOut() { return out; }
    public boolean isVoice() { return voice; }
    public boolean isSent() { return sent; }
}''',
    'org/telegram/ui/Components/TranscribeButton.java': '''package org.telegram.ui.Components;
import org.telegram.messenger.MessageObject;
public class TranscribeButton {
    public static int requests;
    public static boolean isTranscribing(MessageObject m) { return m.transcribing; }
    public static void requestTranscription(MessageObject m) { requests++; m.transcribing = true; }
}''',
    'org/telegram/messenger/AutoTranscribeRegression.java': '''package org.telegram.messenger;
import android.content.*;
import java.util.*;
import java.util.concurrent.*;
import org.telegram.ui.Components.TranscribeButton;

class ApplicationLoader { static Context applicationContext = new Context(); }
class AndroidUtilities {
    static Thread main;
    static Queue<Runnable> queued = new ConcurrentLinkedQueue<>();
    static void runOnUIThread(Runnable r) { if (Thread.currentThread() == main) r.run(); else queued.add(r); }
    static void drain() { Runnable r; while ((r = queued.poll()) != null) r.run(); }
}
class UserConfig {
    static final int MAX_ACCOUNT_COUNT = 3;
    static UserConfig[] users = {new UserConfig(), new UserConfig(), new UserConfig()};
    boolean premium;
    static UserConfig getInstance(int account) { return users[account]; }
    boolean isPremium() { return premium; }
    long getClientUserId() { return 999L; }
}
class DialogObject {
    static boolean isEncryptedDialog(long id) { return id == Long.MIN_VALUE; }
    static boolean isChatDialog(long id) { return id < 0 && !isEncryptedDialog(id); }
    static boolean isUserDialog(long id) { return id > 0; }
}
class NotificationCenter {
    static final int didReceiveNewMessages = 1, appDidLogout = 2;
    interface NotificationCenterDelegate { void didReceivedNotification(int id, int account, Object... args); }
    static NotificationCenter[] centers = {new NotificationCenter(), new NotificationCenter(), new NotificationCenter()};
    int registrations;
    static NotificationCenter getInstance(int account) { return centers[account]; }
    void addObserver(NotificationCenterDelegate observer, int id) {
        if (Thread.currentThread() != AndroidUtilities.main) throw new AssertionError("observer registered off main thread");
        registrations++;
    }
}
public class AutoTranscribeRegression {
    static int passed;
    static final long DAY = 86400000L;
    static AutoTranscribeController controller;
    static SharedPreferences prefs;
    static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        passed++; System.out.println("PASS: " + description);
    }
    static boolean incoming(long dialog, MessageObject message, boolean scheduled) {
        int before = TranscribeButton.requests;
        controller.didReceivedNotification(NotificationCenter.didReceiveNewMessages, 0,
                dialog, new ArrayList<>(Arrays.asList(message)), scheduled);
        return before != TranscribeButton.requests;
    }
    static boolean incoming(long dialog) { return incoming(dialog, new MessageObject(0, dialog), false); }
    static void reads(long dialog, long... times) {
        StringJoiner value = new StringJoiner(",");
        for (long time : times) value.add(Long.toString(time));
        prefs.edit().putString("dialog_" + dialog + "_read_sessions", value.toString()).apply();
    }
    public static void main(String[] args) throws Exception {
        AndroidUtilities.main = Thread.currentThread();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Future<AutoTranscribeController>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) futures.add(executor.submit(() -> AutoTranscribeController.getInstance(0)));
        try {
            controller = futures.get(0).get();
            for (Future<AutoTranscribeController> future : futures) {
                if (future.get() != controller) throw new AssertionError("multiple instances");
            }
        } finally {
            executor.shutdownNow();
        }
        check(NotificationCenter.getInstance(0).registrations == 0, "background initialization defers observer registration");
        AndroidUtilities.drain();
        check(NotificationCenter.getInstance(0).registrations == 2, "concurrent initialization registers each observer once on main");
        prefs = ApplicationLoader.applicationContext.getSharedPreferences("auto_transcribe_0", 0);
        check(!incoming(42), "non-Premium private messages are not transcribed");
        UserConfig.getInstance(0).premium = true;
        check(incoming(42), "Premium private messages need no read history");
        check(!incoming(-42), "unvisited group stays ineligible");
        long now = System.currentTimeMillis();
        reads(-42, now - 26 * DAY, now - 13 * DAY, now - 1000);
        check(!incoming(-42), "day 0, 13, 26 never counts as three reads in 14 days");
        reads(-42, now - 13 * DAY, now - 2 * DAY, now - DAY);
        check(incoming(-42), "three recent group sessions enable transcription");
        UserConfig.getInstance(0).premium = false;
        check(!incoming(-42), "Premium gate also applies to eligible groups");
        UserConfig.getInstance(0).premium = true;
        check(!incoming(Long.MIN_VALUE), "secret chats never use cloud transcription");
        check(!incoming(999), "saved messages are not incoming personal messages");
        check(!incoming(42, new MessageObject(0, 42), true), "scheduled notification does not transcribe");
        MessageObject out = new MessageObject(0, 42); out.out = true;
        check(!incoming(42, out, false), "outgoing audio is ignored");
        MessageObject scheduled = new MessageObject(0, 42); scheduled.scheduled = true;
        check(!incoming(42, scheduled, false), "scheduled message in ordinary batch is ignored");
        MessageObject existing = new MessageObject(0, 42); existing.messageOwner.voiceTranscriptionFinal = true;
        check(!incoming(42, existing, false), "completed transcription is not repeated");
        MessageObject pending = new MessageObject(0, 42); pending.transcribing = true;
        check(!incoming(42, pending, false), "in-flight transcription is not duplicated");
        check(!incoming(42, new MessageObject(1, 42), false), "foreign account message is ignored");
        check(!incoming(42, new MessageObject(0, 43), false), "foreign dialog message is ignored");
        MessageObject empty = new MessageObject(0, 42); empty.messageOwner = null;
        check(!incoming(42, empty, false), "missing owner is safe");
        controller.recordDialogRead(-43);
        controller.recordDialogRead(-43);
        check(prefs.getString("dialog_-43_read_sessions", "").split(",").length == 1, "repeated reads within 30 minutes count once");
        controller.didReceivedNotification(NotificationCenter.appDidLogout, 1);
        check(incoming(-42), "another account logout preserves this account history");
        controller.didReceivedNotification(NotificationCenter.appDidLogout, 0);
        check(!incoming(-42), "logout clears read history before account slot reuse");
        System.out.println(passed + " automatic transcription regressions passed");
    }
}''',
}


def main():
    production = ROOT / 'TMessagesProj/src/main/java/org/telegram/messenger/AutoTranscribeController.java'
    with tempfile.TemporaryDirectory(prefix='telegram-auto-tests-') as directory:
        work = Path(directory)
        files = []
        for name, source in SOURCES.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            files.append(str(path))
        subprocess.run(['javac', '--release', '8', '-d', str(work), *files, str(production)], check=True)
        subprocess.run(['java', '-ea', '-cp', str(work), 'org.telegram.messenger.AutoTranscribeRegression'], check=True)


if __name__ == '__main__':
    main()
