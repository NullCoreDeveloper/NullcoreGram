package tw.nekomimi.nekogram.settings;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

import tw.nekomimi.nekogram.helpers.HiddenAccountsHelper;

public class NekoHiddenAccountsSettingsActivity extends BaseNekoSettingsActivity {

    private int enableRow;
    private int enableAboutRow;

    private int toggleVisibilityRow;
    private int statusShadowRow;

    private int securityHeaderRow;
    private int requirePinRow;
    private int pinConfigRow;
    private int removePinRow;
    private int pinAboutRow;

    private int autoHideHeaderRow;
    private int autoHideTimeoutRow;
    private int hideNotificationsRow;
    private int hideSettingsRow;
    private int autoHideAboutRow;

    private int accountsHeaderRow;
    private int safeAccountRow;
    private int accountsStartRow;
    private int accountsEndRow;
    private int accountsAboutRow;

    private int panicHeaderRow;
    private int panicCodeRow;
    private int removePanicCodeRow;
    private int panicAboutRow;

    private final ArrayList<Integer> accounts = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        loadAccounts();
        return super.onFragmentCreate();
    }

    private void loadAccounts() {
        accounts.clear();
        for (int a : SharedConfig.activeAccounts) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                accounts.add(a);
            }
        }
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString("HiddenAccountsTitle", R.string.HiddenAccountsTitle);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (HiddenAccountsHelper.isFeatureEnabled() && !HiddenAccountsHelper.isRevealed()) {
            finishFragment();
            return;
        }
        loadAccounts();
        updateRows();
    }

    @Override
    protected void updateRows() {
        super.updateRows();

        enableRow = rowCount++;
        enableAboutRow = rowCount++;

        if (HiddenAccountsHelper.isFeatureEnabled()) {
            toggleVisibilityRow = rowCount++;
            statusShadowRow = rowCount++;

            securityHeaderRow = rowCount++;
            requirePinRow = rowCount++;
            if (HiddenAccountsHelper.isPinEnabled()) {
                pinConfigRow = rowCount++;
                if (HiddenAccountsHelper.hasPin()) {
                    removePinRow = rowCount++;
                } else {
                    removePinRow = -1;
                }
            } else {
                pinConfigRow = -1;
                removePinRow = -1;
            }
            pinAboutRow = rowCount++;

            autoHideHeaderRow = rowCount++;
            autoHideTimeoutRow = rowCount++;
            hideNotificationsRow = rowCount++;
            hideSettingsRow = rowCount++;
            autoHideAboutRow = rowCount++;

            accountsHeaderRow = rowCount++;
            safeAccountRow = rowCount++;
            accountsStartRow = rowCount;
            rowCount += accounts.size();
            accountsEndRow = rowCount;
            accountsAboutRow = rowCount++;

            panicHeaderRow = rowCount++;
            panicCodeRow = rowCount++;
            if (HiddenAccountsHelper.hasPanicPin()) {
                removePanicCodeRow = rowCount++;
            } else {
                removePanicCodeRow = -1;
            }
            panicAboutRow = rowCount++;
        } else {
            toggleVisibilityRow = -1;
            statusShadowRow = -1;
            securityHeaderRow = -1;
            requirePinRow = -1;
            pinConfigRow = -1;
            removePinRow = -1;
            pinAboutRow = -1;
            autoHideHeaderRow = -1;
            autoHideTimeoutRow = -1;
            hideNotificationsRow = -1;
            hideSettingsRow = -1;
            autoHideAboutRow = -1;
            accountsHeaderRow = -1;
            safeAccountRow = -1;
            accountsStartRow = -1;
            accountsEndRow = -1;
            accountsAboutRow = -1;
            panicHeaderRow = -1;
            panicCodeRow = -1;
            removePanicCodeRow = -1;
            panicAboutRow = -1;
        }
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == enableRow) {
            boolean current = HiddenAccountsHelper.isFeatureEnabled();
            HiddenAccountsHelper.setFeatureEnabled(!current);
            if (!current && HiddenAccountsHelper.getSafeAccount() < 0 && !accounts.isEmpty()) {
                HiddenAccountsHelper.setSafeAccount(accounts.get(0));
            }
            updateRows();
            listAdapter.notifyDataSetChanged();
        } else if (position == toggleVisibilityRow) {
            if (HiddenAccountsHelper.isRevealed()) {
                HiddenAccountsHelper.hideAccounts(getParentActivity(), this);
                finishFragment();
                return;
            } else {
                HiddenAccountsHelper.revealAccounts(this);
            }
            listAdapter.notifyDataSetChanged();
        } else if (position == requirePinRow) {
            boolean current = HiddenAccountsHelper.isPinEnabled();
            HiddenAccountsHelper.setPinEnabled(!current);
            if (!current && !HiddenAccountsHelper.hasPin()) {
                showSetPinDialog(false);
            }
            updateRows();
            listAdapter.notifyDataSetChanged();
        } else if (position == pinConfigRow) {
            showSetPinDialog(false);
        } else if (position == removePinRow) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle(LocaleController.getString("HiddenAccountsPinDisable", R.string.HiddenAccountsPinDisable));
            builder.setMessage(LocaleController.getString("AreYouSure", R.string.AreYouSure));
            builder.setPositiveButton(LocaleController.getString("OK", R.string.OK), (d, w) -> {
                HiddenAccountsHelper.setPin(null);
                HiddenAccountsHelper.setPinEnabled(false);
                updateRows();
                listAdapter.notifyDataSetChanged();
            });
            builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
            showDialog(builder.create());
        } else if (position == autoHideTimeoutRow) {
            showTimeoutDialog();
        } else if (position == hideNotificationsRow) {
            boolean current = HiddenAccountsHelper.isHideNotifications();
            HiddenAccountsHelper.setHideNotifications(!current);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(!current);
            }
        } else if (position == hideSettingsRow) {
            boolean current = HiddenAccountsHelper.isHideSettingsWhenMasked();
            HiddenAccountsHelper.setHideSettingsWhenMasked(!current);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(!current);
            }
        } else if (position == safeAccountRow) {
            showSafeAccountDialog();
        } else if (position >= accountsStartRow && position < accountsEndRow) {
            int accountIndex = position - accountsStartRow;
            if (accountIndex >= 0 && accountIndex < accounts.size()) {
                int account = accounts.get(accountIndex);
                if (account == HiddenAccountsHelper.getSafeAccount()) {
                    BulletinFactory.of(this).createSimpleBulletin(R.drawable.msg_info, LocaleController.getString("HiddenAccountsSafeAccountAbout", R.string.HiddenAccountsSafeAccountAbout)).show();
                    return;
                }
                boolean currentHidden = HiddenAccountsHelper.isAccountConfiguredHidden(account);
                HiddenAccountsHelper.setAccountConfiguredHidden(account, !currentHidden);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(!currentHidden);
                }
            }
        } else if (position == panicCodeRow) {
            showSetPinDialog(true);
        } else if (position == removePanicCodeRow) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle(LocaleController.getString("HiddenAccountsPanicPinDisable", R.string.HiddenAccountsPanicPinDisable));
            builder.setMessage(LocaleController.getString("AreYouSure", R.string.AreYouSure));
            builder.setPositiveButton(LocaleController.getString("OK", R.string.OK), (d, w) -> {
                HiddenAccountsHelper.setPanicPin(null);
                updateRows();
                listAdapter.notifyDataSetChanged();
            });
            builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
            showDialog(builder.create());
        }
    }

    private void showTimeoutDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString("HiddenAccountsTimeout", R.string.HiddenAccountsTimeout));

        final CharSequence[] items = new CharSequence[]{
                LocaleController.getString("HiddenAccountsTimeoutImmediately", R.string.HiddenAccountsTimeoutImmediately),
                LocaleController.getString("HiddenAccountsTimeout1Min", R.string.HiddenAccountsTimeout1Min),
                LocaleController.getString("HiddenAccountsTimeout5Min", R.string.HiddenAccountsTimeout5Min),
                LocaleController.getString("HiddenAccountsTimeoutNever", R.string.HiddenAccountsTimeoutNever)
        };
        final int[] values = new int[]{0, 60, 300, -1};

        int current = HiddenAccountsHelper.getAutoHideTimeout();
        int selected = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                selected = i;
                break;
            }
        }

        builder.setItems(items, (dialog, which) -> {
            HiddenAccountsHelper.setAutoHideTimeout(values[which]);
            dialog.dismiss();
            listAdapter.notifyItemChanged(autoHideTimeoutRow);
        });
        builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showSafeAccountDialog() {
        if (accounts.isEmpty()) return;
        ArrayList<CharSequence> names = new ArrayList<>();
        ArrayList<Integer> validAccounts = new ArrayList<>();
        for (int a : accounts) {
            TLRPC.User user = AccountInstance.getInstance(a).getUserConfig().getCurrentUser();
            if (user != null) {
                names.add(UserObject.getUserName(user));
                validAccounts.add(a);
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString("HiddenAccountsSafeAccount", R.string.HiddenAccountsSafeAccount));
        builder.setItems(names.toArray(new CharSequence[0]), (dialog, which) -> {
            int chosen = validAccounts.get(which);
            HiddenAccountsHelper.setSafeAccount(chosen);
            HiddenAccountsHelper.setAccountConfiguredHidden(chosen, false);
            dialog.dismiss();
            updateRows();
            listAdapter.notifyDataSetChanged();
        });
        builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showSetPinDialog(boolean isPanic) {
        Activity activity = getParentActivity();
        if (activity == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(isPanic ? LocaleController.getString("HiddenAccountsPanicPinSet", R.string.HiddenAccountsPanicPinSet) : LocaleController.getString("HiddenAccountsPinSet", R.string.HiddenAccountsPinSet));

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
                    AndroidUtilities.shakeView(editText);
                    return;
                }
                if (isPanic) {
                    HiddenAccountsHelper.setPanicPin(code);
                } else {
                    HiddenAccountsHelper.setPin(code);
                    HiddenAccountsHelper.setPinEnabled(true);
                }
                AndroidUtilities.hideKeyboard(editText);
                dialog.dismiss();
                updateRows();
                listAdapter.notifyDataSetChanged();
                BulletinFactory.of(this).createSimpleBulletin(R.drawable.msg_check, LocaleController.getString("OK", R.string.OK)).show();
            });
        });
        showDialog(dialog);
    }

    private String getTimeoutDescription(int timeout) {
        if (timeout == 0) {
            return LocaleController.getString("HiddenAccountsTimeoutImmediately", R.string.HiddenAccountsTimeoutImmediately);
        } else if (timeout == 60) {
            return LocaleController.getString("HiddenAccountsTimeout1Min", R.string.HiddenAccountsTimeout1Min);
        } else if (timeout == 300) {
            return LocaleController.getString("HiddenAccountsTimeout5Min", R.string.HiddenAccountsTimeout5Min);
        } else {
            return LocaleController.getString("HiddenAccountsTimeoutNever", R.string.HiddenAccountsTimeoutNever);
        }
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case TYPE_SHADOW: {
                    holder.itemView.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell textCell = (TextSettingsCell) holder.itemView;
                    textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    if (position == toggleVisibilityRow) {
                        textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2));
                        textCell.setText(HiddenAccountsHelper.isRevealed() ? LocaleController.getString("HiddenAccountsHideNow", R.string.HiddenAccountsHideNow) : LocaleController.getString("HiddenAccountsRevealNow", R.string.HiddenAccountsRevealNow), false);
                    } else if (position == pinConfigRow) {
                        textCell.setText(HiddenAccountsHelper.hasPin() ? LocaleController.getString("HiddenAccountsPinChange", R.string.HiddenAccountsPinChange) : LocaleController.getString("HiddenAccountsPinSet", R.string.HiddenAccountsPinSet), removePinRow != -1);
                    } else if (position == removePinRow) {
                        textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteRedText3));
                        textCell.setText(LocaleController.getString("HiddenAccountsPinDisable", R.string.HiddenAccountsPinDisable), false);
                    } else if (position == autoHideTimeoutRow) {
                        textCell.setTextAndValue(LocaleController.getString("HiddenAccountsTimeout", R.string.HiddenAccountsTimeout), getTimeoutDescription(HiddenAccountsHelper.getAutoHideTimeout()), false);
                    } else if (position == safeAccountRow) {
                        int safe = HiddenAccountsHelper.getSafeAccount();
                        TLRPC.User u = safe >= 0 ? AccountInstance.getInstance(safe).getUserConfig().getCurrentUser() : null;
                        String name = u != null ? UserObject.getUserName(u) : "—";
                        textCell.setTextAndValue(LocaleController.getString("HiddenAccountsSafeAccount", R.string.HiddenAccountsSafeAccount), name, accounts.size() > 1);
                    } else if (position == panicCodeRow) {
                        textCell.setText(HiddenAccountsHelper.hasPanicPin() ? LocaleController.getString("PasscodePanicCodeEdit", R.string.PasscodePanicCodeEdit) : LocaleController.getString("HiddenAccountsPanicPinSet", R.string.HiddenAccountsPanicPinSet), removePanicCodeRow != -1);
                    } else if (position == removePanicCodeRow) {
                        textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteRedText3));
                        textCell.setText(LocaleController.getString("HiddenAccountsPanicPinDisable", R.string.HiddenAccountsPanicPinDisable), false);
                    }
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                    if (position == enableRow) {
                        checkCell.setTextAndCheck(LocaleController.getString("HiddenAccountsEnable", R.string.HiddenAccountsEnable), HiddenAccountsHelper.isFeatureEnabled(), false);
                    } else if (position == requirePinRow) {
                        checkCell.setTextAndCheck(LocaleController.getString("HiddenAccountsPin", R.string.HiddenAccountsPin), HiddenAccountsHelper.isPinEnabled(), pinConfigRow != -1);
                    } else if (position == hideNotificationsRow) {
                        checkCell.setTextAndCheck(LocaleController.getString("HiddenAccountsHideNotifications", R.string.HiddenAccountsHideNotifications), HiddenAccountsHelper.isHideNotifications(), hideSettingsRow != -1);
                    } else if (position == hideSettingsRow) {
                        checkCell.setTextAndCheck(LocaleController.getString("HideNekoSettingsMasked", R.string.HideNekoSettingsMasked), HiddenAccountsHelper.isHideSettingsWhenMasked(), false);
                    } else if (position >= accountsStartRow && position < accountsEndRow) {
                        int index = position - accountsStartRow;
                        int account = accounts.get(index);
                        TLRPC.User u = AccountInstance.getInstance(account).getUserConfig().getCurrentUser();
                        String name = u != null ? UserObject.getUserName(u) : ("Account " + account);
                        boolean isSafe = account == HiddenAccountsHelper.getSafeAccount();
                        if (isSafe) {
                            checkCell.setTextAndCheck(name + " (" + LocaleController.getString("HiddenAccountsSafeAccount", R.string.HiddenAccountsSafeAccount) + ")", false, index + 1 != accounts.size());
                            checkCell.setEnabled(false, null);
                        } else {
                            checkCell.setEnabled(true, null);
                            checkCell.setTextAndCheck(name, HiddenAccountsHelper.isAccountConfiguredHidden(account), index + 1 != accounts.size());
                        }
                    }
                    break;
                }
                case TYPE_HEADER: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == securityHeaderRow) {
                        headerCell.setText(LocaleController.getString("PasscodeNeko", R.string.PasscodeNeko));
                    } else if (position == autoHideHeaderRow) {
                        headerCell.setText(LocaleController.getString("HiddenAccountsTimeout", R.string.HiddenAccountsTimeout));
                    } else if (position == accountsHeaderRow) {
                        headerCell.setText(LocaleController.getString("HiddenAccountsAccountsHeader", R.string.HiddenAccountsAccountsHeader));
                    } else if (position == panicHeaderRow) {
                        headerCell.setText(LocaleController.getString("HiddenAccountsPanicPin", R.string.HiddenAccountsPanicPin));
                    }
                    break;
                }
                case TYPE_INFO_PRIVACY: {
                    TextInfoPrivacyCell infoCell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == enableAboutRow) {
                        infoCell.setText(LocaleController.getString("HiddenAccountsEnableAbout", R.string.HiddenAccountsEnableAbout));
                    } else if (position == pinAboutRow) {
                        infoCell.setText(LocaleController.getString("HiddenAccountsPinAbout", R.string.HiddenAccountsPinAbout));
                    } else if (position == autoHideAboutRow) {
                        infoCell.setText(LocaleController.getString("HiddenAccountsHideNotificationsAbout", R.string.HiddenAccountsHideNotificationsAbout));
                    } else if (position == accountsAboutRow) {
                        infoCell.setText(LocaleController.getString("HiddenAccountsAccountsAbout", R.string.HiddenAccountsAccountsAbout));
                    } else if (position == panicAboutRow) {
                        infoCell.setText(LocaleController.getString("HiddenAccountsPanicPinAbout", R.string.HiddenAccountsPanicPinAbout));
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == statusShadowRow) {
                return TYPE_SHADOW;
            } else if (position == enableRow || position == requirePinRow || position == hideNotificationsRow || (position >= accountsStartRow && position < accountsEndRow)) {
                return TYPE_CHECK;
            } else if (position == securityHeaderRow || position == autoHideHeaderRow || position == accountsHeaderRow || position == panicHeaderRow) {
                return TYPE_HEADER;
            } else if (position == enableAboutRow || position == pinAboutRow || position == autoHideAboutRow || position == accountsAboutRow || position == panicAboutRow) {
                return TYPE_INFO_PRIVACY;
            }
            return TYPE_SETTINGS;
        }
    }
}
