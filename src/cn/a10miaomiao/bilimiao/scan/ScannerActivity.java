package cn.a10miaomiao.bilimiao.scan;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Camera;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 模块自带的相机扫码页（跑在模块自己的进程里，所以能拿到 CAMERA 权限）。
 * 不再依赖 bilimiao_scanner 插件。
 */
public class ScannerActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    public static final String EXTRA_RESULT = "extra_qrcode_text_result";

    private static final int REQ_PERMISSION = 1;
    private static final int REQ_PICK = 2;

    private SurfaceView surfaceView;
    private OverlayView overlayView;
    private TextView tipView;

    private Camera camera;
    private boolean decoding = false;
    private boolean torchOn = false;
    private boolean finished = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ToneGenerator tone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        surfaceView.getHolder().addCallback(this);

        overlayView = new OverlayView(this);
        root.addView(overlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tipView = new TextView(this);
        tipView.setText("把二维码放进框里");
        tipView.setTextColor(0xCCFFFFFF);
        tipView.setTextSize(14);
        FrameLayout.LayoutParams tipLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        tipLp.bottomMargin = dp(150);
        root.addView(tipView, tipLp);

        root.addView(button("✕", true), buttonLp(Gravity.START | Gravity.TOP, 16, 0));
        root.addView(button(torchOn ? "手电开" : "手电", false), buttonLp(Gravity.END | Gravity.TOP, 16, 0));
        root.addView(button("相册识别", false), buttonLp(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 16, 0));

        setContentView(root);
        setResult(RESULT_CANCELED);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERMISSION);
        }
    }

    private FrameLayout.LayoutParams buttonLp(int gravity, int marginDp, int topDp) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        lp.setMargins(dp(marginDp), dp(24), dp(marginDp), dp(40));
        return lp;
    }

    private TextView button(String text, boolean isClose) {
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(16), dp(10), dp(16), dp(10));
        tv.setBackgroundColor(0x33FFFFFF);
        if (isClose) {
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        } else if ("相册识别".equals(text)) {
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickImage();
                }
            });
        } else {
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleTorch();
                    tv.setText(torchOn ? "手电开" : "手电");
                }
            });
        }
        return tv;
    }

    /* ---------------------------------------------------------------- 相机 */

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSION) {
            if (grantResults != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                Toast.makeText(this, "没有相机权限，可以用下面的「相册识别」", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openCamera() {
        if (camera != null) return;
        // 预览 Surface 还没就绪时先不开，等 surfaceCreated 回调
        if (surfaceView == null || !surfaceView.getHolder().getSurface().isValid()) return;
        try {
            camera = Camera.open();
            Camera.Parameters p = camera.getParameters();
            p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            List<Camera.Size> sizes = p.getSupportedPreviewSizes();
            Camera.Size best = null;
            for (Camera.Size s : sizes) {
                int px = Math.max(s.width, s.height);
                if (best == null || (Math.abs(px - 1280) < Math.abs(Math.max(best.width, best.height) - 1280))) {
                    best = s;
                }
            }
            if (best != null) p.setPreviewSize(best.width, best.height);
            camera.setParameters(p);
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(surfaceView.getHolder());
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (Throwable t) {
            Toast.makeText(this, "相机打不开：" + t, Toast.LENGTH_LONG).show();
        }
    }

    private void toggleTorch() {
        if (camera == null) return;
        try {
            Camera.Parameters p = camera.getParameters();
            torchOn = !torchOn;
            p.setFlashMode(torchOn ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(p);
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        openCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
            } catch (Throwable ignore) {
            }
            camera = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        releaseCamera();
        super.onPause();
    }

    /* ---------------------------------------------------------------- 解码 */

    @Override
    public void onPreviewFrame(final byte[] data, Camera cam) {
        if (decoding || finished || data == null) return;
        decoding = true;
        final int width = cam.getParameters().getPreviewSize().width;
        final int height = cam.getParameters().getPreviewSize().height;
        final byte[] frame = data.clone();

        new Thread(new Runnable() {
            @Override
            public void run() {
                String text = null;
                try {
                    PlanarYUVLuminanceSource source =
                            new PlanarYUVLuminanceSource(frame, width, height, 0, 0, width, height, false);
                    BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

                    Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
                    hints.put(DecodeHintType.POSSIBLE_FORMATS, formats());
                    hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");

                    MultiFormatReader reader = new MultiFormatReader();
                    reader.setHints(hints);
                    Result result = reader.decodeWithState(bitmap);
                    if (result != null) text = result.getText();
                } catch (Throwable ignore) {
                } finally {
                    decoding = false;
                }
                if (text != null) finishWith(text);
            }
        }, "scan-decode").start();
    }

    private List<BarcodeFormat> formats() {
        List<BarcodeFormat> list = new ArrayList<BarcodeFormat>();
        list.add(BarcodeFormat.QR_CODE);
        return list;
    }

    /**
     * BV/av 统一转成哔哩喵的 deeplink 再返回。
     * 这样无论走模块自己的入口，还是走哔哩喵搜索框那个入口（它只认 URL），都能直接落地到视频页。
     */
    private static String normalize(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.startsWith("bilimiao://") || t.startsWith("bilibili://")) return t;
        java.util.regex.Matcher bv = java.util.regex.Pattern.compile("BV[0-9A-Za-z]{10}").matcher(t);
        if (bv.find()) return "bilimiao://video/" + bv.group();
        java.util.regex.Matcher av = java.util.regex.Pattern.compile("(?:^|[^0-9A-Za-z])av(\\d{3,})",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(t);
        if (av.find()) return "bilimiao://video/" + av.group(1);
        return t;
    }

    private void finishWith(final String text) {
        if (finished) return;
        finished = true;
        main.post(new Runnable() {
            @Override
            public void run() {
                beep();
                Intent data = new Intent();
                data.putExtra(EXTRA_RESULT, normalize(text));
                setResult(RESULT_OK, data);
                finish();
            }
        });
    }

    private void beep() {
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
        } catch (Throwable ignore) {
        }
    }

    /* ---------------------------------------------------------------- 相册 */

    private void pickImage() {
        Intent doc = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        doc.addCategory(Intent.CATEGORY_OPENABLE);
        doc.setType("image/*");
        try {
            startActivityForResult(doc, REQ_PICK);
            return;
        } catch (Throwable ignore) {
        }
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.setType("image/*");
        try {
            startActivityForResult(pick, REQ_PICK);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开相册", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        Toast.makeText(this, "正在识别…", Toast.LENGTH_SHORT).show();
        final Uri finalUri = uri;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String text = QrDecoder.decode(ScannerActivity.this, finalUri);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (text == null) {
                            Toast.makeText(ScannerActivity.this, "图里没识别到二维码", Toast.LENGTH_SHORT).show();
                        } else {
                            finishWith(text);
                        }
                    }
                });
            }
        }, "scan-gallery").start();
    }

    @Override
    protected void onDestroy() {
        releaseCamera();
        if (tone != null) {
            try {
                tone.release();
            } catch (Throwable ignore) {
            }
            tone = null;
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /* ------------------------------------------------------------------ 蒙层 */

    private static class OverlayView extends View {
        private final Paint dim = new Paint();
        private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF hole = new RectF();

        OverlayView(Activity context) {
            super(context);
            dim.setColor(0x99000000);
            frame.setColor(0xFFFB7299);
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeCap(Paint.Cap.ROUND);
            frame.setStrokeWidth(getResources().getDisplayMetrics().density * 3f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            DisplayMetrics dm = getResources().getDisplayMetrics();
            float size = Math.min(getWidth(), getHeight()) * 0.62f;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            hole.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);

            canvas.drawRect(0, 0, getWidth(), hole.top, dim);
            canvas.drawRect(0, hole.bottom, getWidth(), getHeight(), dim);
            canvas.drawRect(0, hole.top, hole.left, hole.bottom, dim);
            canvas.drawRect(hole.right, hole.top, getWidth(), hole.bottom, dim);

            float arm = size * 0.12f;
            float r = size * 0.06f;
            // 左上
            canvas.drawLine(hole.left, hole.top + arm, hole.left, hole.top + r, frame);
            canvas.drawLine(hole.left, hole.top, hole.left + arm, hole.top, frame);
            // 右上
            canvas.drawLine(hole.right - arm, hole.top, hole.right, hole.top, frame);
            canvas.drawLine(hole.right, hole.top, hole.right, hole.top + arm, frame);
            // 右下
            canvas.drawLine(hole.right, hole.bottom - arm, hole.right, hole.bottom, frame);
            canvas.drawLine(hole.right, hole.bottom, hole.right - arm, hole.bottom, frame);
            // 左下
            canvas.drawLine(hole.left + arm, hole.bottom, hole.left, hole.bottom, frame);
            canvas.drawLine(hole.left, hole.bottom, hole.left, hole.bottom - arm, frame);
        }
    }
}
