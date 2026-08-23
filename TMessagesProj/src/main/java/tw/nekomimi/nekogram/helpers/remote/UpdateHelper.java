package tw.nekomimi.nekogram.helpers.remote;

import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateHelper extends BaseRemoteHelper {
    private static final class InstanceHolder {
        private static final UpdateHelper instance = new UpdateHelper();
    }

    public static UpdateHelper getInstance() {
        return InstanceHolder.instance;
    }

    private boolean updateAlways = false;

    @Override
    protected void onError(String text, Delegate delegate) {
        if (delegate != null) {
            delegate.onTLResponse(null, text);
        }
    }

    @Override
    protected String getTag() {
        return "update"; // Not used directly in this implementation
    }

    private int compareVersions(String v1, String v2) {
        try {
            String v1Sanitized = v1.replaceAll("[^0-9.]", "");
            String v2Sanitized = v2.replaceAll("[^0-9.]", "");
            String[] parts1 = v1Sanitized.split("\\.");
            String[] parts2 = v2Sanitized.split("\\.");
            int length = Math.max(parts1.length, parts2.length);
            for (int i = 0; i < length; i++) {
                int p1 = i < parts1.length && !parts1[i].isEmpty() ? Integer.parseInt(parts1[i]) : 0;
                int p2 = i < parts2.length && !parts2[i].isEmpty() ? Integer.parseInt(parts2[i]) : 0;
                if (p1 != p2) {
                    return p1 < p2 ? -1 : 1;
                }
            }
            return 0;
        } catch (Exception e) {
            return v1.compareTo(v2);
        }
    }

    public void checkNewVersionAvailable(Delegate delegate) {
        checkNewVersionAvailable(delegate, false);
    }

    public void checkNewVersionAvailable(Delegate delegate, boolean updateAlways_) {
        updateAlways = updateAlways_;
        
        new Thread(() -> {
            try {
                URL url = new URL("https://api.github.com/repos/NullCoreDeveloper/NullcoreGram/releases/latest");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json");

                if (connection.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder result = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        result.append(line);
                    }
                    reader.close();

                    JSONObject releaseJson = new JSONObject(result.toString());
                    String tagName = releaseJson.getString("tag_name");
                    String version = tagName.startsWith("v") ? tagName.substring(1) : tagName;
                    String body = releaseJson.optString("body", "");

                    // Check if the version is newer than current
                    if (updateAlways || compareVersions(version, BuildVars.BUILD_VERSION_STRING) > 0) {
                        String downloadUrl = releaseJson.getString("html_url"); // Fallback to release page
                        JSONArray assets = releaseJson.optJSONArray("assets");
                        if (assets != null) {
                            String preferredAbi = "arm64-v8a"; // Default fallback
                            for (String abi : Build.SUPPORTED_ABIS) {
                                if (abi.equals("arm64-v8a") || abi.equals("armeabi-v7a") || abi.equals("x86") || abi.equals("x86_64")) {
                                    preferredAbi = abi;
                                    break;
                                }
                            }

                            for (int i = 0; i < assets.length(); i++) {
                                JSONObject asset = assets.getJSONObject(i);
                                String assetName = asset.getString("name");
                                if (assetName.endsWith(".apk") && assetName.contains(preferredAbi)) {
                                    downloadUrl = asset.getString("browser_download_url");
                                    break;
                                }
                            }
                        }

                        TLRPC.TL_help_appUpdate update = new TLRPC.TL_help_appUpdate();
                        update.version = version;
                        update.text = body;
                        update.url = downloadUrl;
                        update.can_not_skip = false;
                        update.flags |= 4; // URL flag

                        if (delegate != null) {
                            delegate.onTLResponse(update, null);
                        }
                    } else {
                        if (delegate != null) {
                            delegate.onTLResponse(null, null);
                        }
                    }
                } else {
                    if (delegate != null) {
                        delegate.onTLResponse(null, "HTTP Error: " + connection.getResponseCode());
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
                if (delegate != null) {
                    delegate.onTLResponse(null, e.toString());
                }
            }
        }).start();
    }
}
