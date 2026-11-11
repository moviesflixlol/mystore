package com.mystore.movieflix;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.WebViewAssetLoader;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    public static final String CHANNEL_ID = "mystore_downloads";
    public static final String CHANNEL_NAME = "mystore Downloads";
    private static final int PERM_NOTIF_CODE = 101;

    private WebView webView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(3);
    private final Map<String, File> downloadedFiles = new ConcurrentHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        createNotificationChannel();
        checkAndRequestPermissions();

        webView = new WebView(this);
        swipeRefreshLayout = new SwipeRefreshLayout(this);
        swipeRefreshLayout.setColorSchemeColors(Color.parseColor("#e50914"));
        swipeRefreshLayout.setProgressBackgroundColorSchemeColor(Color.parseColor("#181818"));
        swipeRefreshLayout.addView(webView);
        setContentView(swipeRefreshLayout);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);

        // Allow cross-origin requests from WebView
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Modern Mobile User Agent without '; wv' to avoid anti-bot/401 restrictions
        String ua = settings.getUserAgentString();
        if (ua != null) {
            settings.setUserAgentString(ua.replace("; wv", ""));
        }

        // Pull to refresh listener
        swipeRefreshLayout.setOnRefreshListener(() -> {
            webView.evaluateJavascript("window.onPullToRefresh && window.onPullToRefresh();", null);
        });

        // Disable pull to refresh when user has scrolled down
        webView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            swipeRefreshLayout.setEnabled(webView.getScrollY() == 0);
        });

        // WebViewAssetLoader serves local assets over https://appassets.androidplatform.net/
        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        final WebAppInterface bridge = new WebAppInterface(this);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) {
                    return super.shouldInterceptRequest(view, request);
                }

                Uri uri = request.getUrl();
                String host = uri.getHost();
                String path = uri.getPath();

                // 1. Intercept store API requests to bypass CORS and Cloudflare 403 blocks
                if (host != null && (host.contains("aptoide.com") || host.contains("appassets.androidplatform.net") && path != null && (path.contains("api-proxy") || path.contains("proxy")))) {
                    WebResourceResponse proxied = proxyApiRequest(request);
                    if (proxied != null) {
                        return proxied;
                    }
                }

                // 2. Intercept local asset files
                WebResourceResponse assetResponse = assetLoader.shouldInterceptRequest(uri);
                if (assetResponse != null) {
                    return assetResponse;
                }

                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!bridge.isNetworkAvailable()) {
                    runOnUiThread(() -> webView.evaluateJavascript("window.showNoNetwork && window.showNoNetwork();", null));
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                runOnUiThread(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript("window.showNoNetwork && window.showNoNetwork();", null);
                    }
                });
            }

            @TargetApi(Build.VERSION_CODES.M)
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    runOnUiThread(() -> {
                        if (webView != null) {
                            webView.evaluateJavascript("window.showNoNetwork && window.showNoNetwork();", null);
                        }
                    });
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient());

        // JavaScript interfaces for package installer, updates, notifications & native fetch
        webView.addJavascriptInterface(bridge, "Android");
        webView.addJavascriptInterface(bridge, "NuratixInstaller");

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                bridge.startDownloadAndInstall(url, "Download", "", "", "[]");
            }
        });

        // Load over secure origin via assetLoader
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERM_NOTIF_CODE);
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows download progress and install prompts for mystore packages");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private WebResourceResponse proxyApiRequest(WebResourceRequest request) {
        try {
            Uri uri = request.getUrl();
            String targetUrl = uri.toString();
            if (targetUrl.contains("url=")) {
                String q = uri.getQueryParameter("url");
                if (q != null && !q.isEmpty()) {
                    targetUrl = q;
                }
            }

            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                Map<String, String> headers = new HashMap<>();
                headers.put("Access-Control-Allow-Origin", "*");
                headers.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD");
                headers.put("Access-Control-Allow-Headers", "*");
                return new WebResourceResponse("text/plain", "UTF-8", 200, "OK", headers, new ByteArrayInputStream("OK".getBytes()));
            }

            HttpURLConnection conn = (HttpURLConnection) new URL(targetUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(9000);
            conn.setReadTimeout(9000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");

            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();

            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD");
            headers.put("Access-Control-Allow-Headers", "*");
            headers.put("Content-Type", "application/json; charset=utf-8");

            return new WebResourceResponse("application/json", "UTF-8", code, conn.getResponseMessage() != null ? conn.getResponseMessage() : "OK", headers, in);
        } catch (Exception e) {
            return null;
        }
    }

    public class WebAppInterface {
        Context mContext;

        WebAppInterface(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void setRefreshing(boolean refreshing) {
            runOnUiThread(() -> {
                if (swipeRefreshLayout != null) {
                    swipeRefreshLayout.setRefreshing(refreshing);
                }
            });
        }

        @JavascriptInterface
        public void shareText(String title, String text, String url) {
            try {
                Intent sendIntent = new Intent(Intent.ACTION_SEND);
                sendIntent.putExtra(Intent.EXTRA_SUBJECT, title);
                sendIntent.putExtra(Intent.EXTRA_TEXT, text + " " + url);
                sendIntent.setType("text/plain");
                Intent shareIntent = Intent.createChooser(sendIntent, title != null ? title : "Share via");
                shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(shareIntent);
            } catch (Exception e) {
                Toast.makeText(mContext, "Share: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        @JavascriptInterface
        public void launchApp(String packageName) {
            if (packageName == null || packageName.isEmpty()) return;
            try {
                Intent launchIntent = mContext.getPackageManager().getLaunchIntentForPackage(packageName);
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(launchIntent);
                } else {
                    Toast.makeText(mContext, "Application cannot be opened directly", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(mContext, "Error launching app: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        @JavascriptInterface
        public void uninstallApp(String packageName) {
            if (packageName == null || packageName.isEmpty()) return;
            try {
                Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
                intent.setData(Uri.parse("package:" + packageName));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
            } catch (Exception e) {
                try {
                    Intent deleteIntent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + packageName));
                    deleteIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(deleteIntent);
                } catch (Exception ex) {
                    Toast.makeText(mContext, "Cannot uninstall: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        }

        @JavascriptInterface
        public boolean isNetworkAvailable() {
            try {
                ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm == null) return false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    android.net.Network net = cm.getActiveNetwork();
                    if (net == null) return false;
                    NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                    return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                           caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                           caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
                } else {
                    NetworkInfo ni = cm.getActiveNetworkInfo();
                    return ni != null && ni.isConnected();
                }
            } catch (Exception e) {
                return true;
            }
        }

        @JavascriptInterface
        public String nativeFetch(String urlString) {
            try {
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                conn.setRequestProperty("Accept", "application/json, text/plain, */*");

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                return sb.toString();
            } catch (Exception e) {
                return null;
            }
        }

        @JavascriptInterface
        public boolean isPackageInstalled(String packageName) {
            if (packageName == null || packageName.isEmpty()) return false;
            try {
                mContext.getPackageManager().getPackageInfo(packageName, 0);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public String getPackageVersionName(String packageName) {
            if (packageName == null || packageName.isEmpty()) return "";
            try {
                PackageInfo pInfo = mContext.getPackageManager().getPackageInfo(packageName, 0);
                return pInfo.versionName != null ? pInfo.versionName : "";
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public int getPackageVersionCode(String packageName) {
            if (packageName == null || packageName.isEmpty()) return -1;
            try {
                PackageInfo pInfo = mContext.getPackageManager().getPackageInfo(packageName, 0);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    return (int) pInfo.getLongVersionCode();
                } else {
                    return pInfo.versionCode;
                }
            } catch (Exception e) {
                return -1;
            }
        }

        @JavascriptInterface
        public void installDownloadedPackage(String pkgName) {
            File apkFile = downloadedFiles.get(pkgName);
            if (apkFile != null && apkFile.exists()) {
                installViaPackageInstaller(apkFile, "App", pkgName);
            } else {
                Toast.makeText(mContext, "Package not found on storage", Toast.LENGTH_SHORT).show();
            }
        }

        /**
         * Native In-App Downloader & Package Installer
         * Downloads package bytes directly, posts system notification with progress,
         * and triggers Android's Package Installer directly inside the app.
         */
        @JavascriptInterface
        public void startDownloadAndInstall(String downloadUrl, String appName, String pkgName, String iconUrl, String splitsJson) {
            if (downloadUrl == null || downloadUrl.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(mContext, "Download URL is invalid", Toast.LENGTH_SHORT).show());
                return;
            }

            // Check Unknown Sources install permission on Android 8+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!mContext.getPackageManager().canRequestPackageInstalls()) {
                    runOnUiThread(() -> {
                        Toast.makeText(mContext, "Allow mystore to install downloaded apps", Toast.LENGTH_LONG).show();
                        try {
                            Intent reqIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                            reqIntent.setData(Uri.parse("package:" + mContext.getPackageName()));
                            reqIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            mContext.startActivity(reqIntent);
                        } catch (Exception ex) {
                            // ignore
                        }
                    });
                }
            }

            final String safeName = (appName != null && !appName.isEmpty()) ? appName : "App";
            final String safePkg = (pkgName != null && !pkgName.isEmpty()) ? pkgName : ("pkg_" + System.currentTimeMillis());
            final int notifId = Math.abs(safePkg.hashCode());

            runOnUiThread(() -> Toast.makeText(mContext, "Downloading " + safeName + "...", Toast.LENGTH_SHORT).show());

            downloadExecutor.execute(() -> {
                NotificationManagerCompat nm = NotificationManagerCompat.from(mContext);
                Bitmap largeIcon = null;
                try {
                    largeIcon = BitmapFactory.decodeResource(mContext.getResources(), R.mipmap.ic_launcher);
                } catch (Exception ignored) {}

                NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle("Downloading " + safeName)
                        .setContentText("Starting download...")
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setProgress(100, 0, true)
                        .setPriority(NotificationCompat.PRIORITY_LOW);

                if (largeIcon != null) {
                    builder.setLargeIcon(largeIcon);
                }

                try {
                    nm.notify(notifId, builder.build());
                } catch (SecurityException ignored) {}

                File targetFile = null;
                try {
                    File downloadDir = new File(mContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "mystore_packages");
                    if (!downloadDir.exists()) {
                        downloadDir.mkdirs();
                    }

                    String fileName = safeName.replaceAll("[^a-zA-Z0-9_-]", "_") + ".apk";
                    targetFile = new File(downloadDir, fileName);

                    URL url = new URL(downloadUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                    conn.connect();

                    int responseCode = conn.getResponseCode();
                    if (responseCode >= 400) {
                        throw new Exception("HTTP " + responseCode);
                    }

                    int fileLength = conn.getContentLength();
                    InputStream input = conn.getInputStream();
                    FileOutputStream output = new FileOutputStream(targetFile);

                    byte[] data = new byte[8192];
                    long total = 0;
                    int count;
                    long lastUpdateTime = 0;

                    while ((count = input.read(data)) != -1) {
                        total += count;
                        output.write(data, 0, count);

                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastUpdateTime > 280) {
                            lastUpdateTime = currentTime;
                            int progress = (fileLength > 0) ? (int) (total * 100 / fileLength) : 0;
                            String text = progress + "% (" + formatBytes(total) + (fileLength > 0 ? " / " + formatBytes(fileLength) : "") + ")";

                            builder.setProgress(100, progress, fileLength <= 0);
                            builder.setContentText(text);

                            try {
                                nm.notify(notifId, builder.build());
                            } catch (SecurityException ignored) {}

                            final int finalProgress = progress;
                            runOnUiThread(() -> {
                                if (webView != null) {
                                    webView.evaluateJavascript("window.onDownloadProgress && window.onDownloadProgress('" + safePkg + "', " + finalProgress + ");", null);
                                }
                            });
                        }
                    }

                    output.flush();
                    output.close();
                    input.close();

                    // Finished downloading - store reference for in-app installer
                    downloadedFiles.put(safePkg, targetFile);

                    final File finalApk = targetFile;
                    runOnUiThread(() -> {
                        if (webView != null) {
                            webView.evaluateJavascript("window.onDownloadProgress && window.onDownloadProgress('" + safePkg + "', 100);", null);
                            webView.evaluateJavascript("window.onDownloadComplete && window.onDownloadComplete('" + safePkg + "', '" + safeName.replace("'", "\\'") + "');", null);
                        }
                        installViaPackageInstaller(finalApk, safeName, safePkg);
                    });

                    // Build completion notification with click-to-install PendingIntent
                    Intent installIntent = buildInstallIntent(targetFile);
                    PendingIntent pendingIntent = PendingIntent.getActivity(
                            mContext,
                            notifId,
                            installIntent,
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE) : PendingIntent.FLAG_UPDATE_CURRENT
                    );

                    NotificationCompat.Builder completeNotif = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentTitle(safeName + " Ready to Install")
                            .setContentText("Tap to launch Package Installer")
                            .setAutoCancel(true)
                            .setOngoing(false)
                            .setContentIntent(pendingIntent)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT);

                    if (largeIcon != null) {
                        completeNotif.setLargeIcon(largeIcon);
                    }

                    try {
                        nm.notify(notifId, completeNotif.build());
                    } catch (SecurityException ignored) {}

                } catch (Exception e) {
                    final String err = e.getMessage() != null ? e.getMessage() : "Network error";
                    runOnUiThread(() -> {
                        Toast.makeText(mContext, "Download failed: " + err, Toast.LENGTH_LONG).show();
                        if (webView != null) {
                            webView.evaluateJavascript("window.onDownloadFailed && window.onDownloadFailed('" + safePkg + "', '" + err.replace("'", "\\'") + "');", null);
                        }
                    });

                    NotificationCompat.Builder failNotif = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentTitle("Download Failed: " + safeName)
                            .setContentText(err)
                            .setAutoCancel(true)
                            .setOngoing(false)
                            .setPriority(NotificationCompat.PRIORITY_LOW);

                    try {
                        nm.notify(notifId, failNotif.build());
                    } catch (SecurityException ignored) {}
                }
            });
        }

        @JavascriptInterface
        public void installApk(String url, String name, String pkg, String splitsJson) {
            startDownloadAndInstall(url, name, pkg, "", splitsJson);
        }

        @JavascriptInterface
        public void install(String url, String splitsJson) {
            startDownloadAndInstall(url, "App", "", "", splitsJson);
        }

        public void installViaPackageInstaller(File apkFile, String appName, String pkgName) {
            try {
                PackageInstaller installer = mContext.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                if (pkgName != null && !pkgName.isEmpty()) {
                    params.setAppPackageName(pkgName);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                }
                int sessionId = installer.createSession(params);
                PackageInstaller.Session session = installer.openSession(sessionId);

                OutputStream out = session.openWrite("package", 0, apkFile.length());
                FileInputStream in = new FileInputStream(apkFile);
                byte[] buffer = new byte[65536];
                int c;
                while ((c = in.read(buffer)) != -1) {
                    out.write(buffer, 0, c);
                }
                session.fsync(out);
                in.close();
                out.close();

                Intent callbackIntent = new Intent(mContext, MainActivity.class);
                PendingIntent pendingIntent = PendingIntent.getActivity(
                        mContext,
                        sessionId,
                        callbackIntent,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE) : PendingIntent.FLAG_UPDATE_CURRENT
                );

                session.commit(pendingIntent.getIntentSender());
                session.close();
            } catch (Exception ex) {
                // Fallback to FileProvider
                installApkFile(apkFile, appName);
            }
        }

        private void installApkFile(File apkFile, String appName) {
            try {
                Intent intent = buildInstallIntent(apkFile);
                mContext.startActivity(intent);
            } catch (Exception ex) {
                Toast.makeText(mContext, "Package Installer: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        private Intent buildInstallIntent(File apkFile) {
            Uri contentUri = FileProvider.getUriForFile(
                    mContext,
                    mContext.getPackageName() + ".provider",
                    apkFile
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        }

        private String formatBytes(long bytes) {
            if (bytes <= 0) return "0 MB";
            double mb = bytes / (1024.0 * 1024.0);
            return String.format("%.1f MB", mb);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        downloadExecutor.shutdownNow();
    }
}
