package tw.nekomimi.nekogram.settings;

import android.app.Dialog;
import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.URLSpanNoUnderline;
import org.telegram.ui.PasscodeActivity;

import java.util.ArrayList;
import java.util.Locale;

import tw.nekomimi.nekogram.helpers.PasscodeHelper;

public class NekoPasscodeSettingsActivity extends BaseNekoSettingsActivity {

    private boolean passcodeSet;

    private int showInSettingsRow;
    private int showInSettings2Row;

    private int enableStealthModeRow;
    private int stealthToggleRow;
    private int safeAccountRow;
    private int autoLockOnMinimizeRow;
    private int secretGestureRow;
    private int stealthSettings2Row;

    private int accountsStartRow;
    private int accountsEndRow;

    private int panicCodeRow;
    private int setPanicCodeRow;
    private int removePanicCodeRow;
    private int panicCode2Row;

    private int clearPasscodesRow;
    private int clearPasscodes2Row;

    private final ArrayList<Integer> accounts = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        accounts.clear();
        for (int a : SharedConfig.activeAccounts) {
            var u = AccountInstance.getInstance(a).getUserConfig().getCurrentUser();
            if (u != null) {
                accounts.add(a);
            }
        }
        return super.onFragmentCreate();
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (!passcodeSet) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString("PasscodeNeeded", R.string.PasscodeNeeded)).show();
            return;
        }
        if (position == enableStealthModeRow) {
            boolean current = PasscodeHelper.isStealthModeEnabled();
            PasscodeHelper.setStealthModeEnabled(!current);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(!current);
            }
            updateRows();
            listAdapter.notifyDataSetChanged();
            getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
        } else if (position == stealthToggleRow) {
            if (PasscodeHelper.isStealthModeRevealed()) {
                PasscodeHelper.lockHiddenAccounts(getParentActivity());
                BulletinFactory.of(this).createSimpleBulletin(R.drawable.msg_secret, LocaleController.getString("PasscodeStealthModeHidden", R.string.PasscodeStealthModeHidden)).show();
            } else {
                PasscodeHelper.revealAllHiddenAccounts();
                BulletinFactory.of(this).createSimpleBulletin(R.drawable.menu_unlock, LocaleController.getString("PasscodeStealthModeRevealed", R.string.PasscodeStealthModeRevealed)).show();
            }
            listAdapter.notifyItemChanged(stealthToggleRow);
            getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
        } else if (position == safeAccountRow) {
            if (accounts.isEmpty()) return;
            ArrayList<CharSequence> names = new ArrayList<>();
            ArrayList<Integer> validAccounts = new ArrayList<>();
            for (int a : accounts) {
                var user = AccountInstance.getInstance(a).getUserConfig().getCurrentUser();
                if (user != null) {
                    names.add(UserObject.getUserName(user) + (PasscodeHelper.isAccountConfiguredHidden(a) ? " (" + LocaleController.getString("PasscodeHideAccount", R.string.PasscodeHideAccount) + ")" : ""));
                    validAccounts.add(a);
                }
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle(LocaleController.getString("PasscodeSafeAccount", R.string.PasscodeSafeAccount));
            builder.setItems(names.toArray(new CharSequence[0]), (dialog, which) -> {
                int chosen = validAccounts.get(which);
                PasscodeHelper.setSafeAccount(chosen);
                dialog.dismiss();
                listAdapter.notifyItemChanged(safeAccountRow);
                BulletinFactory.of(this).createSimpleBulletin(R.drawable.msg_check, LocaleController.getString("PasscodeSetAsSafeSuccess", R.string.PasscodeSetAsSafeSuccess)).show();
            });
            builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
            showDialog(builder.create());
        } else if (position == autoLockOnMinimizeRow) {
            boolean current = PasscodeHelper.isAutoLockOnMinimize();
            PasscodeHelper.setAutoLockOnMinimize(!current);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(!current);
            }
        } else if (position == secretGestureRow) {
            boolean current = PasscodeHelper.isSecretGestureEnabled();
            PasscodeHelper.setSecretGestureEnabled(!current);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(!current);
            }
        } else if (position > accountsStartRow && position < accountsEndRow) {
            var account = accounts.get(position - accountsStartRow - 1);
            var builder = new AlertDialog.Builder(getParentActivity());

            var linearLayout = new LinearLayout(getParentActivity());
            linearLayout.setOrientation(LinearLayout.VERTICAL);

            if (PasscodeHelper.hasPasscodeForAccount(account)) {
                TextCheckCell hideAccount = new TextCheckCell(getParentActivity(), 23, true);
                hideAccount.setTextAndCheck(LocaleController.getString("PasscodeHideAccount", R.string.PasscodeHideAccount), PasscodeHelper.isAccountConfiguredHidden(account), false);
                hideAccount.setOnClickListener(view13 -> {
                    boolean hide = !hideAccount.isChecked();
                    PasscodeHelper.setHideAccount(account, hide);
                    hideAccount.setChecked(hide);
                    listAdapter.notifyItemChanged(position);
                    getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
                });
                hideAccount.setBackground(Theme.getSelectorDrawable(false));
                linearLayout.addView(hideAccount, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }

            TextCheckCell allowPanic = new TextCheckCell(getParentActivity(), 23, true);
            allowPanic.setTextAndCheck(LocaleController.getString("PasscodeAllowPanic", R.string.PasscodeAllowPanic), PasscodeHelper.isAccountAllowPanic(account), false);
            allowPanic.setOnClickListener(view13 -> {
                boolean panic = !allowPanic.isChecked();
                PasscodeHelper.setAccountAllowPanic(account, panic);
                allowPanic.setChecked(panic);
            });
            allowPanic.setBackground(Theme.getSelectorDrawable(false));
            linearLayout.addView(allowPanic, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            AlertDialog.AlertDialogCell setSafeAccount = new AlertDialog.AlertDialogCell(getParentActivity(), null);
            setSafeAccount.setTextAndIcon(LocaleController.getString("PasscodeSetAsSafe", R.string.PasscodeSetAsSafe), 0);
            setSafeAccount.setOnClickListener(viewSafe -> {
                builder.getDismissRunnable().run();
                PasscodeHelper.setSafeAccount(account);
                listAdapter.notifyItemChanged(safeAccountRow);
                BulletinFactory.of(this).createSimpleBulletin(R.drawable.msg_check, LocaleController.getString("PasscodeSetAsSafeSuccess", R.string.PasscodeSetAsSafeSuccess)).show();
            });
            setSafeAccount.setBackground(Theme.getSelectorDrawable(false));
            linearLayout.addView(setSafeAccount, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            AlertDialog.AlertDialogCell editPasscode = new AlertDialog.AlertDialogCell(getParentActivity(), null);
            editPasscode.setTextAndIcon(PasscodeHelper.hasPasscodeForAccount(account) ? LocaleController.getString("PasscodeEdit", R.string.PasscodeEdit) : LocaleController.getString("PasscodeSet", R.string.PasscodeSet), 0);
            editPasscode.setOnClickListener(view1 -> {
                builder.getDismissRunnable().run();
                presentFragment(new PasscodeActivity(PasscodeActivity.TYPE_SETUP_CODE, account));
            });
            editPasscode.setBackground(Theme.getSelectorDrawable(false));
            linearLayout.addView(editPasscode, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            if (PasscodeHelper.hasPasscodeForAccount(account)) {
                AlertDialog.AlertDialogCell removePasscode = new AlertDialog.AlertDialogCell(getParentActivity(), null);
                removePasscode.setTextAndIcon(LocaleController.getString("PasscodeRemove", R.string.PasscodeRemove), 0);
                removePasscode.setOnClickListener(view12 -> {
                    AlertDialog alertDialog = new AlertDialog.Builder(getParentActivity())
                            .setTitle(LocaleController.getString(R.string.PasscodeRemove))
                            .setMessage(LocaleController.getString(R.string.PasscodeRemoveConfirmMessage))
                            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                            .setPositiveButton(LocaleController.getString(R.string.DisablePasscodeTurnOff), (dialog, which) -> {
                                var hidden = PasscodeHelper.isAccountConfiguredHidden(account);
                                PasscodeHelper.removePasscodeForAccount(account);
                                listAdapter.notifyItemChanged(position);
                                if (hidden) {
                                    getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
                                }
                            }).create();
                    showDialog(alertDialog);
                    ((TextView) alertDialog.getButton(Dialog.BUTTON_POSITIVE)).setTextColor(Theme.getColor(Theme.key_dialogTextRed));
                });
                removePasscode.setBackground(Theme.getSelectorDrawable(false));
                linearLayout.addView(removePasscode, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }

            builder.setView(linearLayout);
            showDialog(builder.create());
        } else if (position == clearPasscodesRow) {
            PasscodeHelper.clearAll();
            finishFragment();
        } else if (position == setPanicCodeRow) {
            presentFragment(new PasscodeActivity(PasscodeActivity.TYPE_SETUP_CODE, Integer.MAX_VALUE));
        } else if (position == removePanicCodeRow) {
            AlertDialog alertDialog = new AlertDialog.Builder(getParentActivity())
                    .setTitle(LocaleController.getString(R.string.PasscodePanicCodeRemove))
                    .setMessage(LocaleController.getString(R.string.PasscodePanicCodeRemoveConfirmMessage))
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .setPositiveButton(LocaleController.getString(R.string.DisablePasscodeTurnOff), (dialog, which) -> {
                        PasscodeHelper.removePasscodeForAccount(Integer.MAX_VALUE);
                        listAdapter.notifyItemChanged(setPanicCodeRow);
                        listAdapter.notifyItemRemoved(removePanicCodeRow);
                        updateRows();
                    }).create();
            showDialog(alertDialog);
            ((TextView) alertDialog.getButton(Dialog.BUTTON_POSITIVE)).setTextColor(Theme.getColor(Theme.key_dialogTextRed));
        } else if (position == showInSettingsRow) {
            PasscodeHelper.setHideSettings(!PasscodeHelper.isSettingsHidden());
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(!PasscodeHelper.isSettingsHidden());
            }
        }
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString("PasscodeNeko", R.string.PasscodeNeko);
    }

    @Override
    protected String getKey() {
        return PasscodeHelper.getSettingsKey();
    }

    @Override
    public void onResume() {
        passcodeSet = SharedConfig.passcodeHash.length() > 0;
        if (!passcodeSet) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString("PasscodeNeeded", R.string.PasscodeNeeded)).show();
        }
        updateRows();
        super.onResume();
    }

    @Override
    protected void updateRows() {
        super.updateRows();

        showInSettingsRow = rowCount++;
        showInSettings2Row = rowCount++;

        enableStealthModeRow = rowCount++;
        if (PasscodeHelper.isStealthModeEnabled()) {
            stealthToggleRow = rowCount++;
            safeAccountRow = rowCount++;
            autoLockOnMinimizeRow = rowCount++;
            secretGestureRow = rowCount++;
        } else {
            stealthToggleRow = -1;
            safeAccountRow = -1;
            autoLockOnMinimizeRow = -1;
            secretGestureRow = -1;
        }
        stealthSettings2Row = rowCount++;

        accountsStartRow = rowCount++;
        rowCount += accounts.size();
        accountsEndRow = rowCount++;

        panicCodeRow = rowCount++;
        setPanicCodeRow = rowCount++;
        if (!PasscodeHelper.hasPanicCode()) {
            removePanicCodeRow = -1;
        } else {
            removePanicCodeRow = rowCount++;
        }
        panicCode2Row = rowCount++;

        clearPasscodesRow = -1;
        clearPasscodes2Row = -1;
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 1: {
                    if (position == clearPasscodes2Row) {
                        holder.itemView.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    } else {
                        holder.itemView.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    }
                    break;
                }
                case 2: {
                    TextSettingsCell textCell = (TextSettingsCell) holder.itemView;
                    textCell.setCanDisable(true);
                    textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    if (position == stealthToggleRow) {
                        textCell.setText(PasscodeHelper.isStealthModeRevealed() ? LocaleController.getString("PasscodeStealthHide", R.string.PasscodeStealthHide) : LocaleController.getString("PasscodeStealthToggle", R.string.PasscodeStealthToggle), true);
                    } else if (position == safeAccountRow) {
                        int safe = PasscodeHelper.getSafeAccount();
                        var u = AccountInstance.getInstance(safe).getUserConfig().getCurrentUser();
                        String name = u != null ? UserObject.getUserName(u) : ("Account " + safe);
                        textCell.setTextAndValue(LocaleController.getString("PasscodeSafeAccount", R.string.PasscodeSafeAccount), name, true);
                    } else if (position == setPanicCodeRow) {
                        textCell.setText(PasscodeHelper.hasPanicCode() ? LocaleController.getString("PasscodePanicCodeEdit", R.string.PasscodePanicCodeEdit) : LocaleController.getString("PasscodePanicCodeSet", R.string.PasscodePanicCodeSet), removePanicCodeRow != -1);
                    } else if (position == clearPasscodesRow) {
                        textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteRedText3));
                        textCell.setText("Clear passcodes", false);
                    } else if (position == removePanicCodeRow) {
                        textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteRedText3));
                        textCell.setText(LocaleController.getString("PasscodePanicCodeRemove", R.string.PasscodePanicCodeRemove), false);
                    }
                    break;
                }
                case 3: {
                    TextCheckCell textCell = (TextCheckCell) holder.itemView;
                    textCell.setEnabled(passcodeSet, null);
                    if (position == showInSettingsRow) {
                        textCell.setTextAndCheck(LocaleController.getString("PasscodeShowInSettings", R.string.PasscodeShowInSettings), !PasscodeHelper.isSettingsHidden(), false);
                    } else if (position == enableStealthModeRow) {
                        textCell.setTextAndCheck(LocaleController.getString("PasscodeEnableStealthMode", R.string.PasscodeEnableStealthMode), PasscodeHelper.isStealthModeEnabled(), true);
                    } else if (position == autoLockOnMinimizeRow) {
                        textCell.setTextAndCheck(LocaleController.getString("PasscodeAutoLockOnMinimize", R.string.PasscodeAutoLockOnMinimize), PasscodeHelper.isAutoLockOnMinimize(), true);
                    } else if (position == secretGestureRow) {
                        textCell.setTextAndCheck(LocaleController.getString("PasscodeSecretGesture", R.string.PasscodeSecretGesture), PasscodeHelper.isSecretGestureEnabled(), false);
                    }
                    break;
                }
                case 4: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    cell.setEnabled(passcodeSet, null);
                    if (position == accountsStartRow) {
                        cell.setText(LocaleController.getString("Account", R.string.Account));
                    } else if (position == panicCodeRow) {
                        cell.setText(LocaleController.getString("PasscodePanicCode", R.string.PasscodePanicCode));
                    }
                    break;
                }
                case 7: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setEnabled(passcodeSet, null);
                    cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    if (position == accountsEndRow) {
                        cell.setText(LocaleController.getString("PasscodeAbout", R.string.PasscodeAbout));
                    } else if (position == stealthSettings2Row) {
                        cell.setText(PasscodeHelper.isStealthModeEnabled()
                                ? LocaleController.getString("PasscodeAutoLockOnMinimizeAbout", R.string.PasscodeAutoLockOnMinimizeAbout)
                                : LocaleController.getString("PasscodeEnableStealthModeAbout", R.string.PasscodeEnableStealthModeAbout));
                    } else if (position == panicCode2Row) {
                        cell.setText(LocaleController.getString("PasscodePanicCodeAbout", R.string.PasscodePanicCodeAbout));
                        if (clearPasscodesRow == -1) {
                            cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                        }
                    } else if (position == showInSettings2Row) {
                        var link = String.format(Locale.ENGLISH, "https://t.me/nasettings/%s", PasscodeHelper.getSettingsKey());
                        var stringBuilder = new SpannableStringBuilder(AndroidUtilities.replaceTags(LocaleController.getString("PasscodeShowInSettingsAbout", R.string.PasscodeShowInSettingsAbout)));
                        stringBuilder.append("\n").append(link);
                        stringBuilder.setSpan(new URLSpanNoUnderline(null) {
                            @Override
                            public void onClick(@NonNull View view) {
                                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE);
                                android.content.ClipData clip = android.content.ClipData.newPlainText("label", link);
                                clipboard.setPrimaryClip(clip);
                                BulletinFactory.of(NekoPasscodeSettingsActivity.this).createCopyLinkBulletin().show();
                            }
                        }, stringBuilder.length() - link.length(), stringBuilder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        cell.setText(stringBuilder);
                    }
                    break;
                }
                case 11: {
                    AccountCell cell = (AccountCell) holder.itemView;
                    cell.setEnabled(passcodeSet);
                    int account = accounts.get(position - accountsStartRow - 1);
                    cell.setAccount(account, PasscodeHelper.hasPasscodeForAccount(account), position + 1 != accountsEndRow);
                    break;
                }
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return passcodeSet && super.isEnabled(holder);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == clearPasscodes2Row) {
                return 1;
            } else if (position == clearPasscodesRow || position == setPanicCodeRow || position == removePanicCodeRow || position == stealthToggleRow || position == safeAccountRow) {
                return 2;
            } else if (position == showInSettingsRow || position == enableStealthModeRow || position == autoLockOnMinimizeRow || position == secretGestureRow) {
                return 3;
            } else if (position == accountsStartRow || position == panicCodeRow) {
                return 4;
            } else if (position == showInSettings2Row || position == accountsEndRow || position == panicCode2Row || position == stealthSettings2Row) {
                return 7;
            } else if (position > accountsStartRow && position < accountsEndRow) {
                return 11;
            }
            return 2;
        }
    }

}
