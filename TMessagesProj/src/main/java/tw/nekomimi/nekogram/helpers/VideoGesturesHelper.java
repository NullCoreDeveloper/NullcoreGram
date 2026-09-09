package tw.nekomimi.nekogram.helpers;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.media.AudioManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.VideoPlayer;
import org.telegram.ui.PhotoViewer;

import tw.nekomimi.nekogram.NekoConfig;

public class VideoGesturesHelper {

    private static final int GESTURE_NONE = 0;
    private static final int GESTURE_BRIGHTNESS = 1;
    private static final int GESTURE_VOLUME = 2;
    private static final int GESTURE_SEEK = 3;

    private static int currentGesture = GESTURE_NONE;
    private static float downX;
    private static float downY;
    private static float initialBrightness = 0.5f;
    private static int initialVolume = 0;
    private static long initialPosition = 0;
    private static long targetSeekPosition = 0;

    private static GesturesOverlayView overlayView;

    public static boolean onTouchEvent(PhotoViewer photoViewer, MotionEvent ev, boolean isCurrentVideo) {
        if (photoViewer == null || !NekoConfig.videoPlayerGestures.Bool() || !isCurrentVideo) {
            return false;
        }

        VideoPlayer player = photoViewer.getVideoPlayer();
        if (player == null) {
            return false;
        }

        int action = ev.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                downX = ev.getRawX();
                downY = ev.getRawY();
                currentGesture = GESTURE_NONE;
                targetSeekPosition = 0;

                Activity activity = photoViewer.getParentActivity();
                if (activity != null) {
                    WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
                    if (lp.screenBrightness >= 0) {
                        initialBrightness = lp.screenBrightness;
                    } else {
                        try {
                            int sys = Settings.System.getInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
                            initialBrightness = Math.max(0.01f, sys / 255.0f);
                        } catch (Exception e) {
                            initialBrightness = 0.5f;
                        }
                    }

                    AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        initialVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                    }
                }

                initialPosition = player.getCurrentPosition();
                return false;
            }

            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getRawX() - downX;
                float dy = ev.getRawY() - downY;
                float absDx = Math.abs(dx);
                float absDy = Math.abs(dy);
                int threshold = AndroidUtilities.dp(16);

                if (currentGesture == GESTURE_NONE) {
                    if (absDx > threshold || absDy > threshold) {
                        if (absDx > absDy * 1.3f) {
                            if (player.getDuration() > 0) {
                                currentGesture = GESTURE_SEEK;
                            }
                        } else if (absDy > absDx * 1.3f) {
                            int screenWidth = photoViewer.windowView != null && photoViewer.windowView.getWidth() > 0 ?
                                    photoViewer.windowView.getWidth() : AndroidUtilities.displaySize.x;
                            if (downX < screenWidth * 0.45f) {
                                currentGesture = GESTURE_BRIGHTNESS;
                            } else if (downX > screenWidth * 0.55f) {
                                currentGesture = GESTURE_VOLUME;
                            }
                        }
                    }
                }

                if (currentGesture == GESTURE_NONE) {
                    return false;
                }

                ensureOverlay(photoViewer);
                if (overlayView == null) {
                    return false;
                }

                int screenHeight = photoViewer.windowView != null && photoViewer.windowView.getHeight() > 0 ?
                        photoViewer.windowView.getHeight() : AndroidUtilities.displaySize.y;
                int screenWidth = photoViewer.windowView != null && photoViewer.windowView.getWidth() > 0 ?
                        photoViewer.windowView.getWidth() : AndroidUtilities.displaySize.x;

                if (currentGesture == GESTURE_BRIGHTNESS) {
                    Activity activity = photoViewer.getParentActivity();
                    if (activity != null) {
                        Window window = activity.getWindow();
                        WindowManager.LayoutParams lp = window.getAttributes();
                        float delta = (downY - ev.getRawY()) / (float) screenHeight;
                        float newBrightness = Math.max(0.01f, Math.min(1.0f, initialBrightness + delta));
                        lp.screenBrightness = newBrightness;
                        window.setAttributes(lp);
                        overlayView.showBrightness((int) (newBrightness * 100));
                    }
                    return true;
                } else if (currentGesture == GESTURE_VOLUME) {
                    Activity activity = photoViewer.getParentActivity();
                    if (activity != null) {
                        AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
                        if (am != null) {
                            int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                            float delta = (downY - ev.getRawY()) / (float) screenHeight;
                            int newVol = Math.max(0, Math.min(maxVol, Math.round(initialVolume + delta * maxVol)));
                            try {
                                am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0);
                            } catch (Exception ignored) {}
                            int percent = maxVol > 0 ? (int) ((newVol / (float) maxVol) * 100) : 0;
                            overlayView.showVolume(percent, newVol == 0);
                        }
                    }
                    return true;
                } else if (currentGesture == GESTURE_SEEK) {
                    long duration = player.getDuration();
                    if (duration > 0) {
                        long deltaMs = (long) ((dx / (float) screenWidth) * 90000L);
                        targetSeekPosition = Math.max(0, Math.min(duration, initialPosition + deltaMs));
                        long diffSec = (targetSeekPosition - initialPosition) / 1000;
                        String sign = diffSec >= 0 ? "+" : "";
                        String diffStr = sign + diffSec + "s";
                        String timeStr = AndroidUtilities.formatShortDuration((int) (targetSeekPosition / 1000)) +
                                " / " + AndroidUtilities.formatShortDuration((int) (duration / 1000));
                        overlayView.showSeek(diffStr, timeStr, diffSec >= 0);
                    }
                    return true;
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (currentGesture != GESTURE_NONE) {
                    if (currentGesture == GESTURE_SEEK && targetSeekPosition >= 0) {
                        player.seekTo(targetSeekPosition);
                    }
                    if (overlayView != null) {
                        overlayView.dismiss();
                    }
                    currentGesture = GESTURE_NONE;
                    return true;
                }
                break;
            }
        }

        return false;
    }

    public static void onReset() {
        currentGesture = GESTURE_NONE;
        if (overlayView != null) {
            overlayView.animate().cancel();
            overlayView.setVisibility(View.GONE);
        }
    }

    private static void ensureOverlay(PhotoViewer photoViewer) {
        if (photoViewer.windowView == null) {
            return;
        }
        if (overlayView == null || overlayView.getParent() != photoViewer.windowView) {
            if (overlayView != null && overlayView.getParent() != null) {
                ((FrameLayout) overlayView.getParent()).removeView(overlayView);
            }
            overlayView = new GesturesOverlayView(photoViewer.windowView.getContext());
            photoViewer.windowView.addView(overlayView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        }
    }

    private static class GesturesOverlayView extends FrameLayout {
        private final LinearLayout cardView;
        private final ImageView iconView;
        private final TextView primaryText;
        private final TextView secondaryText;
        private final FrameLayout progressBarContainer;
        private final View progressBarFill;

        public GesturesOverlayView(Context context) {
            super(context);
            setVisibility(View.GONE);
            setClickable(false);
            setFocusable(false);

            cardView = new LinearLayout(context);
            cardView.setOrientation(LinearLayout.VERTICAL);
            cardView.setGravity(Gravity.CENTER);
            cardView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(16), 0xCC1A1A1A));
            cardView.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(16), AndroidUtilities.dp(20), AndroidUtilities.dp(16));
            addView(cardView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

            iconView = new ImageView(context);
            iconView.setColorFilter(Color.WHITE);
            cardView.addView(iconView, LayoutHelper.createLinear(36, 36, Gravity.CENTER_HORIZONTAL));

            primaryText = new TextView(context);
            primaryText.setTextColor(Color.WHITE);
            primaryText.setTextSize(17);
            primaryText.setTypeface(AndroidUtilities.bold());
            primaryText.setGravity(Gravity.CENTER);
            cardView.addView(primaryText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

            secondaryText = new TextView(context);
            secondaryText.setTextColor(0xAAFFFFFF);
            secondaryText.setTextSize(13);
            secondaryText.setGravity(Gravity.CENTER);
            cardView.addView(secondaryText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 2, 0, 0));

            progressBarContainer = new FrameLayout(context);
            progressBarContainer.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(2), 0x44FFFFFF));
            cardView.addView(progressBarContainer, LayoutHelper.createLinear(100, 4, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 0));

            progressBarFill = new View(context);
            progressBarFill.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(2), Color.WHITE));
            progressBarContainer.addView(progressBarFill, LayoutHelper.createFrame(0, 4, Gravity.LEFT | Gravity.CENTER_VERTICAL));
        }

        public void showBrightness(int percent) {
            animate().cancel();
            setAlpha(1.0f);
            setVisibility(View.VISIBLE);

            iconView.setImageResource(percent < 40 ? R.drawable.msg_brightness_low : R.drawable.msg_brightness_high);
            primaryText.setText(percent + "%");
            secondaryText.setVisibility(View.GONE);
            progressBarContainer.setVisibility(View.VISIBLE);

            int width = Math.max(0, Math.min(AndroidUtilities.dp(100), (int) (AndroidUtilities.dp(100) * (percent / 100.0f))));
            progressBarFill.getLayoutParams().width = width;
            progressBarFill.requestLayout();
        }

        public void showVolume(int percent, boolean isMute) {
            animate().cancel();
            setAlpha(1.0f);
            setVisibility(View.VISIBLE);

            iconView.setImageResource(isMute ? R.drawable.volume_off : R.drawable.volume_on);
            primaryText.setText(percent + "%");
            secondaryText.setVisibility(View.GONE);
            progressBarContainer.setVisibility(View.VISIBLE);

            int width = Math.max(0, Math.min(AndroidUtilities.dp(100), (int) (AndroidUtilities.dp(100) * (percent / 100.0f))));
            progressBarFill.getLayoutParams().width = width;
            progressBarFill.requestLayout();
        }

        public void showSeek(String diffText, String timeText, boolean forward) {
            animate().cancel();
            setAlpha(1.0f);
            setVisibility(View.VISIBLE);

            iconView.setImageResource(R.drawable.forwardvideo);
            iconView.setScaleX(forward ? 1.0f : -1.0f);
            primaryText.setText(diffText);
            secondaryText.setText(timeText);
            secondaryText.setVisibility(View.VISIBLE);
            progressBarContainer.setVisibility(View.GONE);
        }

        public void dismiss() {
            animate().cancel();
            animate()
                    .alpha(0.0f)
                    .setDuration(250)
                    .setStartDelay(500)
                    .withEndAction(() -> setVisibility(View.GONE))
                    .start();
        }
    }
}
