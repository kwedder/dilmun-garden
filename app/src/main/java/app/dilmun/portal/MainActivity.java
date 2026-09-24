package app.dilmun.portal;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * One screen: a WebView showing the app's pages from the APK's assets, with
 * the bridge to the engine. The app declares no internet permission, so
 * nothing it holds can leave the device.
 */
public final class MainActivity extends Activity {
    private static final int PICK_TREE = 42;
    private static final int PICK_MODEL = 43;
    private static final int BG = 0xFF080C11;

    private WebView web;
    private Bridge bridge;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        web = new WebView(this);
        web.setBackgroundColor(BG);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 30) {
            // draw edge to edge and keep the page clear of the system bars and the keyboard
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                    v.setPadding(i.left, i.top, i.right, i.bottom);
                    return insets;
                }
            });
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !request.getUrl().toString().startsWith("file:///android_asset/");
            }
        });

        bridge = new Bridge(this, web);
        web.addJavascriptInterface(bridge, "Dilmun");
        web.loadUrl("file:///android_asset/index.html");
    }

    void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_TREE);
    }

    void pickModel() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_MODEL);
    }

    /**
     * Keep the screen on while a bulk run works through its queue: work stops
     * when the phone sleeps, until runs move to a foreground service.
     */
    void keepAwake(final boolean on) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }

    void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            // no browser; nothing to do
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == PICK_TREE) {
            Uri tree = data.getData();
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            bridge.onTreePicked(tree);
        } else if (requestCode == PICK_MODEL) {
            bridge.onModelPicked(data.getData());
        }
    }

    @Override protected void onDestroy() {
        if (bridge != null) bridge.close();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        // let the page close a sheet or leave a tab first
        web.evaluateJavascript("window.dilmunBack ? String(window.dilmunBack()) : 'false'", new ValueCallback<String>() {
            @Override public void onReceiveValue(String handled) {
                if (!"\"true\"".equals(handled)) MainActivity.super.onBackPressed();
            }
        });
    }
}
