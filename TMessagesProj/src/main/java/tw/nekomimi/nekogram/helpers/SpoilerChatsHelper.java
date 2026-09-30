package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

import java.util.HashSet;
import java.util.Set;

public class SpoilerChatsHelper {

    private static final String PREF_NAME = "spoiler_chats_config";
    private static final SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

    private static Set<String> cachedSet = null;

    public static boolean isChatSpoiler(long dialogId) {
        if (cachedSet == null) {
            Set<String> set = preferences.getStringSet("spoiler_chats", null);
            cachedSet = set != null ? new HashSet<>(set) : new HashSet<>();
        }
        return cachedSet.contains(String.valueOf(dialogId));
    }

    public static void toggleChatSpoiler(long dialogId) {
        if (cachedSet == null) {
            Set<String> set = preferences.getStringSet("spoiler_chats", null);
            cachedSet = set != null ? new HashSet<>(set) : new HashSet<>();
        }
        String idStr = String.valueOf(dialogId);
        if (cachedSet.contains(idStr)) {
            cachedSet.remove(idStr);
        } else {
            cachedSet.add(idStr);
        }
        preferences.edit().putStringSet("spoiler_chats", new HashSet<>(cachedSet)).apply();
    }
}
