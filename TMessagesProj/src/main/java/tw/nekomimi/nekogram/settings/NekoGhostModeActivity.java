/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package tw.nekomimi.nekogram.settings;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Cells.*;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.Locale;

import org.telegram.tgnet.TLRPC;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.utils.AyuGhostConfig;
import tw.nekomimi.nekogram.utils.AyuGhostUtils;

public class NekoGhostModeActivity extends BaseNekoSettingsActivity {
    // title
    private int selectedAccount = UserConfig.selectedAccount;

    private int accountSelectorRow;
    private int GhostHeaderRow;
    private int GhostModeTitleRow;

    private int sendReadMessagePacketsRow;
    private int sendOnlinePacketsRow;
    private int sendUploadProgressRow;
    private int sendReadStoryPacketsRow;
    private int sendOfflineAfterOnlineRow;
    private int markReadAfterSendRow;
    private int saveDeletedMessagesRow;
    private int saveTtlMediaRow;
    private int saveEditedMessagesRow;

    private int ghostDividerRow;
    private int DrawerHeaderRow;
    private int showGhostToggleInDrawerRow;
    private boolean ghostModeMenuExpanded;

    @Override
    protected void updateRows() {
        super.updateRows();

        accountSelectorRow = addRow();
        GhostHeaderRow = addRow();
        GhostModeTitleRow = addRow();
        if (ghostModeMenuExpanded) {
            sendReadMessagePacketsRow = addRow();
            sendOnlinePacketsRow = addRow();
            sendUploadProgressRow = addRow();
            sendReadStoryPacketsRow = addRow();
            sendOfflineAfterOnlineRow = addRow();
        } else {
            sendReadMessagePacketsRow = -1;
            sendOnlinePacketsRow = -1;
            sendUploadProgressRow = -1;
            sendReadStoryPacketsRow = -1;
            sendOfflineAfterOnlineRow = -1;
        }
        markReadAfterSendRow = addRow();
        saveDeletedMessagesRow = addRow();
        saveTtlMediaRow = addRow();
        saveEditedMessagesRow = addRow();
        ghostDividerRow = addRow();
        DrawerHeaderRow = addRow();
        showGhostToggleInDrawerRow = addRow();
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
    }

    private void updateGhostViews() {
        var isActive = AyuGhostConfig.isGhostModeActive(selectedAccount);

        listAdapter.notifyItemChanged(GhostModeTitleRow, PARTIAL);
        listAdapter.notifyItemChanged(sendReadMessagePacketsRow, !isActive);
        listAdapter.notifyItemChanged(sendOnlinePacketsRow, !isActive);
        listAdapter.notifyItemChanged(sendUploadProgressRow, !isActive);
        listAdapter.notifyItemChanged(sendReadStoryPacketsRow, !isActive);
        listAdapter.notifyItemChanged(sendOfflineAfterOnlineRow, isActive);

        NotificationCenter.getInstance(selectedAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
    }

    private void showAccountSelectBottomSheet() {
        BottomSheet.Builder builder = new BottomSheet.Builder(getParentActivity());
        builder.setApplyTopPadding(false);
        
        RecyclerListView listView = new RecyclerListView(getParentActivity());
        listView.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(getParentActivity()));
        listView.setAdapter(new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return true;
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
                TextSettingsCell textCell = new TextSettingsCell(getParentActivity());
                textCell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(textCell);
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                TextSettingsCell textCell = (TextSettingsCell) holder.itemView;
                TLRPC.User user = UserConfig.getInstance(position).getCurrentUser();
                String name = user != null ? UserObject.getUserName(user) : "Account " + position;
                textCell.setText(name, position == selectedAccount);
            }

            @Override
            public int getItemCount() {
                return UserConfig.MAX_ACCOUNT_COUNT;
            }
        });
        
        listView.setOnItemClickListener((view, position) -> {
            if (UserConfig.getInstance(position).isClientActivated()) {
                selectedAccount = position;
                listAdapter.notifyDataSetChanged();
                builder.getDismissRunnable().run();
            }
        });
        
        builder.setCustomView(listView);
        builder.setTitle("Select Account for Ghost Mode");
        showDialog(builder.create());
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == accountSelectorRow) {
            showAccountSelectBottomSheet();
        } else if (position == GhostModeTitleRow) {
            ghostModeMenuExpanded ^= true;
            updateRows();
            listAdapter.notifyItemChanged(GhostModeTitleRow, PARTIAL);
            if (ghostModeMenuExpanded) {
                listAdapter.notifyItemRangeInserted(GhostModeTitleRow + 1, 5);
            } else {
                listAdapter.notifyItemRangeRemoved(GhostModeTitleRow + 1, 5);
            }
        } else if (position == sendReadMessagePacketsRow) {
            AyuGhostConfig.putBoolean(selectedAccount, "sendReadMessagePackets", AyuGhostConfig.sendReadMessagePackets[selectedAccount] ^= true);
            ((CheckBoxCell) view).setChecked(AyuGhostConfig.sendReadMessagePackets[selectedAccount], true);
            AyuGhostUtils.setAllowReadPacket(selectedAccount, false, -1);
            updateGhostViews();
        } else if (position == sendOnlinePacketsRow) {
            AyuGhostConfig.putBoolean(selectedAccount, "sendOnlinePackets", AyuGhostConfig.sendOnlinePackets[selectedAccount] ^= true);
            ((CheckBoxCell) view).setChecked(AyuGhostConfig.sendOnlinePackets[selectedAccount], true);
            updateGhostViews();
        } else if (position == sendUploadProgressRow) {
            AyuGhostConfig.putBoolean(selectedAccount, "sendUploadProgress", AyuGhostConfig.sendUploadProgress[selectedAccount] ^= true);
            ((CheckBoxCell) view).setChecked(AyuGhostConfig.sendUploadProgress[selectedAccount], true);
            updateGhostViews();
        } else if (position == sendReadStoryPacketsRow) {
            AyuGhostConfig.putBoolean(selectedAccount, "sendReadStoryPackets", AyuGhostConfig.sendReadStoryPackets[selectedAccount] ^= true);
            ((CheckBoxCell) view).setChecked(AyuGhostConfig.sendReadStoryPackets[selectedAccount], true);
            updateGhostViews();
        } else if (position == sendOfflineAfterOnlineRow) {
            AyuGhostConfig.putBoolean(selectedAccount, "sendOfflineAfterOnline", AyuGhostConfig.sendOfflineAfterOnline[selectedAccount] ^= true);
            ((CheckBoxCell) view).setChecked(AyuGhostConfig.sendOfflineAfterOnline[selectedAccount], true);
            updateGhostViews();
        } else if (position == markReadAfterSendRow) {
            AyuGhostConfig.putBoolean(selectedAccount, "markReadAfterSend", AyuGhostConfig.markReadAfterSend[selectedAccount] ^= true);
            ((TextCheckCell) view).setChecked(AyuGhostConfig.markReadAfterSend[selectedAccount]);
            AyuGhostUtils.setAllowReadPacket(selectedAccount, false, -1);
        } else if (position == saveDeletedMessagesRow) {
            AyuGhostConfig.putBoolean(selectedAccount, "saveDeletedMessages", AyuGhostConfig.saveDeletedMessages[selectedAccount] ^= true);
            ((TextCheckCell) view).setChecked(AyuGhostConfig.saveDeletedMessages[selectedAccount]);
        } else if (position == saveTtlMediaRow) {
            AyuGhostConfig.putBoolean(selectedAccount, "saveTtlMedia", AyuGhostConfig.saveTtlMedia[selectedAccount] ^= true);
            ((TextCheckCell) view).setChecked(AyuGhostConfig.saveTtlMedia[selectedAccount]);
        } else if (position == saveEditedMessagesRow) {
            AyuGhostConfig.putBoolean(selectedAccount, "saveEditedMessages", AyuGhostConfig.saveEditedMessages[selectedAccount] ^= true);
            ((TextCheckCell) view).setChecked(AyuGhostConfig.saveEditedMessages[selectedAccount]);
        } else if (position == showGhostToggleInDrawerRow) {
            AyuGhostConfig.putBoolean(selectedAccount, "showGhostToggleInDrawer", AyuGhostConfig.showGhostToggleInDrawer[selectedAccount] ^= true);
            ((TextCheckCell) view).setChecked(AyuGhostConfig.showGhostToggleInDrawer[selectedAccount]);

            NotificationCenter.getInstance(selectedAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
        }
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.GhostMode);
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    private int getGhostModeSelectedCount() {
        int count = 0;
        if (!AyuGhostConfig.sendReadMessagePackets[selectedAccount]) count++;
        if (!AyuGhostConfig.sendOnlinePackets[selectedAccount]) count++;
        if (!AyuGhostConfig.sendUploadProgress[selectedAccount]) count++;
        if (!AyuGhostConfig.sendReadStoryPackets[selectedAccount]) count++;
        if (AyuGhostConfig.sendOfflineAfterOnline[selectedAccount]) count++;
        return count;
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean payload, boolean divider) {
            switch (holder.getItemViewType()) {
                case TYPE_SHADOW:
                    holder.itemView.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    break;
                case TYPE_HEADER:
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == GhostHeaderRow) {
                        headerCell.setText(LocaleController.getString(R.string.GhostElements));
                    }
                    if (position == DrawerHeaderRow) {
                        headerCell.setText(LocaleController.getString(R.string.DrawerElements));
                    }
                    break;
                case TYPE_CHECK:
                    TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                    textCheckCell.setEnabled(true, null);
                    if (position == markReadAfterSendRow) {
                        textCheckCell.setTextAndCheck(LocaleController.getString(R.string.MarkReadAfterAction), AyuGhostConfig.markReadAfterSend[selectedAccount], divider);
                    } else if (position == saveDeletedMessagesRow) {
                        textCheckCell.setTextAndCheck("Save Deleted Messages", AyuGhostConfig.saveDeletedMessages[selectedAccount], divider);
                    } else if (position == saveTtlMediaRow) {
                        textCheckCell.setTextAndCheck("Save TTL Media", AyuGhostConfig.saveTtlMedia[selectedAccount], divider);
                    } else if (position == saveEditedMessagesRow) {
                        textCheckCell.setTextAndCheck("Save Edited Messages", AyuGhostConfig.saveEditedMessages[selectedAccount], divider);
                    } else if (position == showGhostToggleInDrawerRow) {
                        textCheckCell.setTextAndCheck(LocaleController.getString(R.string.GhostMode), AyuGhostConfig.showGhostToggleInDrawer[selectedAccount], divider);
                    }
                    break;
                case TYPE_CHECK2:
                    TextCheckCell2 textCheckCell2 = (TextCheckCell2) holder.itemView;
                    if (position == GhostModeTitleRow) {
                        int selectedCount = getGhostModeSelectedCount();
                        textCheckCell2.setTextAndCheck(LocaleController.getString(R.string.GhostMode), AyuGhostConfig.isGhostModeActive(selectedAccount), divider, true);
                        textCheckCell2.setCollapseArrow(String.format(Locale.US, "%d/5", selectedCount), !ghostModeMenuExpanded, () -> {
                            AyuGhostConfig.toggleGhostMode(selectedAccount);
                            updateGhostViews();
                        });
                    }
                    textCheckCell2.getCheckBox().setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
                    textCheckCell2.getCheckBox().setDrawIconType(0);
                    break;
                case TYPE_CHECKBOX2:
                    CheckBoxCell checkBoxCell = (CheckBoxCell) holder.itemView;
                    if (position == sendReadMessagePacketsRow) {
                        checkBoxCell.setText(LocaleController.getString(R.string.DontReadMessages), "", !AyuGhostConfig.sendReadMessagePackets[selectedAccount], divider, true);
                    } else if (position == sendOnlinePacketsRow) {
                        checkBoxCell.setText(LocaleController.getString(R.string.DontSendOnlinePackets), "", !AyuGhostConfig.sendOnlinePackets[selectedAccount], divider, true);
                    } else if (position == sendUploadProgressRow) {
                        checkBoxCell.setText(LocaleController.getString(R.string.DontSendUploadProgress), "", !AyuGhostConfig.sendUploadProgress[selectedAccount], divider, true);
                    } else if (position == sendReadStoryPacketsRow) {
                        checkBoxCell.setText(LocaleController.getString(R.string.DontReadStories), "", !AyuGhostConfig.sendReadStoryPackets[selectedAccount], divider, true);
                    } else if (position == sendOfflineAfterOnlineRow) {
                        checkBoxCell.setText(LocaleController.getString(R.string.SendOfflinePacketAfterOnline), "", AyuGhostConfig.sendOfflineAfterOnline[selectedAccount], divider, true);
                    }
                    checkBoxCell.setPad(1);
                    break;
                case TYPE_SETTINGS:
                    TextSettingsCell textSettingsCell = (TextSettingsCell) holder.itemView;
                    if (position == accountSelectorRow) {
                        TLRPC.User user = UserConfig.getInstance(selectedAccount).getCurrentUser();
                        String name = user != null ? UserObject.getUserName(user) : "Account " + selectedAccount;
                        textSettingsCell.setTextAndValue("Configure Ghost Mode for Account", name, divider);
                    }
                    break;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == accountSelectorRow) {
                return TYPE_SETTINGS;
            }
            if (position == ghostDividerRow) {
                return TYPE_SHADOW;
            }
            if (position == GhostHeaderRow || position == DrawerHeaderRow) {
                return TYPE_HEADER;
            }
            if (position == GhostModeTitleRow) {
                return TYPE_CHECK2;
            }
            if (position >= sendReadMessagePacketsRow && position <= sendOfflineAfterOnlineRow) {
                return TYPE_CHECKBOX2;
            }
            if (position == markReadAfterSendRow || position == saveDeletedMessagesRow || position == saveTtlMediaRow || position == saveEditedMessagesRow || position == showGhostToggleInDrawerRow) {
                return TYPE_CHECK;
            }
            return super.getItemViewType(position);
        }
    }
}
