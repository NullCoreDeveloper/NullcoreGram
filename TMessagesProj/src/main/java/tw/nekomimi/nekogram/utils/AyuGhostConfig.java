package tw.nekomimi.nekogram.utils;

import org.telegram.messenger.UserConfig;
import xyz.nextalone.nagram.NkmrConfig;

public class AyuGhostConfig {

    public static boolean[] sendReadMessagePackets = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    public static boolean[] sendOnlinePackets = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    public static boolean[] sendUploadProgress = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    public static boolean[] sendReadStoryPackets = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    public static boolean[] sendOfflineAfterOnline = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    public static boolean[] markReadAfterSend = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    public static boolean[] showGhostToggleInDrawer = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    public static boolean[] saveDeletedMessages = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    public static boolean[] saveTtlMedia = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    public static boolean[] saveEditedMessages = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    public static void loadConfig() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            String suffix = a == 0 ? "" : "_" + a;
            sendReadMessagePackets[a] = NkmrConfig.preferences.getBoolean("sendReadMessagePackets" + suffix, true);
            sendOnlinePackets[a] = NkmrConfig.preferences.getBoolean("sendOnlinePackets" + suffix, true);
            sendUploadProgress[a] = NkmrConfig.preferences.getBoolean("sendUploadProgress" + suffix, true);
            sendReadStoryPackets[a] = NkmrConfig.preferences.getBoolean("sendReadStoryPackets" + suffix, true);
            sendOfflineAfterOnline[a] = NkmrConfig.preferences.getBoolean("sendOfflineAfterOnline" + suffix, false);
            markReadAfterSend[a] = NkmrConfig.preferences.getBoolean("markReadAfterSend" + suffix, true);
            showGhostToggleInDrawer[a] = NkmrConfig.preferences.getBoolean("showGhostToggleInDrawer" + suffix, false);
            
            saveDeletedMessages[a] = NkmrConfig.preferences.getBoolean("saveDeletedMessages" + suffix, false);
            saveTtlMedia[a] = NkmrConfig.preferences.getBoolean("saveTtlMedia" + suffix, false);
            saveEditedMessages[a] = NkmrConfig.preferences.getBoolean("saveEditedMessages" + suffix, false);
        }
    }

    public static void setGhostMode(int account, boolean enabled) {
        sendReadMessagePackets[account] = !enabled;
        sendOnlinePackets[account] = !enabled;
        sendUploadProgress[account] = !enabled;
        sendReadStoryPackets[account] = !enabled;
        sendOfflineAfterOnline[account] = enabled;

        String suffix = account == 0 ? "" : "_" + account;
        NkmrConfig.preferences.edit()
                .putBoolean("sendReadMessagePackets" + suffix, sendReadMessagePackets[account])
                .putBoolean("sendOnlinePackets" + suffix, sendOnlinePackets[account])
                .putBoolean("sendUploadProgress" + suffix, sendUploadProgress[account])
                .putBoolean("sendReadStoryPackets" + suffix, sendReadStoryPackets[account])
                .putBoolean("sendOfflineAfterOnline" + suffix, sendOfflineAfterOnline[account])
                .apply();
    }

    public static void putBoolean(int account, String key, boolean value) {
        String suffix = account == 0 ? "" : "_" + account;
        NkmrConfig.preferences.edit().putBoolean(key + suffix, value).apply();
    }

    public static void toggleGhostMode(int account) {
        setGhostMode(account, !isGhostModeActive(account));
    }

    public static boolean isGhostModeActive(int account) {
        return !sendReadMessagePackets[account]
                && !sendOnlinePackets[account]
                && !sendUploadProgress[account]
                && !sendReadStoryPackets[account]
                && sendOfflineAfterOnline[account];
    }
}
