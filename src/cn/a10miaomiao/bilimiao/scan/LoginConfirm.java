package cn.a10miaomiao.bilimiao.scan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫码登录：扫到「登录二维码」时直接在哔哩喵里确认，不用再开官方 B 站 App。
 *
 * 支持两种码：
 *  1) 网页/PC 登录二维码 —— 带 qrcode_key，POST /x/passport-login/web/qrcode/confirm
 *  2) 哔哩喵自己的登录页二维码（TV 登录）—— 带 auth_code，
 *     POST /x/passport-tv-login/h5/qrcode/confirm（按 B 站 App 的 appkey 规则签名）
 *
 * 登录态取自哔哩喵自己的 CookieProvider，所以用的是当前登录的账号。
 */
public class LoginConfirm {

    private static final String TAG = "ScanMod31: ";

    private static final String APP_KEY = "27eb53fc9058f8c3";
    private static final String APP_SECRET = "c2ed53a74eeefe3cf99fbd01d8c9c375";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 是不是登录二维码 */
    public static boolean isLoginQr(String text) {
        if (text == null) return false;
        if (!text.contains("passport.bilibili.com") && !text.contains("passport.bilibili")) return false;
        return text.contains("qrcode_key=") || text.contains("auth_code=");
    }

    public static void confirm(final Activity activity, final String text) {
        final String qrcodeKey = param(text, "qrcode_key");
        final String authCode = param(text, "auth_code");
        if (qrcodeKey == null && authCode == null) {
            toast(activity, "这不是登录二维码");
            return;
        }

        final String kind = qrcodeKey != null ? "网页登录" : "哔哩喵登录";
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    new AlertDialog.Builder(activity)
                            .setTitle("确认登录")
                            .setMessage("用当前账号确认这次「" + kind + "」？")
                            .setPositiveButton("确认登录", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    doConfirm(activity, qrcodeKey, authCode);
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                } catch (Throwable t) {
                    doConfirm(activity, qrcodeKey, authCode);
                }
            }
        });
    }

    private static void doConfirm(final Activity activity, final String qrcodeKey, final String authCode) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String[] holder = new String[1];
                try {
                    String cookie = cookie(activity);
                    if (cookie == null || cookie.isEmpty()) {
                        holder[0] = "没拿到登录态，请先在哔哩喵里登录";
                    } else if (qrcodeKey != null) {
                        holder[0] = confirmWeb(cookie, qrcodeKey);
                    } else {
                        holder[0] = confirmTv(cookie, authCode);
                    }
                } catch (Throwable t) {
                    holder[0] = "确认失败：" + t;
                }
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(activity, holder[0]);
                    }
                });
            }
        }, "bilimiao-scan-login").start();
    }

    /* ------------------------------------------------------------- 网页登录 */

    private static String confirmWeb(String cookie, String qrcodeKey) {
        String csrf = cookieValue(cookie, "bili_jct");
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("qrcode_key", qrcodeKey);
        form.put("csrf", csrf);
        String resp = post("https://passport.bilibili.com/x/passport-login/web/qrcode/confirm",
                encode(form), cookie);
        int code = code(resp);
        if (code == 0) return "已在网页登录成功";
        if (code == 86038) return "二维码已过期，请刷新后重试";
        if (code == 86090) return "二维码已被扫过，请在网页点一下确认";
        return "登录确认失败（code=" + code + "）";
    }

    /* ---------------------------------------------------------- TV/哔哩喵登录 */

    private static String confirmTv(String cookie, String authCode) {
        String csrf = cookieValue(cookie, "bili_jct");
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("auth_code", authCode);
        form.put("csrf", csrf);
        form.put("build", "7082000");
        form.put("statistics", "{\"appId\":6,\"platform\":3,\"version\":\"7.82.0\",\"abtest\":\"\"}");
        form.put("appkey", APP_KEY);

        String query = encode(form);
        String sign = md5(query + APP_SECRET);
        String body = query + "&sign=" + sign;

        String resp = post("https://passport.bilibili.com/x/passport-tv-login/h5/qrcode/confirm", body, cookie);
        int code = code(resp);
        if (code == 0) return "登录确认成功，请在哔哩喵登录页查看";
        if (code == 86038) return "二维码已过期，请刷新后重试";
        return "登录确认失败（code=" + code + "）";
    }

    /* ------------------------------------------------------------------ 工具 */

    private static String cookie(Context context) {
        // 优先用哔哩喵自己的 CookieProvider（Kotlin object，反射调用）
        try {
            Class<?> providers = Class.forName("com.a10miaomiao.bilimiao.comm.platform.PlatformProviders");
            Object instance = providers.getField("INSTANCE").get(null);
            Object provider = providers.getMethod("getCookieProvider").invoke(instance);
            if (provider != null) {
                Object result = provider.getClass()
                        .getMethod("getCookie", String.class)
                        .invoke(provider, "https://passport.bilibili.com/");
                if (result instanceof String && !((String) result).isEmpty()) {
                    return (String) result;
                }
            }
        } catch (Throwable t) {
            android.util.Log.d("bilimiao-scan", "cookieProvider: " + t);
        }
        // 兜底：WebView cookie（哔哩喵的 H5 登录走 WebView）
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                CookieManager cm = CookieManager.getInstance();
                String c = cm.getCookie("https://passport.bilibili.com");
                if (c != null && !c.isEmpty()) return c;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private static String cookieValue(String cookie, String key) {
        if (cookie == null) return "";
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith(key + "=")) return p.substring(key.length() + 1);
        }
        return "";
    }

    private static String post(String url, String body, String cookie) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", "https://www.bilibili.com/");
            if (cookie != null) conn.setRequestProperty("Cookie", cookie);

            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();

            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return "";
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String encode(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        try {
            List<String> keys = new ArrayList<String>(form.keySet());
            Collections.sort(keys);
            for (String k : keys) {
                if (sb.length() > 0) sb.append('&');
                sb.append(URLEncoder.encode(k, "UTF-8"))
                        .append('=')
                        .append(URLEncoder.encode(form.get(k) == null ? "" : form.get(k), "UTF-8"));
            }
        } catch (Throwable ignore) {
        }
        return sb.toString();
    }

    private static int code(String json) {
        if (json == null) return -1;
        int idx = json.indexOf("\"code\"");
        if (idx < 0) return -1;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return -1;
        StringBuilder sb = new StringBuilder();
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '-' || (c >= '0' && c <= '9')) sb.append(c);
            else if (sb.length() > 0) break;
        }
        try {
            return Integer.parseInt(sb.toString());
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String param(String text, String key) {
        try {
            String prefix = key + "=";
            int start = text.indexOf(prefix);
            if (start < 0) return null;
            start += prefix.length();
            int end = start;
            while (end < text.length()) {
                char c = text.charAt(end);
                if (c == '&' || c == '#' || c == ' ' || c == '\n' || c == '\r' || c == '"') break;
                end++;
            }
            return URLDecoder.decode(text.substring(start, end), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void toast(final Context ctx, final String msg) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
                } catch (Throwable ignore) {
                }
            }
        });
    }
}
