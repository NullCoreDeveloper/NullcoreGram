package tw.nekomimi.nekogram.helpers;

import android.app.Activity;
import android.view.Gravity;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

import tw.nekomimi.nekogram.NekoConfig;

public class MediaConfirmHelper {

    public static boolean checkConfirmSticker(BaseFragment fragment, TLRPC.Document sticker, Runnable onConfirm) {
        if (!NekoConfig.confirmSendSticker.Bool() || fragment == null || onConfirm == null) {
            return false;
        }

        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return false;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString("ConfirmSendStickerTitle", R.string.ConfirmSendStickerTitle));

        if (sticker != null) {
            FrameLayout container = new FrameLayout(activity);
            BackupImageView imageView = new BackupImageView(activity);
            imageView.setAspectFit(true);
            imageView.setImage(ImageLocation.getForDocument(sticker), "130_130", "webp", null, sticker);
            container.addView(imageView, LayoutHelper.createFrame(130, 130, Gravity.CENTER, 0, 12, 0, 8));
            builder.setView(container);
        } else {
            builder.setMessage(LocaleController.getString("ConfirmSendStickerMessage", R.string.ConfirmSendStickerMessage));
        }

        builder.setPositiveButton(LocaleController.getString("Send", R.string.Send), (dialog, which) -> onConfirm.run());
        builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);

        fragment.showDialog(builder.create());
        return true;
    }

    public static boolean checkConfirmGif(BaseFragment fragment, Runnable onConfirm) {
        if (!NekoConfig.confirmSendGif.Bool() || fragment == null || onConfirm == null) {
            return false;
        }

        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return false;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString("ConfirmSendGifTitle", R.string.ConfirmSendGifTitle));
        builder.setMessage(LocaleController.getString("ConfirmSendGifMessage", R.string.ConfirmSendGifMessage));

        builder.setPositiveButton(LocaleController.getString("Send", R.string.Send), (dialog, which) -> onConfirm.run());
        builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);

        fragment.showDialog(builder.create());
        return true;
    }

    public static boolean checkConfirmPhotos(BaseFragment fragment, ArrayList<SendMessagesHelper.SendingMediaInfo> photos, Runnable onConfirm) {
        if (!NekoConfig.confirmSendMedia.Bool() || fragment == null || onConfirm == null || photos == null || photos.isEmpty()) {
            return false;
        }

        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return false;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);

        if (photos.size() == 1) {
            SendMessagesHelper.SendingMediaInfo info = photos.get(0);
            String title = info.isVideo ?
                    LocaleController.getString("ConfirmSendVideoTitle", R.string.ConfirmSendVideoTitle) :
                    LocaleController.getString("ConfirmSendPhotoTitle", R.string.ConfirmSendPhotoTitle);
            builder.setTitle(title);

            String path = info.path != null ? info.path : info.imagePath;
            if (path != null) {
                FrameLayout container = new FrameLayout(activity);
                BackupImageView imageView = new BackupImageView(activity);
                imageView.setRoundRadius(AndroidUtilities.dp(8));
                imageView.setImage(path, null, null);
                container.addView(imageView, LayoutHelper.createFrame(140, 140, Gravity.CENTER, 0, 12, 0, 8));
                builder.setView(container);
            } else {
                builder.setMessage(LocaleController.getString("ConfirmSendMediaMessage", R.string.ConfirmSendMediaMessage));
            }
        } else {
            String title = String.format(LocaleController.getString("ConfirmSendMediaTitleMultiple", R.string.ConfirmSendMediaTitleMultiple), photos.size());
            builder.setTitle(title);
            builder.setMessage(LocaleController.getString("ConfirmSendMediaMessage", R.string.ConfirmSendMediaMessage));
        }

        builder.setPositiveButton(LocaleController.getString("Send", R.string.Send), (dialog, which) -> onConfirm.run());
        builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);

        fragment.showDialog(builder.create());
        return true;
    }
}
