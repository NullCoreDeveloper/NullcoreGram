package tw.nekomimi.nekogram.helpers;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class HiddenAccountsHelper {

    private static final String PREF_NAME = "hidden_accounts_config";
    private static final SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

    private static boolean accountsRevealed = false;
    private static long appPausedTime = 0;

    public static boolean isFeatureEnabled() {
        return preferences.getBoolean("enabled", false);
    }

    public static void setFeatureEnabled(boolean enabled) {
        preferences.edit().putBoolean("enabled", enabled).apply();
        if (!enabled) {
            accountsRevealed = false;
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged);
    }

    public static boolean isRevealed() {
        return accountsRevealed;
    }

    public static boolean isAccountHidden(int account) {
        if (!isFeatureEnabled()) {
            return false;
        }
        if (accountsRevealed) {
            return false;
        }
        return isAccountConfiguredHidden(account);
    }

    public static boolean isAccountConfiguredHidden(int account) {
        Set<String> set = preferences.getStringSet("hidden_accounts", null);
        return set != null && set.contains(String.valueOf(account));
    }

    public static void setAccountConfiguredHidden(int account, boolean hidden) {
        Set<String> set = preferences.getStringSet("hidden_accounts", null);
        Set<String> newSet = set != null ? new HashSet<>(set) : new HashSet<>();
        if (hidden) {
            newSet.add(String.valueOf(account));
        } else {
            newSet.remove(String.valueOf(account));
        }
        preferences.edit().putStringSet("hidden_accounts", newSet).apply();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged);
    }

    public static int getSafeAccount() {
        int safe = preferences.getInt("safe_account", -1);
        if (safe >= 0 && UserConfig.getInstance(safe).isClientActivated() && !isAccountConfiguredHidden(safe)) {
            return safe;
        }
        for (int a : SharedConfig.activeAccounts) {
            if (UserConfig.getInstance(a).isClientActivated() && !isAccountConfiguredHidden(a)) {
                return a;
            }
        }
        return UserConfig.selectedAccount;
    }

    public static void setSafeAccount(int account) {
        preferences.edit().putInt("safe_account", account).apply();
    }

    public static boolean isPinEnabled() {
        return preferences.getBoolean("pin_enabled", false);
    }

    public static void setPinEnabled(boolean enabled) {
        preferences.edit().putBoolean("pin_enabled", enabled).apply();
    }

    public static boolean hasPin() {
        return !TextUtils.isEmpty(preferences.getString("pin_hash", ""));
    }

    public static void setPin(String pin) {
        if (TextUtils.isEmpty(pin)) {
            preferences.edit().remove("pin_hash").remove("pin_salt").apply();
            return;
        }
        try {
            byte[] salt = new byte[16];
            Utilities.random.nextBytes(salt);
            byte[] pinBytes = pin.getBytes(StandardCharsets.UTF_8);
            byte[] bytes = new byte[32 + pinBytes.length];
            System.arraycopy(salt, 0, bytes, 0, 16);
            System.arraycopy(pinBytes, 0, bytes, 16, pinBytes.length);
            System.arraycopy(salt, 0, bytes, pinBytes.length + 16, 16);
            String hash = Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length));
            preferences.edit()
                    .putString("pin_hash", hash)
                    .putString("pin_salt", Base64.encodeToString(salt, Base64.DEFAULT))
                    .apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static boolean checkPin(String pin) {
        String hash = preferences.getString("pin_hash", "");
        String salt = preferences.getString("pin_salt", "");
        return verifyHash(pin, hash, salt);
    }

    public static boolean hasPanicPin() {
        return !TextUtils.isEmpty(preferences.getString("panic_pin_hash", ""));
    }

    public static void setPanicPin(String pin) {
        if (TextUtils.isEmpty(pin)) {
            preferences.edit().remove("panic_pin_hash").remove("panic_pin_salt").apply();
            return;
        }
        try {
            byte[] salt = new byte[16];
            Utilities.random.nextBytes(salt);
            byte[] pinBytes = pin.getBytes(StandardCharsets.UTF_8);
            byte[] bytes = new byte[32 + pinBytes.length];
            System.arraycopy(salt, 0, bytes, 0, 16);
            System.arraycopy(pinBytes, 0, bytes, 16, pinBytes.length);
            System.arraycopy(salt, 0, bytes, pinBytes.length + 16, 16);
            String hash = Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length));
            preferences.edit()
                    .putString("panic_pin_hash", hash)
                    .putString("panic_pin_salt", Base64.encodeToString(salt, Base64.DEFAULT))
                    .apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static boolean checkPanicPin(String pin) {
        String hash = preferences.getString("panic_pin_hash", "");
        String salt = preferences.getString("panic_pin_salt", "");
        return verifyHash(pin, hash, salt);
    }

    private static boolean verifyHash(String text, String expectedHash, String saltString) {
        if (TextUtils.isEmpty(expectedHash) || TextUtils.isEmpty(text)) {
            return false;
        }
        try {
            byte[] salt;
            if (!TextUtils.isEmpty(saltString)) {
                salt = Base64.decode(saltString, Base64.DEFAULT);
            } else {
                salt = new byte[0];
            }
            byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
            byte[] bytes = new byte[32 + textBytes.length];
            System.arraycopy(salt, 0, bytes, 0, 16);
            System.arraycopy(textBytes, 0, bytes, 16, textBytes.length);
            System.arraycopy(salt, 0, bytes, textBytes.length + 16, 16);
            String hash = Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length));
            return expectedHash.equals(hash);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static int getAutoHideTimeout() {
        return preferences.getInt("auto_hide_timeout", 0);
    }

    public static void setAutoHideTimeout(int seconds) {
        preferences.edit().putInt("auto_hide_timeout", seconds).apply();
    }

    public static boolean isHideNotifications() {
        return preferences.getBoolean("hide_notifications", true);
    }

    public static void setHideNotifications(boolean hide) {
        preferences.edit().putBoolean("hide_notifications", hide).apply();
    }

    public static boolean isHideSettingsWhenMasked() {
        return preferences.getBoolean("hide_settings_masked", true);
    }

    public static void setHideSettingsWhenMasked(boolean hide) {
        preferences.edit().putBoolean("hide_settings_masked", hide).apply();
    }

    public static int getVisibleActivatedAccountsCount() {
        int count = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (isAccountHidden(a)) continue;
            if (UserConfig.getInstance(a).isClientActivated()) {
                count++;
            }
        }
        return count;
    }

    public static void revealAccounts(BaseFragment fragment) {
        accountsRevealed = true;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
        if (fragment != null) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.drawable.menu_unlock, LocaleController.getString("HiddenAccountsRevealed", R.string.HiddenAccountsRevealed)).show();
        }
    }

    public static void hideAccounts(Activity activity, BaseFragment fragment) {
        accountsRevealed = false;
        int current = UserConfig.selectedAccount;
        if (isAccountConfiguredHidden(current)) {
            int safe = getSafeAccount();
            if (safe != current && activity instanceof LaunchActivity) {
                ((LaunchActivity) activity).switchToAccount(safe, true);
            }
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
        if (fragment != null) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.drawable.msg_secret, LocaleController.getString("HiddenAccountsHidden", R.string.HiddenAccountsHidden)).show();
        }
    }

    public static void executePanic(Activity activity) {
        int safeAccount = getSafeAccount();
        for (int a : SharedConfig.activeAccounts) {
            if (UserConfig.getInstance(a).isClientActivated() && isAccountConfiguredHidden(a)) {
                try {
                    MessagesController.getInstance(a).performLogout(1);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
        accountsRevealed = false;
        if (activity instanceof LaunchActivity) {
            LaunchActivity launchActivity = (LaunchActivity) activity;
            int current = UserConfig.selectedAccount;
            if (!UserConfig.getInstance(current).isClientActivated() || isAccountConfiguredHidden(current)) {
                launchActivity.switchToAccount(safeAccount, true);
            }
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged);
    }

    public static void onAppPaused(Activity activity) {
        if (!isFeatureEnabled()) {
            return;
        }
        appPausedTime = SystemClock.elapsedRealtime();
        if (getAutoHideTimeout() == 0 && accountsRevealed) {
            hideAccounts(activity, null);
        }
    }

    public static void onAppResumed(Activity activity) {
        if (!isFeatureEnabled()) {
            return;
        }
        if (accountsRevealed) {
            int timeout = getAutoHideTimeout();
            if (timeout > 0 && appPausedTime > 0) {
                long elapsedSeconds = (SystemClock.elapsedRealtime() - appPausedTime) / 1000;
                if (elapsedSeconds >= timeout) {
                    hideAccounts(activity, null);
                }
            }
        }
    }

    public static void onLogo3SecondsHeld(BaseFragment fragment) {
        if (!isFeatureEnabled() || fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        if (accountsRevealed) {
            hideAccounts(fragment.getParentActivity(), fragment);
            return;
        }
        if (isPinEnabled() && hasPin()) {
            showPinDialog(fragment);
        } else {
            revealAccounts(fragment);
        }
    }

    public static void showPinDialog(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        Activity activity = fragment.getParentActivity();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString("HiddenAccountsPromptTitle", R.string.HiddenAccountsPromptTitle));

        FrameLayout frameLayout = new FrameLayout(activity);
        frameLayout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));

        EditTextBoldCursor editText = new EditTextBoldCursor(activity);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setHint(LocaleController.getString("HiddenAccountsPromptHint", R.string.HiddenAccountsPromptHint));
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editText.setTransformationMethod(PasswordTransformationMethod.getInstance());
        editText.setGravity(Gravity.CENTER);
        editText.setBackground(Theme.createEditTextDrawable(activity, false));
        frameLayout.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        builder.setView(frameLayout);
        builder.setPositiveButton(LocaleController.getString("OK", R.string.OK), null);
        builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            AndroidUtilities.showKeyboard(editText);
            editText.requestFocus();
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String code = editText.getText().toString().trim();
                if (TextUtils.isEmpty(code)) {
                    return;
                }
                if (hasPanicPin() && checkPanicPin(code)) {
                    AndroidUtilities.hideKeyboard(editText);
                    dialog.dismiss();
                    executePanic(activity);
                    BulletinFactory.of(fragment).createSimpleBulletin(R.drawable.msg_secret, LocaleController.getString("HiddenAccountsPanicDone", R.string.HiddenAccountsPanicDone)).show();
                    return;
                }
                if (checkPin(code)) {
                    AndroidUtilities.hideKeyboard(editText);
                    dialog.dismiss();
                    revealAccounts(fragment);
                    return;
                }
                AndroidUtilities.shakeView(editText);
                editText.setText("");
            });
        });
        fragment.showDialog(dialog);
    }
}
