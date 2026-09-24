package tw.nekomimi.nekogram.helpers;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
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

public class PasscodeHelper {
    private static final SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekopasscode", Context.MODE_PRIVATE);

    private static final HashSet<Integer> unlockedAccounts = new HashSet<>();
    private static boolean stealthModeRevealed = false;
    private static long lastTapTime = 0;
    private static int tapCount = 0;

    public static boolean checkPasscode(Activity activity, String passcode) {
        if (hasPasscodeForAccount(Integer.MAX_VALUE)) {
            String passcodeHash = preferences.getString("passcodeHash" + Integer.MAX_VALUE, "");
            String passcodeSaltString = preferences.getString("passcodeSalt" + Integer.MAX_VALUE, "");
            if (checkPasscodeHash(passcode, passcodeHash, passcodeSaltString)) {
                executePanic(activity);
                return true;
            }
        }
        for (int a : SharedConfig.activeAccounts) {
            if (UserConfig.getInstance(a).isClientActivated() && hasPasscodeForAccount(a)) {
                String passcodeHash = preferences.getString("passcodeHash" + a, "");
                String passcodeSaltString = preferences.getString("passcodeSalt" + a, "");
                if (checkPasscodeHash(passcode, passcodeHash, passcodeSaltString)) {
                    revealAccount(a);
                    if (activity instanceof LaunchActivity) {
                        LaunchActivity launchActivity = (LaunchActivity) activity;
                        launchActivity.switchToAccount(a, true);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean checkPasscodeHash(String passcode, String passcodeHash, String passcodeSaltString) {
        try {
            byte[] passcodeSalt;
            if (passcodeSaltString.length() > 0) {
                passcodeSalt = Base64.decode(passcodeSaltString, Base64.DEFAULT);
            } else {
                passcodeSalt = new byte[0];
            }
            byte[] passcodeBytes = passcode.getBytes(StandardCharsets.UTF_8);
            byte[] bytes = new byte[32 + passcodeBytes.length];
            System.arraycopy(passcodeSalt, 0, bytes, 0, 16);
            System.arraycopy(passcodeBytes, 0, bytes, 16, passcodeBytes.length);
            System.arraycopy(passcodeSalt, 0, bytes, passcodeBytes.length + 16, 16);
            String hash = Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length));
            return passcodeHash.equals(hash);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static void removePasscodeForAccount(int account) {
        preferences.edit()
                .remove("passcodeHash" + account)
                .remove("passcodeSalt" + account)
                .remove("hide" + account)
                .apply();
        unlockedAccounts.remove(account);
    }

    public static boolean isAccountAllowPanic(int account) {
        return preferences.getBoolean("allowPanic" + account, true);
    }

    public static boolean isAccountConfiguredHidden(int account) {
        return hasPasscodeForAccount(account) && preferences.getBoolean("hide" + account, false);
    }

    public static boolean isStealthModeEnabled() {
        return preferences.getBoolean("stealthModeEnabled", false);
    }

    public static void setStealthModeEnabled(boolean enable) {
        preferences.edit().putBoolean("stealthModeEnabled", enable).apply();
        if (!enable) {
            stealthModeRevealed = false;
            unlockedAccounts.clear();
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged);
    }

    public static boolean isAccountHidden(int account) {
        if (!isStealthModeEnabled()) {
            return false;
        }
        if (!isAccountConfiguredHidden(account)) {
            return false;
        }
        if (stealthModeRevealed || unlockedAccounts.contains(account)) {
            return false;
        }
        return true;
    }

    public static boolean isAccountUnlocked(int account) {
        return !isAccountHidden(account);
    }

    public static boolean isStealthModeRevealed() {
        return stealthModeRevealed;
    }

    public static void revealAccount(int account) {
        unlockedAccounts.add(account);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged);
    }

    public static void revealAllHiddenAccounts() {
        stealthModeRevealed = true;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged);
    }

    public static void lockHiddenAccounts(Activity activity) {
        boolean changed = stealthModeRevealed || !unlockedAccounts.isEmpty();
        stealthModeRevealed = false;
        unlockedAccounts.clear();

        int current = UserConfig.selectedAccount;
        if (isAccountConfiguredHidden(current)) {
            int safe = getSafeAccount();
            if (safe != current && activity instanceof LaunchActivity) {
                ((LaunchActivity) activity).switchToAccount(safe, true);
            }
        }
        if (changed) {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged);
        }
    }

    public static int getSafeAccount() {
        int safe = preferences.getInt("safeAccount", -1);
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
        preferences.edit().putInt("safeAccount", account).apply();
    }

    public static boolean isAutoLockOnMinimize() {
        return preferences.getBoolean("autoLockOnMinimize", true);
    }

    public static void setAutoLockOnMinimize(boolean enable) {
        preferences.edit().putBoolean("autoLockOnMinimize", enable).apply();
    }

    public static boolean isSecretGestureEnabled() {
        return preferences.getBoolean("secretGestureEnabled", true);
    }

    public static void setSecretGestureEnabled(boolean enable) {
        preferences.edit().putBoolean("secretGestureEnabled", enable).apply();
    }

    public static void onAppPaused(Activity activity) {
        if (!isStealthModeEnabled()) {
            return;
        }
        if (isAutoLockOnMinimize()) {
            lockHiddenAccounts(activity);
        }
    }

    public static void executePanic(Activity activity) {
        int safeAccount = getSafeAccount();
        for (int a : SharedConfig.activeAccounts) {
            if (UserConfig.getInstance(a).isClientActivated() && (isAccountAllowPanic(a) || isAccountConfiguredHidden(a))) {
                try {
                    MessagesController.getInstance(a).performLogout(1);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
        stealthModeRevealed = false;
        unlockedAccounts.clear();

        if (activity instanceof LaunchActivity) {
            LaunchActivity launchActivity = (LaunchActivity) activity;
            int current = UserConfig.selectedAccount;
            if (!UserConfig.getInstance(current).isClientActivated() || isAccountConfiguredHidden(current)) {
                launchActivity.switchToAccount(safeAccount, true);
            }
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged);
    }

    public static void onTitleTapped(BaseFragment fragment) {
        if (!isStealthModeEnabled() || !isSecretGestureEnabled() || fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastTapTime < 600) {
            tapCount++;
        } else {
            tapCount = 1;
        }
        lastTapTime = now;

        if (tapCount >= 5) {
            tapCount = 0;
            showSecretGesturePrompt(fragment);
        }
    }

    public static void showSecretGesturePrompt(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        Activity activity = fragment.getParentActivity();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString("PasscodeSecretPromptTitle", R.string.PasscodeSecretPromptTitle));

        FrameLayout frameLayout = new FrameLayout(activity);
        frameLayout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));

        EditTextBoldCursor editText = new EditTextBoldCursor(activity);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setHint(LocaleController.getString("PasscodeSecretPromptHint", R.string.PasscodeSecretPromptHint));
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
                if (hasPanicCode()) {
                    String passcodeHash = preferences.getString("passcodeHash" + Integer.MAX_VALUE, "");
                    String passcodeSaltString = preferences.getString("passcodeSalt" + Integer.MAX_VALUE, "");
                    if (checkPasscodeHash(code, passcodeHash, passcodeSaltString)) {
                        AndroidUtilities.hideKeyboard(editText);
                        dialog.dismiss();
                        executePanic(activity);
                        BulletinFactory.of(fragment).createSimpleBulletin(R.drawable.msg_secret, LocaleController.getString("PasscodePanicExecuted", R.string.PasscodePanicExecuted)).show();
                        return;
                    }
                }
                for (int a : SharedConfig.activeAccounts) {
                    if (UserConfig.getInstance(a).isClientActivated() && hasPasscodeForAccount(a)) {
                        String passcodeHash = preferences.getString("passcodeHash" + a, "");
                        String passcodeSaltString = preferences.getString("passcodeSalt" + a, "");
                        if (checkPasscodeHash(code, passcodeHash, passcodeSaltString)) {
                            AndroidUtilities.hideKeyboard(editText);
                            dialog.dismiss();
                            revealAccount(a);
                            if (activity instanceof LaunchActivity) {
                                ((LaunchActivity) activity).switchToAccount(a, true);
                            }
                            BulletinFactory.of(fragment).createSimpleBulletin(R.drawable.menu_unlock, LocaleController.getString("PasscodeStealthModeRevealed", R.string.PasscodeStealthModeRevealed)).show();
                            return;
                        }
                    }
                }
                if (SharedConfig.checkPasscode(code)) {
                    AndroidUtilities.hideKeyboard(editText);
                    dialog.dismiss();
                    revealAllHiddenAccounts();
                    BulletinFactory.of(fragment).createSimpleBulletin(R.drawable.menu_unlock, LocaleController.getString("PasscodeStealthModeRevealed", R.string.PasscodeStealthModeRevealed)).show();
                    return;
                }
                AndroidUtilities.shakeView(editText);
                editText.setText("");
            });
        });
        fragment.showDialog(dialog);
    }

    public static void setAccountAllowPanic(int account, boolean panic) {
        preferences.edit()
                .putBoolean("allowPanic" + account, panic)
                .apply();
    }

    public static void setHideAccount(int account, boolean hide) {
        preferences.edit()
                .putBoolean("hide" + account, hide)
                .apply();
    }

    public static void setPasscodeForAccount(String firstPassword, int account) {
        try {
            byte[] passcodeSalt = new byte[16];
            Utilities.random.nextBytes(passcodeSalt);
            byte[] passcodeBytes = firstPassword.getBytes(StandardCharsets.UTF_8);
            byte[] bytes = new byte[32 + passcodeBytes.length];
            System.arraycopy(passcodeSalt, 0, bytes, 0, 16);
            System.arraycopy(passcodeBytes, 0, bytes, 16, passcodeBytes.length);
            System.arraycopy(passcodeSalt, 0, bytes, passcodeBytes.length + 16, 16);
            preferences.edit()
                    .putString("passcodeHash" + account, Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length)))
                    .putString("passcodeSalt" + account, Base64.encodeToString(passcodeSalt, Base64.DEFAULT))
                    .apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static boolean hasPasscodeForAccount(int account) {
        return preferences.contains("passcodeHash" + account) && preferences.contains("passcodeSalt" + account);
    }

    public static boolean hasPanicCode() {
        return hasPasscodeForAccount(Integer.MAX_VALUE);
    }

    public static String getSettingsKey() {
        var settingsHash = preferences.getString("settingsHash", "");
        if (!TextUtils.isEmpty(settingsHash)) {
            return settingsHash;
        }
        byte[] bytes = new byte[8];
        Utilities.random.nextBytes(bytes);
        var hash = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        preferences.edit().putString("settingsHash", hash).apply();
        return hash;
    }

    public static boolean isSettingsHidden() {
        return preferences.getBoolean("hideSettings", false);
    }

    public static void setHideSettings(boolean hide) {
        preferences.edit()
                .putBoolean("hideSettings", hide)
                .apply();
    }

    public static boolean isEnabled() {
        return preferences.getAll().size() != 0;
    }

    public static void clearAll() {
        preferences.edit().clear().apply();
        unlockedAccounts.clear();
        stealthModeRevealed = false;
    }
}
