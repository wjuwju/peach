package video.peach.mobile;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.webkit.CookieManager;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** 原版 Peach 页面由本机原版后端提供，安卓只负责窗口与系统交互。 */
public final class MainActivity extends Activity {
    private static final String ORIGIN = "http://127.0.0.1:8900";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService transfers = Executors.newSingleThreadExecutor();
    private WebView web;
    private FrameLayout root;
    private TextView status;
    private boolean loaded;
    private ValueCallback<Uri[]> chooser;
    private String downloadUrl, downloadCookie;
    private View fullscreen;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private final Runnable readiness = new Runnable() {
        @Override public void run() {
            if (isDestroyed()) return;
            if (CoreService.peachReady && !loaded) showPeach();
            else if (!loaded) { status.setText(CoreService.state); handler.postDelayed(this, 700); }
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        root = new FrameLayout(this); root.setFitsSystemWindows(true); setContentView(root);
        LinearLayout startup = new LinearLayout(this); startup.setOrientation(LinearLayout.VERTICAL); startup.setPadding(32, 48, 32, 32);
        TextView title = new TextView(this); title.setText("Peach"); title.setTextSize(30); startup.addView(title);
        status = new TextView(this); status.setTextSize(16); status.setPadding(0, 32, 0, 32); startup.addView(status);
        Button retry = new Button(this); retry.setText("启动 / 重试"); retry.setOnClickListener(v -> startCore()); startup.addView(retry);
        Button cloud = new Button(this); cloud.setText("内置 CloudDrive2"); cloud.setOnClickListener(v -> startActivity(new Intent(this, CloudDriveActivity.class))); startup.addView(cloud);
        Button logs = new Button(this); logs.setText("查看运行诊断"); logs.setOnClickListener(v -> diagnostics()); startup.addView(logs);
        root.addView(startup); startCore(); handler.post(readiness);
    }
    private void startCore() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        startForegroundService(new Intent(this, CoreService.class));
    }
    private static boolean local(Uri uri) { return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == 8900; }
    private void showPeach() {
        loaded = true; web = new WebView(this);
        WebSettings settings = web.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(true); settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); settings.setSupportMultipleWindows(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (local(request.getUrl())) return false;
                if (request.isForMainFrame() && request.hasGesture()) openExternal(request.getUrl());
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams parameters) {
                if (chooser != null) chooser.onReceiveValue(null); chooser = callback;
                try { startActivityForResult(parameters.createIntent(), 100); }
                catch (ActivityNotFoundException e) { chooser.onReceiveValue(null); chooser = null; return false; }
                return true;
            }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreen != null) { callback.onCustomViewHidden(); return; }
                fullscreen = view; fullscreenCallback = callback; web.setVisibility(View.GONE); root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            @Override public void onHideCustomView() { hideFullscreen(); }
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, android.os.Message result) {
                if (!gesture) return false;
                WebView popup = new WebView(MainActivity.this);
                popup.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView child, WebResourceRequest request) {
                        if (local(request.getUrl())) web.loadUrl(request.getUrl().toString()); else openExternal(request.getUrl());
                        popup.destroy(); return true;
                    }
                });
                ((WebView.WebViewTransport) result.obj).setWebView(popup); result.sendToTarget(); return true;
            }
        });
        web.setDownloadListener((url, userAgent, disposition, mime, length) -> {
            if (!local(Uri.parse(url))) { openExternal(Uri.parse(url)); return; }
            downloadUrl = url; downloadCookie = CookieManager.getInstance().getCookie(url);
            Intent document = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime)
                .putExtra(Intent.EXTRA_TITLE, new File(URLUtil.guessFileName(url, disposition, mime)).getName());
            try { startActivityForResult(document, 101); }
            catch (ActivityNotFoundException e) { Toast.makeText(this, "没有可用的文件保存位置", Toast.LENGTH_LONG).show(); }
        });
        String localToken = null;
        try (InputStream input = new FileInputStream(new File(RuntimeBundle.data(this), "secrets/auth-token"))) {
            String value = RuntimeBundle.readText(input).trim();
            if (value.matches("[A-Za-z0-9_-]{32,128}")) localToken = value;
        } catch (IOException ignored) { /* 原版登录页处理访问密码与缺失口令。 */ }
        LinearLayout shell = new LinearLayout(this); shell.setOrientation(LinearLayout.VERTICAL);
        Button cloud = new Button(this); cloud.setText("CloudDrive2"); cloud.setAllCaps(false);
        cloud.setContentDescription("管理内置 CloudDrive2 账号与媒体目录");
        cloud.setOnClickListener(v -> startActivity(new Intent(this, CloudDriveActivity.class)));
        shell.addView(cloud, new LinearLayout.LayoutParams(-1, (int) (40 * getResources().getDisplayMetrics().density)));
        shell.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        root.removeAllViews(); root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        if (localToken == null) web.loadUrl(ORIGIN + "/");
        else CookieManager.getInstance().setCookie(ORIGIN, "tok=" + localToken + "; Path=/; HttpOnly; SameSite=Strict", accepted -> {
            if (!isDestroyed()) web.loadUrl(ORIGIN + "/");
        });
    }
    private void openExternal(Uri uri) {
        if ("javascript".equals(uri.getScheme()) || "file".equals(uri.getScheme()) || "content".equals(uri.getScheme()) || "data".equals(uri.getScheme())) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "没有可打开此链接的应用", Toast.LENGTH_LONG).show(); }
    }
    private void hideFullscreen() {
        if (fullscreen == null) return;
        root.removeView(fullscreen); fullscreen = null; web.setVisibility(View.VISIBLE); fullscreenCallback.onCustomViewHidden(); fullscreenCallback = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (request == 100 && chooser != null) { chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, intent)); chooser = null; }
        if (request == 101 && result == RESULT_OK && intent != null && intent.getData() != null && downloadUrl != null) {
            Uri target = intent.getData(); String source = downloadUrl, cookies = downloadCookie;
            transfers.execute(() -> {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(source).openConnection(); connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10000); connection.setReadTimeout(30000);
                    if (cookies != null) connection.setRequestProperty("Cookie", cookies);
                    if (connection.getResponseCode() != 200) throw new IOException("导出请求失败：" + connection.getResponseCode());
                    try (InputStream input = connection.getInputStream(); OutputStream output = getContentResolver().openOutputStream(target, "w")) {
                        if (output == null) throw new IOException("无法打开保存位置");
                        byte[] buffer = new byte[65536]; int count; while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    }
                    handler.post(() -> Toast.makeText(getApplicationContext(), "已保存文件", Toast.LENGTH_LONG).show());
                } catch (Exception error) { handler.post(() -> Toast.makeText(getApplicationContext(), "文件未保存：" + error.getMessage(), Toast.LENGTH_LONG).show()); }
                finally { if (connection != null) connection.disconnect(); }
            });
        }
    }
    private void diagnostics() {
        String value;
        try (RandomAccessFile input = new RandomAccessFile(new File(RuntimeBundle.data(this), "android-runtime.log"), "r")) {
            input.seek(Math.max(0, input.length() - 18000)); byte[] buffer = new byte[(int) Math.min(18000, input.length())]; input.readFully(buffer);
            value = new String(buffer, StandardCharsets.UTF_8).replaceAll("(?i)Bearer\\s+[A-Za-z0-9._-]+", "Bearer [已隐藏]");
        } catch (IOException e) { value = CoreService.state; }
        TextView text = new TextView(this); text.setText(value); text.setTextIsSelectable(true); ScrollView scroll = new ScrollView(this); scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("本机运行诊断").setView(scroll).setPositiveButton("关闭", null).show();
    }
    @Override public void onBackPressed() { if (fullscreen != null) hideFullscreen(); else if (web != null && web.canGoBack()) web.goBack(); else moveTaskToBack(true); }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null); transfers.shutdown(); if (chooser != null) chooser.onReceiveValue(null);
        if (web != null) web.destroy(); super.onDestroy();
    }
}
