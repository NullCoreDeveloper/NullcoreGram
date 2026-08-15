package tw.nekomimi.nekogram.helpers;

import android.app.Application;

import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;

import xyz.nextalone.nagram.NkmrConfig;

public class AnalyticsHelper {
    public static String DSN = "";
    public static boolean loaded = false;

    public static void start(Application application) {
        // Telemetry removed for NullcoreGram
        loaded = true;
    }

    public static void captureException(Throwable e) {
        // Telemetry removed for NullcoreGram
    }

    public static boolean getSentryStatus(Application application) {
        return false;
    }
}
