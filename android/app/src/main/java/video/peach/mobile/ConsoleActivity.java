package video.peach.mobile;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.*;
import android.widget.*;

/** 官方管理页独立显示，不向网页暴露 JavaScript 原生桥。 */
public final class ConsoleActivity extends Activity {
    private WebView web;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        Button back = new Button(this); back.setText("返回 Peach · 登录后在此添加网盘"); back.setOnClickListener(v -> finish()); root.addView(back);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true); web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false); web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == 19798) return false;
                if (request.isForMainFrame() && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (android.content.ActivityNotFoundException ignored) { Toast.makeText(ConsoleActivity.this, "未找到浏览器", Toast.LENGTH_SHORT).show(); }
                }
                return true;
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        web.loadUrl("http://127.0.0.1:19798/");
    }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() { web.destroy(); super.onDestroy(); }
}
