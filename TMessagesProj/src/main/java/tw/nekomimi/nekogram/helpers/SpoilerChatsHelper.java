package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

import java.util.HashSet;
import java.util.Set;

public class SpoilerChatsHelper {

    private static final String PREF_NAME = "spoiler_chats_config";
    private static final SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

    public static boolean isChatSpoiler(long dialogId) {
        Set<String> set = preferences.getStringSet("spoiler_chats", null);
        return set != null && set.contains(String.valueOf(dialogId));
    }

    public static void toggleChatSpoiler(long dialogId) {
        Set<String> set = preferences.getStringSet("spoiler_chats", null);
        Set<String> newSet = set != null ? new HashSet<>(set) : new HashSet<>();
        String idStr = String.valueOf(dialogId);
        if (newSet.contains(idStr)) {
            newSet.remove(idStr);
        } else {
            newSet.add(idStr);
        }
        preferences.edit().putStringSet("spoiler_chats", newSet).apply();
    }
}
