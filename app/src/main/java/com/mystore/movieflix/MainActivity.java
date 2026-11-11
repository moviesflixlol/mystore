package com.mystore.movieflix;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

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

        // WebViewAssetLoader serves local assets over https://appassets.androidplatform.net/
        // Registering both /assets/ and / ensures /header-logo-tight.png resolves properly
        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) {
                    return super.shouldInterceptRequest(view, request);
                }

                Uri uri = request.getUrl();
                String host = uri.getHost();
                String path = uri.getPath();

                // 1. Intercept store API requests (e.g. ws75.aptoide.com or /api-proxy)
                // This executes the request via native Java HttpURLConnection, completely
                // bypassing browser CORS and Cloudflare 403 blocks.
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
        });

        webView.setWebChromeClient(new WebChromeClient());

        // JavaScript interface for package installer, updates & native fetch
        WebAppInterface bridge = new WebAppInterface(this);
        webView.addJavascriptInterface(bridge, "Android");
        webView.addJavascriptInterface(bridge, "NuratixInstaller");

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimeType != null && mimeType.length() > 0 ? mimeType : "application/vnd.android.package-archive");
                    request.addRequestHeader("User-Agent", userAgent);
                    request.setTitle("Downloading Package");
                    request.setDescription("mystore Package Installer");
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "mystore_download.apk");

                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(request);
                        Toast.makeText(MainActivity.this, "Downloading package to /Download...", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse(url));
                    startActivity(intent);
                }
            }
        });

        // Load over secure origin via assetLoader
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
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
        public void installApk(String url, String name, String pkg, String splitsJson) {
            runOnUiThread(() -> {
                try {
                    Toast.makeText(mContext, "Opening " + (name != null ? name : "Package") + " with Package Installer...", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(Uri.parse(url), "application/vnd.android.package-archive");
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    mContext.startActivity(intent);
                } catch (Exception e) {
                    try {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        browserIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(browserIntent);
                    } catch (Exception ex) {
                        Toast.makeText(mContext, "Installer error: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public void install(String url, String splitsJson) {
            installApk(url, "App", "", splitsJson);
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
}
