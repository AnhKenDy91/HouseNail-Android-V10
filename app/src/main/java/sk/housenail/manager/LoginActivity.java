package sk.housenail.manager;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public class LoginActivity extends Activity {
    private WebView webView;
    private ProgressBar progress;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean finished = false;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (finished || isFinishing()) return;
            new Thread(() -> {
                try {
                    new ApiClient().verifyOwnerSession();
                    runOnUiThread(() -> {
                        if (finished) return;
                        finished = true;
                        setResult(RESULT_OK);
                        finish();
                    });
                } catch (Exception ignored) {
                    handler.postDelayed(this, 3000);
                }
            }).start();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(254, 247, 244));

        TextView info = new TextView(this);
        info.setText("Đăng nhập bằng tài khoản HouseNail vừa tạo trên website. Không cần mã đăng nhập Cloudflare Access.");
        info.setTextSize(13f);
        info.setTextColor(Color.rgb(92,72,65));
        info.setPadding(dp(18), dp(16), dp(18), dp(12));
        info.setGravity(Gravity.CENTER);
        root.addView(info);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(ProgressBar.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView footer = new TextView(this);
        footer.setText("Phiên HouseNail được xác minh trên máy chủ. Ứng dụng sẽ tự mở trang quản lý sau khi đăng nhập thành công.");
        footer.setTextSize(12f);
        footer.setTextColor(Color.rgb(120,104,96));
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(18), dp(10), dp(18), dp(16));
        root.addView(footer);

        setContentView(root);
        configureWebView();
        webView.loadUrl("https://housenail.sk/login");
        handler.postDelayed(poll, 1200);
    }

    private void configureWebView() {
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, false);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request.getUrl() == null) return true;
                String scheme = request.getUrl().getScheme();
                String host = request.getUrl().getHost();
                if ("about".equalsIgnoreCase(scheme)) return false;
                return !("https".equalsIgnoreCase(scheme) && "housenail.sk".equalsIgnoreCase(host));
            }

            @Override public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                handler.removeCallbacks(poll);
                handler.postDelayed(poll, 400);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? ProgressBar.GONE : ProgressBar.VISIBLE);
            }
        });
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
