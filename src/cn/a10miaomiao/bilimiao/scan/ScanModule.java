package cn.a10miaomiao.bilimiao.scan;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 哔哩喵扫码 1.0：注入哔哩喵（com.a10miaomiao.bilimiao）。
 *
 * 3.0 → 3.1 的变化：
 *  - 底栏右侧图标位（搜索右边），点开模块自带的相机扫码页
 *  - 扫码页内置「相册识别」，不用长按
 *  - 扫到登录二维码可直接确认登录
 */
public class ScanModule implements IXposedHookLoadPackage {

    private static final String TAG = "ScanMod31: ";

    private static final String HOST_PKG = "com.a10miaomiao.bilimiao";
    private static final String MODULE_PKG = "cn.a10miaomiao.bilimiao.scan";
    private static final String SCANNER_PKG = "cn.a10miaomiao.bilimiao.scanner";
    private static final String SCANNER_ACT = "cn.a10miaomiao.bilimiao.scanner.QrScannerActivity";
    private static final String SCAN_ACTION = "bilimiao.plugin.ACTION_SCAN_QRCODE";

    private static final String EXTRA_RESULT = "extra_qrcode_text_result";
    private static final String EXTRA_THEME = "extra_theme_color";

    private static final int REQ_SCAN = 0x5CAC;
    private static final int REQ_GALLERY = 0x5CAD;
    private static final int REQ_HOST_SCAN = 0x5CAE;

    private static final String BTN_TAG = "bilimiao_scan_btn_31";
    private static final int ACCENT = 0xFFFB7299;

    private static final String PREF_NAME = "bilimiao_scan_mod";
    private static final String PREF_HINTED = "hinted_gallery";

    private static final Pattern BV_PATTERN = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final Pattern AV_PATTERN = Pattern.compile("(?:^|[^0-9A-Za-z])av(\\d{3,})",
            Pattern.CASE_INSENSITIVE);

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || !HOST_PKG.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + "loaded in " + lpparam.packageName);

        try {
            XposedBridge.hookAllMethods(Instrumentation.class, "callActivityOnResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1) return;
                    Object arg = param.args[0];
                    if (!(arg instanceof Activity)) return;
                    try {
                        attachButton((Activity) arg);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "attach: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook resume: " + t);
        }

        // 哔哩喵自己的扫码入口（搜索框旁边的相机）——直接挂它的入口函数，插件没装就走自带扫码页
        try {
            Class<?> scannerClass = Class.forName(
                    "com.a10miaomiao.bilimiao.comm.scanner.BilimiaoScanner",
                    false, lpparam.classLoader);
            XposedBridge.hookAllMethods(scannerClass, "openScanner", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1) return;
                    Object arg = param.args[0];
                    if (!(arg instanceof Activity)) return;
                    Activity activity = (Activity) arg;
                    if (isPluginInstalled(activity)) return;   // 装了原版插件就用原版
                    try {
                        if (startBuiltin(activity, REQ_HOST_SCAN)) {
                            param.setResult(Boolean.TRUE);      // 告诉宿主「已打开」，它会收起抽屉
                            XposedBridge.log(TAG + "host scanner -> builtin");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "host scanner: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + "hook BilimiaoScanner.openScanner ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook BilimiaoScanner failed: " + t);
        }

        // 最底层兜底：所有 Activity 启动都会经过 Instrumentation.execStartActivity
        try {
            XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null) return;
                        int index = -1;
                        for (int i = 0; i < param.args.length; i++) {
                            if (param.args[i] instanceof Intent) {
                                index = i;
                                break;
                            }
                        }
                        if (index < 0) return;
                        Intent intent = (Intent) param.args[index];
                        if (!SCAN_ACTION.equals(intent.getAction())) return;
                        Context ctx = null;
                        for (Object a : param.args) {
                            if (a instanceof Context) {
                                ctx = (Context) a;
                                break;
                            }
                        }
                        if (ctx == null || isPluginInstalled(ctx)) return;
                        Intent builtin = new Intent();
                        builtin.setClassName(MODULE_PKG, MODULE_PKG + ".ScannerActivity");
                        builtin.putExtra(EXTRA_THEME, ACCENT);
                        param.args[index] = builtin;
                        XposedBridge.log(TAG + "execStartActivity -> builtin scanner");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "execStartActivity: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook execStartActivity failed: " + t);
        }

        // androidx 的 ComponentActivity 会重写启动方法，也要挂一份（有些版本走这里）
        try {
            Class<?> componentActivity = Class.forName(
                    "androidx.activity.ComponentActivity", false, null);
            XposedBridge.hookAllMethods(componentActivity, "startActivityForResult", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    rewriteScanIntent(param);
                }
            });
        } catch (Throwable ignore) {
        }

        // 宿主（哔哩喵）自带的扫码入口只认原版扫码器插件；插件没装时把它兜底到模块自带扫码页
        XposedBridge.hookAllMethods(Activity.class, "startActivityForResult", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 2) return;
                Object arg = param.args[1];
                if (!(arg instanceof Intent)) return;
                Intent intent = (Intent) arg;
                if (!SCAN_ACTION.equals(intent.getAction())) return;
                Activity activity = (Activity) param.thisObject;
                if (activity == null || isPluginInstalled(activity)) return;
                try {
                    Intent builtin = new Intent();
                    builtin.setClassName(MODULE_PKG, MODULE_PKG + ".ScannerActivity");
                    builtin.putExtra(EXTRA_THEME, ACCENT);
                    param.args[1] = builtin;
                    XposedBridge.log(TAG + "plugin missing, use built-in scanner");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "fallback: " + t);
                }
            }
        });

        XposedBridge.hookAllMethods(Activity.class, "onActivityResult", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 3) return;
                Object code = param.args[0];
                if (!(code instanceof Integer)) return;
                int requestCode = (Integer) code;
                if (requestCode != REQ_SCAN && requestCode != REQ_GALLERY && requestCode != REQ_HOST_SCAN) return;

                Activity activity = (Activity) param.thisObject;
                int resultCode = param.args[1] instanceof Integer ? (Integer) param.args[1] : 0;
                Intent data = param.args[2] instanceof Intent ? (Intent) param.args[2] : null;

                try {
                    if (requestCode == REQ_SCAN || requestCode == REQ_HOST_SCAN) {
                        handleScanResult(activity, resultCode, data);
                    } else {
                        handleGalleryResult(activity, resultCode, data);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "result: " + t);
                }
            }
        });
    }

    /* ------------------------------------------------------------------ UI */

    private void attachButton(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.getWindow() == null) return;
        if (!isHomeLike(activity)) return;

        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        if (content.findViewWithTag(BTN_TAG) != null) return;

        final ScanButton btn = new ScanButton(activity, navTextColor(activity));
        btn.setShowBackground(false);
        btn.setTag(BTN_TAG);
        btn.setClickable(true);

        // 按底栏原生图标对齐：图标 20dp、「扫码」10sp 文字，和图标/文字两行分别水平
        btn.setIconSize(dp(activity, 20));
        btn.setIconOffset(0, 0);
        btn.setIconCenterFromBottom(dp(activity, 34));
        btn.setLabel("扫码", (int) (10 * activity.getResources().getDisplayMetrics().scaledDensity));
        btn.setLabelBaselineFromBottom(dp(activity, 4));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(activity, 28), dp(activity, 76));
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.leftMargin = dp(activity, 2);
        lp.rightMargin = 0;
        lp.bottomMargin = bottomInset(activity);

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    openScanner((Activity) v.getContext());
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "click: " + t);
                }
            }
        });

        btn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                try {
                    Activity activity = (Activity) v.getContext();
                    toast(activity, "从相册识别");
                    openGallery(activity);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "long click: " + t);
                }
                return true;
            }
        });

        content.addView(btn, lp);
        XposedBridge.log(TAG + "attached to " + activity.getClass().getName());

        // 首次提示：长按可以从相册识别（只提示一次）
        try {
            android.content.SharedPreferences sp =
                    activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            if (!sp.getBoolean(PREF_HINTED, false)) {
                sp.edit().putBoolean(PREF_HINTED, true).apply();
                toast(activity, "点一下扫码，长按可从相册识别");
            }
        } catch (Throwable ignore) {
        }
    }

    private void rewriteScanIntent(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length < 2) return;
        Object arg = param.args[1];
        if (!(arg instanceof Intent)) return;
        Intent intent = (Intent) arg;
        if (!SCAN_ACTION.equals(intent.getAction())) return;
        Object self = param.thisObject;
        if (!(self instanceof Activity)) return;
        Activity activity = (Activity) self;
        if (isPluginInstalled(activity)) return;
        try {
            Intent builtin = new Intent();
            builtin.setClassName(MODULE_PKG, MODULE_PKG + ".ScannerActivity");
            builtin.putExtra(EXTRA_THEME, ACCENT);
            param.args[1] = builtin;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "rewrite: " + t);
        }
    }

    private boolean startBuiltin(Activity activity, int requestCode) {
        Intent builtin = new Intent();
        builtin.setClassName(MODULE_PKG, MODULE_PKG + ".ScannerActivity");
        builtin.putExtra(EXTRA_THEME, ACCENT);
        return tryStart(activity, builtin, requestCode);
    }

    private boolean isPluginInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(SCANNER_PKG, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isHomeLike(Activity activity) {
        String cls = activity.getClass().getName().toLowerCase();
        try {
            Intent launcher = new Intent(Intent.ACTION_MAIN);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            launcher.setPackage(activity.getPackageName());
            ResolveInfo ri = activity.getPackageManager().resolveActivity(launcher, 0);
            if (ri != null && ri.activityInfo != null
                    && cls.equals(ri.activityInfo.name.toLowerCase())) {
                return true;
            }
        } catch (Throwable ignore) {
        }
        return cls.contains("main") || cls.contains("home") || cls.contains("index");
    }

    private int bottomInset(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                if (insets != null) return insets.getSystemWindowInsetBottom();
            }
            // 老版本兜底：导航栏高度
            int id = activity.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) return activity.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignore) {
        }
        return 0;
    }

    /** 底栏文字/图标颜色：跟着深浅色主题走 */
    private static int navTextColor(Context ctx) {
        try {
            int mode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            if (mode == Configuration.UI_MODE_NIGHT_YES) return 0xFFE6E6E6;
        } catch (Throwable ignore) {
        }
        return 0xFF2B2B2B;
    }

    private static int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /* ---------------------------------------------------------------- scan */

    private void openScanner(Activity activity) {
        // 1) 模块自带的相机扫码（不再依赖额外安装插件）
        if (startBuiltin(activity, REQ_SCAN)) return;

        // 2) 已装 10miaomiao 扫码器插件的话，回退用插件
        Intent explicit = new Intent();
        explicit.setClassName(SCANNER_PKG, SCANNER_ACT);
        explicit.addCategory(Intent.CATEGORY_DEFAULT);
        explicit.putExtra(EXTRA_THEME, ACCENT);
        if (tryStart(activity, explicit, REQ_SCAN)) return;

        Intent withPkg = new Intent(SCAN_ACTION);
        withPkg.addCategory(Intent.CATEGORY_DEFAULT);
        withPkg.setPackage(SCANNER_PKG);
        withPkg.putExtra(EXTRA_THEME, ACCENT);
        if (tryStart(activity, withPkg, REQ_SCAN)) return;

        Intent byAction = new Intent(SCAN_ACTION);
        byAction.addCategory(Intent.CATEGORY_DEFAULT);
        byAction.putExtra(EXTRA_THEME, ACCENT);
        if (tryStart(activity, byAction, REQ_SCAN)) return;

        Intent gms = new Intent();
        gms.setPackage("com.google.android.gms");
        gms.setAction("com.google.android.gms.mlkit.ACTION_SCAN_BARCODE");
        gms.putExtra("extra_supported_formats", 0);
        gms.putExtra("extra_allow_manual_input", false);
        gms.putExtra("extra_enable_auto_zoom", false);
        if (tryStart(activity, gms, REQ_SCAN)) return;

        toast(activity, "没找到扫码器：请安装 bilimiao 扫码器插件");
    }

    /* -------------------------------------------------------------- 相册 */

    private void openGallery(Activity activity) {
        Intent doc = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        doc.addCategory(Intent.CATEGORY_OPENABLE);
        doc.setType("image/*");
        doc.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (tryStart(activity, doc, REQ_GALLERY)) return;

        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (tryStart(activity, pick, REQ_GALLERY)) return;

        toast(activity, "打不开相册选择器");
    }

    private void handleGalleryResult(final Activity activity, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return;
        final Uri uri = data.getData();
        if (uri == null) {
            toast(activity, "没有拿到图片");
            return;
        }
        toast(activity, "正在识别图片…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String text = QrDecoder.decode(activity, uri);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (text == null || text.trim().isEmpty()) {
                            toast(activity, "图片里没识别到二维码");
                            return;
                        }
                        XposedBridge.log(TAG + "gallery scan: " + text);
                        copy(activity, text.trim());
                        route(activity, text.trim());
                    }
                });
            }
        }, "bilimiao-scan-gallery").start();
    }

    /* -------------------------------------------------------------- result */

    private void handleScanResult(final Activity activity, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return;
        String text = extract(data);
        if (text == null) return;
        final String raw = text.trim();
        if (raw.isEmpty()) return;

        XposedBridge.log(TAG + "scan: " + raw);
        copy(activity, raw);
        route(activity, raw);
    }

    private String extract(Intent data) {
        if (data == null) return null;
        String s = firstNonEmpty(
                data.getStringExtra(EXTRA_RESULT),
                data.getStringExtra("SCAN_RESULT"),
                data.getStringExtra("scan_result"),
                data.getStringExtra("result"),
                data.getDataString());
        if (s != null) return s;
        try {
            Bundle b = data.getExtras();
            if (b != null) {
                for (String k : b.keySet()) {
                    Object v = b.get(k);
                    if (v instanceof String && ((String) v).trim().length() > 0) return (String) v;
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private void route(final Activity activity, final String text) {
        // 登录二维码：网页登录 / 哔哩喵自己的登录页，直接用当前账号确认
        if (LoginConfirm.isLoginQr(text)) {
            LoginConfirm.confirm(activity, text);
            return;
        }
        if (text.startsWith("bilimiao://") || text.startsWith("bilibili://")) {
            openUrl(activity, text);
            return;
        }
        Matcher bv = BV_PATTERN.matcher(text);
        if (bv.find()) {
            openUrl(activity, "bilimiao://video/" + bv.group());
            return;
        }
        Matcher av = AV_PATTERN.matcher(text);
        if (av.find()) {
            openUrl(activity, "bilimiao://video/" + av.group(1));
            return;
        }
        if (text.startsWith("http://") || text.startsWith("https://")) {
            if (text.contains("b23.tv") || text.contains("bili2233.cn")) {
                toast(activity, "正在解析短链…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String resolved = resolveShortLink(text);
                        MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                if (resolved != null) {
                                    openUrl(activity, resolved);
                                } else {
                                    openUrl(activity, text);
                                }
                            }
                        });
                    }
                }, "bilimiao-scan-resolve").start();
                return;
            }
            openUrl(activity, text);
            return;
        }
        toast(activity, "不是链接，已复制到剪贴板：" + shorten(text));
    }

    private String resolveShortLink(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            conn.getResponseCode();
            String finalUrl = conn.getURL() != null ? conn.getURL().toString() : null;
            String fromUrl = pick(finalUrl);
            if (fromUrl != null) return fromUrl;
            return pick(conn.getHeaderField("Location"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + "resolve: " + t);
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private String pick(String s) {
        if (s == null) return null;
        Matcher bv = BV_PATTERN.matcher(s);
        if (bv.find()) return "bilimiao://video/" + bv.group();
        Matcher av = AV_PATTERN.matcher(s);
        if (av.find()) return "bilimiao://video/" + av.group(1);
        return null;
    }

    private boolean tryStart(Activity activity, Intent intent, int requestCode) {
        try {
            activity.startActivityForResult(intent, requestCode);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "start failed: " + t);
            return false;
        }
    }

    private void openUrl(Activity activity, String url) {
        Intent scoped = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        scoped.setPackage(HOST_PKG);
        try {
            activity.startActivity(scoped);
            return;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "open scoped: " + t);
        }
        Intent any = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        try {
            activity.startActivity(any);
            return;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "open any: " + t);
        }
        toast(activity, "打不开：" + url + "，已复制");
    }

    private void copy(Context ctx, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("scan", text));
        } catch (Throwable t) {
            XposedBridge.log(TAG + "clip: " + t);
        }
    }

    private void toast(Context ctx, String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "toast: " + t);
        }
    }

    private static String firstNonEmpty(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && v.trim().length() > 0) return v;
        }
        return null;
    }

    private String shorten(String s) {
        String t = s.replace('\n', ' ');
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }
}
