package com.dsh.uvctest;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.graphics.Canvas;
import android.net.Uri;
import android.content.ContentValues;
import android.provider.MediaStore;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

public class MainActivity extends Activity {

    static final String TAG = "UVCTEST";
    static final String ACT_PERM = "com.dsh.uvctest.PERM";
    static final int VENDOR = 0x2BDF;

    UsbManager usbManager;
    UsbDevice device;
    UsbDeviceConnection conn;
    UsbInterface vcIf, vsIf;
    UsbEndpoint epIn;
    UsbEndpoint epIntr;
    int vsNum = -1;

    TextView tvLog;
    LevelBar bar;
    ImageView iv;
    ScrollView sc;
    Button bStream, bOsd, bCap, bDump, bSeq, bTc, bFoc, bFocus, bSnap;
    Button bBar, bMax, bMin, bCenter, bAdj, bDev, bPhoto, bRec;
    LinearLayout devRow, adjRow;
    SeekBar sbBright, sbContrast;
    volatile boolean showBar = true, showMax = false, showMin = false, showCenter = false,
            adjShown = false, devShown = false;
    volatile int brightVal = -1, contrastVal = -1;
    long lastSliderWrite = 0;
    byte[] cacheBasic, cacheBright, cacheContrast;

    final StringBuilder logBuf = new StringBuilder();
    File logFile;
    final Handler ui = new Handler(Looper.getMainLooper());

    // parsed descriptors
    int bcdUVC = 0, xuUnit = -1, xuCtrl = 0;
    String xuGuid = "";
    int fmtIdx = -1, frmIdx = -1, frameW = 0, frameH = 0, frameInt = 333333, frameBuf = 0;
    int maxPayload = 3072;

    volatile boolean streaming = false;
    volatile boolean autoRestart = false;
    volatile int frameCount = 0, fps = 0;
    volatile int hdrSeen = 0, rawSeen = 0, sniffCount = 0;
    volatile byte[] lastJpg;
    volatile double focusLap = -1, focusTen = -1;
    volatile boolean showPeaks = false;
    int[] pxBuf;
    int[] ovBuf;
    Thread thStream;

    static final int REQ_TYPE_IN = 0xA1;   // class, interface, device-to-host
    static final int REQ_TYPE_OUT = 0x21;  // class, interface, host-to-device
    static final int UVC_GET_CUR = 0x81, UVC_SET_CUR = 0x01, UVC_GET_LEN = 0x85;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        logFile = new File(getExternalFilesDir(null), "uvctest.log");
        startLogWriter();
        try { if (logFile.exists()) logFile.delete(); } catch (Exception ignored) {}

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1E1E1E);

        iv = new ImageView(this);
        iv.setBackgroundColor(Color.BLACK);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);

        bar = new LevelBar(this);

        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.addView(iv, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        float d = getResources().getDisplayMetrics().density;
        android.widget.FrameLayout.LayoutParams blp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (12 * d));
        blp.gravity = Gravity.BOTTOM;
        blp.leftMargin = (int) (20 * d);
        blp.rightMargin = (int) (20 * d);
        blp.bottomMargin = (int) (16 * d);
        box.addView(bar, blp);
        LinearLayout.LayoutParams ivp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(box, ivp);

        tvLog = new TextView(this);
        tvLog.setTextColor(Color.parseColor("#00FF88"));
        tvLog.setTextSize(8f);
        tvLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        sc = new ScrollView(this);
        sc.addView(tvLog, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 250));
        sc.setVisibility(View.GONE);

        // ---- top toolbar (VS Code dark) ----
        LinearLayout bar0 = new LinearLayout(this);
        bar0.setOrientation(LinearLayout.HORIZONTAL);
        bar0.setBackgroundColor(0xFF252526);
        bar0.setPadding(8, 6, 8, 6);
        bBar = mkBtn("对焦条");
        bMax = mkBtn("最高");
        bMin = mkBtn("最低");
        bAdj = mkBtn("图像");
        bDev = mkBtn("调试");
        bar0.addView(bBar); bar0.addView(bMax); bar0.addView(bMin);
        bar0.addView(bAdj); bar0.addView(bDev);
        root.addView(bar0, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- dev row (hidden) ----
        devRow = new LinearLayout(this);
        devRow.setOrientation(LinearLayout.HORIZONTAL);
        bDump = mkBtn("日志");
        bSeq = mkBtn("探测");
        bStream = mkBtn("开流");
        bFoc = mkBtn("峰值");
        bFocus = mkBtn("对焦");
        devRow.addView(bDump); devRow.addView(bSeq); devRow.addView(bStream);
        devRow.addView(bFoc); devRow.addView(bFocus);
        devRow.setVisibility(View.GONE);
        root.addView(devRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- brightness / contrast panel (hidden) ----
        adjRow = new LinearLayout(this);
        adjRow.setOrientation(LinearLayout.VERTICAL);
        adjRow.setBackgroundColor(0xFF1E1E1E);
        adjRow.setPadding(16, 8, 16, 8);
        sbBright = new SeekBar(this);
        sbContrast = new SeekBar(this);
        sbBright.setMax(100);
        sbContrast.setMax(100);
        sbBright.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF007ACC));
        sbContrast.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF007ACC));
        adjRow.addView(sbBright, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        adjRow.addView(sbContrast, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        adjRow.setVisibility(View.GONE);
        root.addView(adjRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- shutter row ----
        LinearLayout bar2 = new LinearLayout(this);
        bar2.setOrientation(LinearLayout.HORIZONTAL);
        bar2.setBackgroundColor(0xFF252526);
        bar2.setPadding(8, 6, 8, 6);
        bPhoto = mkBtn("拍照");
        bRec = mkBtn("录像");
        if (bPhoto != null) {
            bPhoto.setTextSize(17f);
            bRec.setTextSize(17f);
        }
        bar2.addView(bPhoto); bar2.addView(bRec);
        root.addView(bar2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // explicit order: toolbar / preview / log / dev row / shutter row
        root.removeAllViews();
        root.addView(bar0, bar0.getLayoutParams());
        root.addView(box, box.getLayoutParams());
        root.addView(sc, sc.getLayoutParams());
        root.addView(devRow, devRow.getLayoutParams());
        root.addView(adjRow, adjRow.getLayoutParams());
        root.addView(bar2, bar2.getLayoutParams());

        setContentView(root);

        IntentFilter f = new IntentFilter(ACT_PERM);
        registerReceiver(permRx, f);

        IntentFilter cf = new IntentFilter("com.dsh.uvctest.CMD");
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String cmd = i.getStringExtra("cmd");
                if (cmd == null) return;
                log("cmd " + cmd);
                switch (cmd) {
                    case "photo": photo(); break;
                    case "rec": if (recording) stopRecording(); else startRecording(); break;
                    case "start": autoRestart = true; if (!streaming) startStream(); break;
                    case "bar": showBar = !showBar; bar.setVisibility(showBar ? View.VISIBLE : View.GONE); styleToggles(); break;
                    case "max": showMax = !showMax; applyOverlays(); break;
                    case "blind": writeBasic(1, 1, 0, 0); log("blind write sent"); break;
                    case "rmw2": readCmd(3, 1, 51); try { Thread.sleep(60); } catch (Exception ignored) {} applyOverlays(); break;
                    case "min": showMin = !showMin; applyOverlays(); break;
                    case "center": showCenter = !showCenter; applyOverlays(); break;
                    case "bright": setBrightness(i.getIntExtra("v", 50)); break;
                    case "contrast": setContrast(i.getIntExtra("v", 50)); break;
                    case "adj": adjShown = !adjShown;
                        adjRow.setVisibility(adjShown ? View.VISIBLE : View.GONE);
                        if (adjShown) loadBrightContrast();
                        styleToggles(); break;
                    case "focus": focusMode = !focusMode; baseAtEntry = -1; peakEntry = -1;
                        baseSettleAt = System.currentTimeMillis() + 700; trendCnt = 0; trendIdx = 0; styleToggles(); break;
                    case "peak": showPeaks = !showPeaks; styleToggles(); break;
                    case "probe": runReadMatrix(); break;
                    case "t2046": probeTemp2064(); break;
                    case "dg": probeDoubleGet(); break;
                    case "mjtest": testMjpegMp4(); break;
                    case "t2046n": stopStream();
                        try { Thread.sleep(400); } catch (Exception ignored) {}
                        probeTemp2064();
                        autoRestart = true; startStream(); break;
                    case "read": testReadBack(); break;
                }
                styleToggles();
            }
        }, cf);

        bDump.setOnClickListener(v -> {
            boolean show = sc.getVisibility() != View.VISIBLE;
            sc.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) probeXu();
        });
        bSeq.setOnClickListener(v -> { testTempReads(); });
        // never stop from a tap: this board's touch panel can synthesize long presses
        bStream.setOnClickListener(v -> {
            autoRestart = true;
            if (streaming) log("stream already running");
            else startStream();
        });
        bFoc.setOnClickListener(v -> { showPeaks = !showPeaks; log("peaks=" + showPeaks); });
        bBar.setOnClickListener(v -> {
            showBar = !showBar;
            bar.setVisibility(showBar ? View.VISIBLE : View.GONE);
            styleToggles();
        });
        bMax.setOnClickListener(v -> { showMax = !showMax; applyOverlays(); });
        bMin.setOnClickListener(v -> { showMin = !showMin; applyOverlays(); });
        bAdj.setOnClickListener(v -> {
            adjShown = !adjShown;
            adjRow.setVisibility(adjShown ? View.VISIBLE : View.GONE);
            if (adjShown) loadBrightContrast();
            styleToggles();
        });
        sbBright.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                if (!fromUser) return;
                long now = System.currentTimeMillis();
                if (now - lastSliderWrite < 120) return;
                lastSliderWrite = now;
                setBrightness(v);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        sbContrast.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                if (!fromUser) return;
                long now = System.currentTimeMillis();
                if (now - lastSliderWrite < 120) return;
                lastSliderWrite = now;
                setContrast(v);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        bDev.setOnClickListener(v -> {
            devShown = !devShown;
            devRow.setVisibility(devShown ? View.VISIBLE : View.GONE);
            styleToggles();
        });
        bPhoto.setOnClickListener(v -> photo());
        bRec.setOnClickListener(v -> {
            if (saving) { toast("保存中…"); return; }
            if (recording) stopRecording(); else startRecording();
            ui.postDelayed(this::styleToggles, 50);
        });
        bFocus.setOnClickListener(v -> {
            focusMode = !focusMode;
            baseAtEntry = -1;
            peakEntry = -1;
            baseSettleAt = System.currentTimeMillis() + 700;
            trendCnt = 0;
            trendIdx = 0;
            log("focus mode=" + focusMode);
        });

        styleToggles();
        log("boot, sdk=" + android.os.Build.VERSION.SDK_INT);
        ensureCameraThenOpen();
    }

    class LevelBar extends android.view.View {
        float level = 0f;
        int state = 0;          // 0 = low, 1 = good, 2 = close
        boolean dim = false;
        final android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        LevelBar(android.content.Context c) { super(c); }

        void set(float lv, int st, boolean d) {
            level = lv;
            state = st;
            dim = d;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            int w = getWidth(), hgt = getHeight();
            float r = hgt / 2f;
            pt.setStyle(android.graphics.Paint.Style.FILL);
            pt.setColor(0xCC000000);
            cv.drawRoundRect(0, 0, w, hgt, r, r, pt);
            pt.setStyle(android.graphics.Paint.Style.STROKE);
            pt.setStrokeWidth(1.5f);
            pt.setColor(0x40FFFFFF);
            cv.drawRoundRect(0.75f, 0.75f, w - 0.75f, hgt - 0.75f, r, r, pt);
            pt.setStyle(android.graphics.Paint.Style.FILL);
            float fw = Math.max(0f, Math.min(1f, level)) * w;
            if (fw > 1) {
                int col = dim ? 0xFF5A6672 : (state == 1 ? 0xFF00E676 : (state == 2 ? 0xFFFFC107 : 0xFFFF5252));
                pt.setColor(col);
                cv.drawRoundRect(0, 0, fw, hgt, r, r, pt);
            }
            pt.setColor(0x99FFFFFF);
            cv.drawRect(w * 0.85f - 1f, 0, w * 0.85f + 1f, hgt, pt);
        }
    }

    Button mkBtn(String lbl) {
        Button b = new Button(this);
        b.setText(lbl);
        b.setTextSize(10f);
        b.setAllCaps(false);
        b.setTextColor(0xFFCCCCCC);
        b.setBackgroundColor(0xFF333333);
        b.setPadding(6, 4, 6, 4);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    void styleToggles() {
        int on = 0xFF007ACC, off = 0xFF333333;
        bBar.setBackgroundColor(showBar ? on : off);
        bMax.setBackgroundColor(showMax ? on : off);
        bMin.setBackgroundColor(showMin ? on : off);
        bAdj.setBackgroundColor(adjShown ? on : off);
        bDev.setBackgroundColor(devShown ? on : off);
        bRec.setBackgroundColor(recording ? 0xFFA31515 : off);
        bRec.setText(recording ? "停止" : "录像");
        bFocus.setBackgroundColor(focusMode ? on : off);
        bFoc.setBackgroundColor(showPeaks ? on : off);
    }

    /** fixed-template write: the struct read back from this firmware is shifted by one byte
     *  relative to the write layout, so read-modify-write corrupts it; keep the known-good template. */
    void applyOverlays() {
        writeBasic(1, showMax ? 1 : 0, showMin ? 1 : 0, 0);
        styleToggles();
        log("overlay max=" + showMax + " min=" + showMin + " cen=" + showCenter);
    }

    void loadBasicCache() {
        byte[] p = readCmd(3, 1, 4 + 47);
        if (p.length < 12) { log("read 2030 failed"); return; }
        cacheBasic = Arrays.copyOfRange(p, 4, p.length);
        log("cached 2030 payload " + cacheBasic.length + "B");
    }

    /** stock app sends: 21 01 wValue=0x0200 wIndex=0x0a00 len=5  {03,88,param,01,value} */
    void sendImageParam(int param, int value) {
        byte[] p = new byte[]{0x03, (byte) 0x88, (byte) param, 0x01, (byte) value};
        int r = ctrl(REQ_TYPE_OUT, UVC_SET_CUR, 0x0200, 0x0A00, p, p.length, 2000);
        log("image param " + param + "=" + value + " -> " + r);
    }

    void loadBrightContrast() {
        byte[] b = readCmd(2, 1, 16);
        byte[] c = readCmd(2, 2, 16);
        cacheBright = b.length > 4 ? Arrays.copyOfRange(b, 4, b.length) : null;
        cacheContrast = c.length > 4 ? Arrays.copyOfRange(c, 4, c.length) : null;
        brightVal = b.length >= 8 ? le32(b, 4) : -1;
        contrastVal = c.length >= 8 ? le32(c, 4) : -1;
        log("bright read len=" + b.length + " val=" + brightVal + " " + hex(b, 0, Math.min(b.length, 12)));
        log("contrast read len=" + c.length + " val=" + contrastVal + " " + hex(c, 0, Math.min(c.length, 12)));
        if (brightVal >= 0) sbBright.setProgress(Math.min(100, brightVal));
        if (contrastVal >= 0) sbContrast.setProgress(Math.min(100, contrastVal));
    }

    void setBrightness(int v) {
        v = Math.max(1, Math.min(100, v));
        sendImageParam(1, v);
        brightVal = v;
        log("brightness " + v);
    }

    void setBrightnessUnused(int v) {
        byte[] p = readCmd(2, 1, 16);
        if (p.length < 8) { log("bright: short read"); return; }
        putInt(p, 4, v);
        selectCmd(2, 1);
        byte[] out = new byte[3 + 4];
        out[0] = 3; out[1] = (byte) 0x88; out[2] = 1;
        putInt(out, 3, v);
        int r = ctrl(REQ_TYPE_OUT, UVC_SET_CUR, 0x0200, 0x0A00, out, out.length, 2000);
        log("brightness set " + v + " (4B) -> " + r);
    }

    void setContrast(int v) {
        v = Math.max(1, Math.min(100, v));
        sendImageParam(2, v);
        contrastVal = v;
        log("contrast " + v);
    }

    void setContrastUnused(int v) {
        byte[] p = readCmd(2, 2, 16);
        if (p.length < 8) { log("contrast: short read"); return; }
        putInt(p, 4, v);
        selectCmd(2, 2);
        byte[] out = new byte[3 + 4];
        out[0] = 3; out[1] = (byte) 0x88; out[2] = 1;
        putInt(out, 3, v);
        int r = ctrl(REQ_TYPE_OUT, UVC_SET_CUR, 0x0200, 0x0A00, out, out.length, 2000);
        log("contrast set " + v + " (4B) -> " + r);
    }

    final java.util.concurrent.ArrayBlockingQueue<String> logQ =
            new java.util.concurrent.ArrayBlockingQueue<>(4000);
    Thread logThread;

    void startLogWriter() {
        logThread = new Thread(() -> {
            while (true) {
                String s;
                try { s = logQ.take(); } catch (Exception e) { return; }
                try (FileOutputStream fo = new FileOutputStream(logFile, true)) {
                    fo.write((s + "\n").getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
            }
        }, "logw");
        logThread.setDaemon(true);
        logThread.start();
    }

    void log(final String s) {
        Log.i(TAG, s);
        logQ.offer(s);
        ui.post(() -> {
            logBuf.append(s).append('\n');
            if (logBuf.length() > 20000) logBuf.delete(0, 10000);
            tvLog.setText(logBuf);
            sc.post(() -> sc.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    // ---------------- USB ----------------

    void ensureCameraThenOpen() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            openDevice();
            return;
        }
        log("requesting CAMERA permission (required for UVC access)");
        requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 1001);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == 1001) {
            boolean ok = res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            log("CAMERA granted=" + ok);
            if (ok) openDevice();
            else toast("需要相机权限才能访问 UVC 设备");
        }
    }

    void openDevice() {
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            log(String.format("dev %04x:%04x %s %s", d.getVendorId(), d.getProductId(),
                    d.getManufacturerName(), d.getProductName()));
            if (d.getVendorId() == VENDOR) device = d;
        }
        if (device == null) { log("no 2bdf device attached"); return; }
        if (usbManager.hasPermission(device)) { onGranted(); return; }
        log("requesting permission...");
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 31) piFlags |= 0x02000000; // FLAG_MUTABLE (API 31)
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACT_PERM).setPackage(getPackageName()), piFlags);
        usbManager.requestPermission(device, pi);
    }

    final BroadcastReceiver permRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            boolean ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            log("permission granted=" + ok);
            if (ok) onGranted();
        }
    };

    void onGranted() {
        conn = usbManager.openDevice(device);
        if (conn == null) { log("openDevice failed"); return; }
        log("openDevice ok, fd=" + conn.getFileDescriptor());

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface it = device.getInterface(i);
            StringBuilder sb = new StringBuilder();
            sb.append("iface ").append(it.getId()).append(" alt ").append(it.getAlternateSetting())
              .append(" cls ").append(it.getInterfaceClass()).append("/").append(it.getInterfaceSubclass())
              .append(" eps ").append(it.getEndpointCount());
            for (int j = 0; j < it.getEndpointCount(); j++) {
                UsbEndpoint ep = it.getEndpoint(j);
                sb.append(" [").append(Integer.toHexString(ep.getAddress()))
                  .append(" t").append(ep.getType()).append(" mps").append(ep.getMaxPacketSize()).append("]");
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT
                        && ep.getDirection() == UsbConstants.USB_DIR_IN) epIntr = ep;
            }
            log(sb.toString());
            if (it.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO && it.getInterfaceSubclass() == 1 && vcIf == null) vcIf = it;
            if (it.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO && it.getInterfaceSubclass() == 2 && vsIf == null) {
                vsIf = it; vsNum = it.getId();
                for (int j = 0; j < it.getEndpointCount(); j++) {
                    UsbEndpoint ep = it.getEndpoint(j);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                            && ep.getDirection() == UsbConstants.USB_DIR_IN) epIn = ep;
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT
                            && ep.getDirection() == UsbConstants.USB_DIR_IN) epIntr = ep;
                }
            }
        }
        log("vcIf=" + (vcIf != null) + " vsIf=" + (vsIf != null) + " epIn=" + (epIn == null ? "null" : Integer.toHexString(epIn.getAddress())));

        if (vcIf != null) log("claim vc=" + conn.claimInterface(vcIf, true));
        if (vsIf != null) log("claim vs=" + conn.claimInterface(vsIf, true));

        dumpDescriptors();
        autoRestart = true;
        startStream();
    }

    int ctrl(int reqType, int req, int val, int idx, byte[] buf, int len, int timeout) {
        return conn.controlTransfer(reqType, req, val, idx, buf, len, timeout);
    }

    // ---------------- descriptors ----------------

    void dumpDescriptors() {
        if (conn == null) { log("dump: no conn"); return; }
        byte[] head = new byte[9];
        int n = ctrl(0x80, 0x06, 0x0200, 0, head, 9, 1000);
        log("GET_DESC(9) -> " + n);
        if (n < 9) return;
        int total = (head[2] & 0xFF) | ((head[3] & 0xFF) << 8);
        byte[] buf = new byte[total];
        n = ctrl(0x80, 0x06, 0x0200, 0, buf, total, 2000);
        log("GET_DESC(" + total + ") -> " + n);
        if (n <= 0) return;

        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < n; i++) {
            hex.append(String.format("%02x", buf[i]));
            if (i % 32 == 31) { log("  " + hex); hex.setLength(0); }
        }
        if (hex.length() > 0) log("  " + hex);

        parseConfig(buf, n);
    }

    static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    static int le32(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24); }

    void parseConfig(byte[] b, int n) {
        log("--- descriptor parse ---");
        int o = 0, cls = -1, sub = -1;
        ArrayList<int[]> frames = new ArrayList<>();
        while (o + 2 <= n) {
            int len = b[o] & 0xFF;
            int type = b[o + 1] & 0xFF;
            if (len < 2 || o + len > n) break;
            if (type == 0x04) {
                cls = b[o + 5] & 0xFF; sub = b[o + 6] & 0xFF;
                log(String.format("IF n=%d alt=%d cls=%d sub=%d eps=%d", b[o + 2] & 0xFF, b[o + 3] & 0xFF, cls, sub, b[o + 4] & 0xFF));
            } else if (type == 0x05) {
                int addr = b[o + 2] & 0xFF, attr = b[o + 3] & 0xFF;
                log(String.format("  EP 0x%02x attr=0x%02x mps=%d int=%d", addr, attr, u16(b, o + 4), b[o + 6] & 0xFF));
            } else if (type == 0x24 && cls == 14) {
                int st = b[o + 2] & 0xFF;
                if (sub == 1) {
                    if (st == 1) { bcdUVC = u16(b, o + 3); log("  VC_HEADER bcdUVC=" + Integer.toHexString(bcdUVC)); }
                    else if (st == 6) {
                        xuUnit = b[o + 3] & 0xFF; xuCtrl = b[o + 20] & 0xFF;
                        StringBuilder g = new StringBuilder();
                        for (int i = 4; i < 20; i++) g.append(String.format("%02x", b[o + i]));
                        xuGuid = g.toString();
                        log("  EXTENSION_UNIT unit=" + xuUnit + " nCtrl=" + xuCtrl + " guid=" + xuGuid);
                    }
                } else if (sub == 2) {
                    if (st == 6) {
                        fmtIdx = b[o + 3] & 0xFF;
                        log("  VS_FORMAT_MJPEG idx=" + fmtIdx + " nFrames=" + (b[o + 4] & 0xFF) + " defFrame=" + (b[o + 6] & 0xFF));
                    } else if (st == 7) {
                        int fi = b[o + 3] & 0xFF, w = u16(b, o + 5), h = u16(b, o + 7);
                        int mfb = le32(b, o + 17), defInt = le32(b, o + 21);
                        log("  VS_FRAME_MJPEG idx=" + fi + " " + w + "x" + h + " maxBuf=" + mfb + " defInt=" + defInt);
                        frames.add(new int[]{fi, w, h, mfb, defInt});
                    }
                }
            }
            o += len;
        }
        // choose 240x320 if present else largest
        int[] best = null;
        for (int[] f : frames) { if (f[1] == 240 && f[2] == 320) best = f; }
        if (best == null) for (int[] f : frames) if (best == null || f[1] * f[2] > best[1] * best[2]) best = f;
        if (best != null) {
            frmIdx = best[0]; frameW = best[1]; frameH = best[2]; frameBuf = best[3]; frameInt = best[4];
            log("chosen frame idx=" + frmIdx + " " + frameW + "x" + frameH + " int=" + frameInt + " maxBuf=" + frameBuf);
        }
    }

    // ---------------- XU ----------------

    byte[] xuIn(int sel, int len, int req) {
        byte[] b = new byte[len];
        int n = ctrl(REQ_TYPE_IN, req, sel << 8, 0x0A00, b, len, 2000);
        if (n < 0) log("XU IN sel=" + sel + " req=0x" + Integer.toHexString(req) + " ERR " + n);
        return (n > 0) ? Arrays.copyOf(b, n) : new byte[0];
    }

    int xuOut(int sel, byte[] data) {
        int n = ctrl(REQ_TYPE_OUT, UVC_SET_CUR, sel << 8, 0x0A00, data, data.length, 2000);
        if (n < 0) log("XU OUT sel=" + sel + " len=" + data.length + " ERR " + n);
        return n;
    }

    void dumpRead(String tag, int sel) {
        byte[] l = new byte[4];
        int nl = ctrl(REQ_TYPE_IN, UVC_GET_LEN, sel << 8, 0x0A00, l, 4, 1000);
        byte[] c = new byte[64];
        int nc = ctrl(REQ_TYPE_IN, UVC_GET_CUR, sel << 8, 0x0A00, c, 64, 1000);
        log(String.format("%s: len(n=%d)=%s cur(n=%d)=%s", tag, nl, hex(l, 0, 4), nc, hex(c, 0, 32)));
    }

    void seqTest() {
        if (conn == null) { log("seq: no conn"); return; }
        log("=== SEQ test (streaming=" + streaming + ") ===");

        log("[A] SET_CUR sel3 {00,05} -> " + xuOut(3, new byte[]{0, 5}));
        dumpRead("  A sel3", 3);
        dumpRead("  A sel2", 2);
        dumpRead("  A sel5", 5);

        log("[B] SET_CUR sel2 {03,00,01,01} -> "
                + ctrl(REQ_TYPE_OUT, UVC_SET_CUR, 0x0200, 0x0A00, new byte[]{3, 0, 1, 1}, 4, 2000));
        dumpRead("  B sel3", 3);
        dumpRead("  B sel2", 2);

        log("[C] SET_CUR sel5 {03,01} -> " + xuOut(5, new byte[]{3, 1}));
        dumpRead("  C sel3", 3);
        dumpRead("  C sel5", 5);
    }

    volatile int selToggle = 0;

    /** the device ignores a SET if the selected command is unchanged, so flip the last byte */
    void selectCmd(int group, int sub) {
        ctrl(REQ_TYPE_OUT, 0x81, group << 8, 0x0A00,
                new byte[]{(byte) group, 0, (byte) sub, 1}, 4, 1000);
    }

    /** select then immediately GET_CUR (GET_LEN clears the pending transaction on this firmware) */
    byte[] readCmd(int group, int sub, int len) {
        selectCmd(group, sub);
        int r = 0;
        if (r < 0) { log("select err " + r); return new byte[0]; }
        byte[] b = new byte[len];
        int n = ctrl(REQ_TYPE_IN, 0x81, group << 8, 0x0A00, b, len, 2000);
        if (n <= 0) { log("read err " + n); return new byte[0]; }
        return Arrays.copyOf(b, n);
    }

    void testReadBack() {
        if (conn == null) return;
        log("=== read back tests ===");
        byte[] p = readCmd(3, 1, 96);
        log("2030 len=" + p.length + " " + hex(p, 0, Math.min(p.length, 64)));

        byte[] m = readCmd(3, 2, 32);
        log("2032 len=" + m.length + " " + hex(m, 0, Math.min(m.length, 32)));

        byte[] v = readCmd(1, 1, 96);
        log("2011 len=" + v.length + " " + hex(v, 0, Math.min(v.length, 48)));

        // 2047 ROI max-temp search: try several request variants
        for (int variant = 0; variant < 3; variant++) {
            byte[] req = new byte[234];
            java.util.Calendar cal = java.util.Calendar.getInstance();
            req[0] = 1;
            req[1] = (byte) cal.get(14);                       // ms lo
            req[2] = (byte) (cal.get(14) >> 8);                 // ms hi
            req[3] = (byte) cal.get(13);                        // sec
            req[4] = (byte) cal.get(12);                        // min
            req[5] = (byte) cal.get(10);                        // hour
            req[6] = (byte) cal.get(5);                         // day
            req[7] = (byte) cal.get(2);                         // month
            req[8] = (byte) cal.get(1);                         // year lo
            req[9] = (byte) (cal.get(1) >> 8);
            req[10] = (byte) (variant == 2 ? 1 : 0);            // jpeg enabled
            req[11] = 1;                                        // max overlay
            req[12] = 0;
            req[13] = 1;                                        // roi count
            req[14] = 1;
            req[15] = 1;
            putInt(req, 16, 0);
            putInt(req, 20, 0);
            putInt(req, 24, 480);
            putInt(req, 28, 640);
            if (variant == 1) req[13] = 0;                      // no ROI at all
            ctrl(REQ_TYPE_OUT, 0x81, 0x0300, 0x0A00, new byte[]{3, 0, 10, 1}, 4, 1000);
            byte[] pre = new byte[3 + req.length];
            pre[0] = 3; pre[1] = (byte) (variant == 1 ? 0x88 : 0x00); pre[2] = 1;
            System.arraycopy(req, 0, pre, 3, req.length);
            int r = ctrl(REQ_TYPE_OUT, UVC_SET_CUR, 0x0300, 0x0A00, pre, pre.length, 3000);
            log("2047 v" + variant + " set -> " + r);
            for (int i = 0; i < 4; i++) {
                try { Thread.sleep(200); } catch (Exception ignored) {}
                byte[] h = new byte[256];
                int n = ctrl(REQ_TYPE_IN, 0x81, 0x0300, 0x0A00, h, 256, 3000);
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < 24 && k < n; k++) sb.append(String.format("%02x", h[k]));
                log("2047 v" + variant + " read#" + i + " n=" + n + " " + sb);
                if (n > 8 && (h[0] & 0xFF) == 3 && (h[3] & 0xFF) == 1) break;
            }
        }

    }

    static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    /** DoubleGet: write request -> read 4-byte head (u32 length at offset 1) -> read body */
    byte[] doubleGet(int group, int sub, byte[] req, boolean prefix) {
        selectCmd(group, sub);
        byte[] payload;
        if (prefix) {
            payload = new byte[3 + req.length];
            payload[0] = 3; payload[1] = (byte) 0x88; payload[2] = 1;
            System.arraycopy(req, 0, payload, 3, req.length);
        } else {
            payload = req;
        }
        int w = ctrl(REQ_TYPE_OUT, UVC_SET_CUR, group << 8, 0x0A00, payload, payload.length, 3000);
        log("  dg set=" + w + " len=" + payload.length);
        byte[] h = new byte[4];
        int L = 0;
        for (int i = 0; i < 4; i++) {
            int n = ctrl(REQ_TYPE_IN, 0x81, group << 8, 0x0A00, h, 4, 3000);
            L = le32(h, 1);
            log("  dg head#" + i + " n=" + n + " " + hex(h, 0, 4) + " L=" + L);
            if (L > 0) break;
            try { Thread.sleep(150); } catch (Exception ignored) {}
        }
        if (L <= 0 || L > 65536) { log("  dg bad len " + L); return new byte[0]; }
        int want = Math.min(L + 512, 8192);
        byte[] data = new byte[want];
        int n2 = ctrl(REQ_TYPE_IN, 0x81, group << 8, 0x0A00, data, want, 4000);
        log("  dg body n=" + n2 + " want=" + want + " " + hex(data, 0, Math.min(Math.max(n2, 0), 32)));
        return data;
    }

    void probeDoubleGet() {
        if (conn == null) return;
        log("=== double get: 2046 ===");
        byte[] r6 = new byte[13];
        r6[0] = 1;
        java.util.Calendar c = java.util.Calendar.getInstance();
        r6[1] = (byte) c.get(14); r6[2] = (byte) (c.get(14) >> 8);
        r6[3] = (byte) c.get(13); r6[4] = (byte) c.get(12); r6[5] = (byte) c.get(10);
        r6[6] = (byte) c.get(5); r6[7] = (byte) c.get(2);
        r6[8] = (byte) c.get(1); r6[9] = (byte) (c.get(1) >> 8);
        doubleGet(3, 9, r6, true);

        log("=== double get: 2047 (1 ROI full frame) ===");
        byte[] r7 = new byte[236];
        r7[0] = 1;
        r7[1] = (byte) c.get(14); r7[2] = (byte) (c.get(14) >> 8);
        r7[3] = (byte) c.get(13); r7[4] = (byte) c.get(12); r7[5] = (byte) c.get(10);
        r7[6] = (byte) c.get(5); r7[7] = (byte) c.get(2);
        r7[8] = (byte) c.get(1); r7[9] = (byte) (c.get(1) >> 8);
        r7[10] = 0;      // jpeg off
        r7[11] = 1;      // max overlay
        r7[12] = 0;
        r7[13] = 1;      // one ROI
        r7[14] = 1; r7[15] = 1;
        putInt(r7, 16, 0);
        putInt(r7, 20, 0);
        putInt(r7, 24, 480);
        putInt(r7, 28, 640);
        doubleGet(3, 10, r7, true);
    }

    /** can the platform mux a JPEG/MJPEG track into MP4? (would avoid H.264 entirely) */
    void testMjpegMp4() {
        byte[] j = lastJpg;
        if (j == null) { log("mjtest: no frame"); return; }
        String path = new java.io.File(getFilesDir(), "mjtest.mp4").getAbsolutePath();
        MediaMuxer mux = null;
        try {
            MediaFormat f = MediaFormat.createVideoFormat("video/mjpeg", 320, 240);
            mux = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = mux.addTrack(f);
            mux.start();
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(j);
            bi.set(0, j.length, 0, MediaCodec.BUFFER_FLAG_KEY_FRAME);
            mux.writeSampleData(track, bb, bi);
            mux.stop();
            log("mjtest: OK mjpeg-in-mp4 supported, size=" + new java.io.File(path).length());
        } catch (Throwable e) {
            log("mjtest: FAIL " + e);
        } finally {
            try { if (mux != null) mux.release(); } catch (Throwable ignored) {}
        }
    }

    void probeTemp2064() {
        if (conn == null) return;
        log("=== 2046 variants ===");
        for (int variant = 0; variant < 3; variant++) {
            byte[] req = new byte[13];
            req[0] = 1;
            java.util.Calendar c = java.util.Calendar.getInstance();
            req[1] = (byte) c.get(14);
            req[2] = (byte) (c.get(14) >> 8);
            req[3] = (byte) c.get(13);
            req[4] = (byte) c.get(12);
            req[5] = (byte) c.get(10);
            req[6] = (byte) c.get(5);
            req[7] = (byte) c.get(2);
            req[8] = (byte) c.get(1);
            req[9] = (byte) (c.get(1) >> 8);

            byte[] payload;
            if (variant == 1) {                       // no 3-byte prefix
                payload = req;
                selectCmd(3, 9);
            } else if (variant == 2) {                // prefix, no select
                payload = new byte[3 + req.length];
                payload[0] = 3; payload[1] = (byte) 0x88; payload[2] = 1;
                System.arraycopy(req, 0, payload, 3, req.length);
            } else {                                  // prefix + select (same as basicParam write)
                payload = new byte[3 + req.length];
                payload[0] = 3; payload[1] = (byte) 0x88; payload[2] = 1;
                System.arraycopy(req, 0, payload, 3, req.length);
                selectCmd(3, 9);
            }
            int w = ctrl(REQ_TYPE_OUT, UVC_SET_CUR, 0x0300, 0x0A00, payload, payload.length, 3000);
            log("2046 v" + variant + " write=" + w + " len=" + payload.length);
            for (int i = 0; i < 4; i++) {
                try { Thread.sleep(250); } catch (Exception ignored) {}
                byte[] h = new byte[64];
                int n = ctrl(REQ_TYPE_IN, 0x81, 0x0300, 0x0A00, h, 64, 3000);
                log("2046 v" + variant + " poll#" + i + " n=" + n + " " + hex(h, 0, Math.min(Math.max(n, 0), 16)));
            }
        }
        log("=== 2046 done ===");
    }

    void runReadMatrix() {
        if (conn == null) return;
        log("=== read matrix (streaming=" + streaming + ") ===");
        int[] sels = {1, 2, 3, 4, 5, 6};
        int[][] variants = {{0xA1, 0x81}, {0xA1, 0x01}, {0xC1, 0x81}, {0xA1, 0x82}};
        for (int sel : sels) {
            for (int v = 0; v < variants.length; v++) {
                byte[] b = new byte[64];
                int n = ctrl(variants[v][0], variants[v][1], sel << 8, 0x0A00, b, 64, 800);
                boolean nz = false;
                for (int i = 0; i < Math.max(n, 0); i++) if (b[i] != 0) nz = true;
                if (nz || n > 0)
                    log(String.format("R sel%d rt=%02x rq=%02x n=%d %s", sel, variants[v][0], variants[v][1], n,
                            hex(b, 0, Math.min(Math.max(n, 0), 24))));
            }
        }
        // after an explicit select for cmd 2030
        ctrl(0x21, 0x81, 0x0300, 0x0A00, new byte[]{0x03, 0x00, 0x01, 0x01}, 4, 800);
        for (int v = 0; v < variants.length; v++) {
            byte[] b = new byte[64];
            int n = ctrl(variants[v][0], variants[v][1], 0x0300, 0x0A00, b, 64, 800);
            log(String.format("S sel3 rt=%02x rq=%02x n=%d %s", variants[v][0], variants[v][1], n, hex(b, 0, Math.min(Math.max(n, 0), 24))));
        }
        log("=== read matrix done ===");
    }

    void testTempReads() {
        if (conn == null) return;
        log("=== temp read probes (streaming=" + streaming + ") ===");

        ctrl(REQ_TYPE_OUT, 0x81, 0x0300, 0x0A00, new byte[]{0x03, 0x00, 0x01, 0x00}, 4, 2000);
        dumpRead("A sel{3,0,1,0} sel3", 3);

        dumpRead("B no-select sel3", 3);

        ctrl(REQ_TYPE_OUT, 0x81, 0x0300, 0x0A00, new byte[]{0x03, 0x00, 0x01, 0x01}, 4, 2000);
        dumpRead("C sel{3,0,1,1} sel3", 3);

        if (epIntr != null) {
            byte[] b = new byte[16];
            int n = conn.bulkTransfer(epIntr, b, 16, 300);
            log("D EP83 n=" + n + " " + hex(b, 0, Math.max(n, 0)));
            n = conn.bulkTransfer(epIntr, b, 16, 300);
            log("D EP83 again n=" + n + " " + hex(b, 0, Math.max(n, 0)));
        } else {
            log("D no interrupt endpoint");
        }
    }

    void probeXu() {
        if (conn == null) { log("probeXu: no conn, streaming=" + streaming); return; }
        log("--- XU control map (streaming=" + streaming + ") ---");
        for (int sel = 1; sel <= 10; sel++) {
            byte[] info = new byte[1];
            int n1 = ctrl(REQ_TYPE_IN, 0x86, sel << 8, 0x0A00, info, 1, 1000);
            byte[] l = new byte[4];
            int n2 = ctrl(REQ_TYPE_IN, UVC_GET_LEN, sel << 8, 0x0A00, l, 4, 1000);
            byte[] c = new byte[32];
            int n3 = ctrl(REQ_TYPE_IN, UVC_GET_CUR, sel << 8, 0x0A00, c, 32, 1000);
            log(String.format("sel%-2d info n=%d %s | len n=%d %s | cur n=%d %s", sel, n1, hex(info, 0, Math.max(n1, 0)),
                    n2, hex(l, 0, Math.max(n2, 0)), n3, hex(c, 0, Math.max(n3, 0))));
        }
    }

    void testXu() {
        for (int i = 0; i < 8; i++) {
            unlock();
            byte[] p = readSimple(3, 1);
            log("try#" + i + " 2030 len=" + p.length);
            if (p.length > 0) { readThermometry(); return; }
            try { Thread.sleep(400); } catch (Exception ignored) {}
        }
    }

    void unlock() {
        log("--- XU unlock ---");
        byte[] l = xuIn(4, 4, UVC_GET_LEN);
        log("GET_LEN sel4 len=" + l.length + " " + hex(l, 0, l.length));
        byte[] v = xuIn(4, 4, UVC_GET_CUR);
        String s = new String(v, StandardCharsets.US_ASCII);
        log("GET_CUR sel4 -> '" + s.replace("\0", ".") + "' len=" + v.length + " " + hex(v, 0, v.length));
    }

    int hold(int group, int sub) {
        int r = xuOut(5, new byte[]{(byte) group, (byte) sub});
        return r;
    }

    int getLen(int group) {
        byte[] l = xuIn(group, 4, UVC_GET_LEN);
        if (l.length >= 4) return le32(l, 0);
        return 0;
    }

    byte[] readSimple(int group, int sub) {
        hold(group, sub);
        int len = getLen(group);
        log("  len(sel" + group + ")=" + len);
        if (len <= 0 || len > 8192) return new byte[0];
        return xuIn(group, len, UVC_GET_CUR);
    }

    void readThermometry() {
        if (conn == null) { log("read2030: no conn"); return; }
        log("--- 2030 thermometry basic param ---");
        byte[] p = readSimple(3, 1);
        log("2030 payload len=" + p.length);
        for (int i = 0; i < p.length; i += 16) log("  " + hex(p, i, Math.min(16, p.length - i)));
    }

    boolean osdState = false;
    int tcMode = 0;

    /** cmd 2040/2041 TEMPERATURE_CORRECT (group 3, sub 6): 24-byte wire struct */
    void writeTempCorrect(int mode) {
        if (conn == null) { log("tc: no conn"); return; }
        byte[] st = new byte[24];
        st[0] = 1;                       // channel
        st[1] = (byte) (mode == 2 ? 0 : 1);
        st[2] = (byte) (mode == 2 ? 0 : 1);
        st[3] = (byte) (mode == 0 ? 1 : 0);
        st[16] = 0; st[17] = 0;          // centre X (u16)
        st[18] = 0; st[19] = 0;          // centre Y (u16)
        byte[] p = new byte[27];
        p[0] = 0x03; p[1] = (byte) 0x88; p[2] = 0x01;
        System.arraycopy(st, 0, p, 3, 24);
        int r1 = ctrl(REQ_TYPE_OUT, 0x81, 0x0300, 0x0A00, new byte[]{0x03, 0x00, 0x06, 0x01}, 4, 2000);
        int r2 = ctrl(REQ_TYPE_OUT, UVC_SET_CUR, 0x0300, 0x0A00, p, p.length, 2000);
        log("tempCorrect(mode=" + mode + ") select=" + r1 + " write=" + r2 + " " + hex(p, 0, p.length));
    }

    void osdOff() {
        osdState = !osdState;
        if (osdState) writeBasic(1, 1, 1, 1);
        else writeBasic(0, 0, 0, 0);
    }

    /** cmd 2030/2031: select {03,00,01,01} then write {03,88,01} + 47-byte struct */
    void writeBasic(int enabled, int showMax, int showMin, int showAvg) {
        if (conn == null) { log("basic: no conn"); return; }
        byte[] st = new byte[47];
        st[0] = 1;                    // byChannelID
        st[1] = (byte) enabled;       // byEnabled
        st[2] = (byte) showMax;       // displayMax
        st[3] = (byte) showMin;       // displayMin
        st[4] = (byte) showAvg;       // displayAvg
        st[5] = 1;                    // unit
        st[6] = 1;                    // range
        st[12] = (byte) 0xb0; st[13] = 4;   // extOpticsWindowCorrection = 1200
        st[16] = 0x62;                      // emissivity = 98 (0.98)
        st[20] = 1;                         // distance unit
        st[21] = 100;                       // distance
        st[26] = (byte) 0xb0; st[27] = 4;   // reflective temperature = 1200
        // s[30]=displayPosition, s[31]=streamOverlay, s[44]=displayCenTemp -> 0
        byte[] p = new byte[50];
        p[0] = 0x03; p[1] = (byte) 0x88; p[2] = 0x01;
        System.arraycopy(st, 0, p, 3, 47);

        int r1 = ctrl(REQ_TYPE_OUT, 0x81, 0x0300, 0x0A00, new byte[]{0x03, 0x00, 0x01, 0x01}, 4, 2000);
        int r2 = ctrl(REQ_TYPE_OUT, UVC_SET_CUR, 0x0300, 0x0A00, p, p.length, 2000);
        log("basic(en=" + enabled + ",max=" + showMax + ",min=" + showMin + ",avg=" + showAvg + ") select=" + r1 + " write=" + r2);
        log("  payload " + hex(p, 0, 32));
    }

    // ---------------- stream ----------------

    void restartStream() {
        if (!autoRestart) return;
        log("restarting stream...");
        try { startStream(); } catch (Exception e) { log("restart err " + e); }
    }

    void startStream() {
        if (conn == null || vsIf == null || epIn == null) { log("stream: missing iface/ep"); return; }
        log("--- start stream fmt=" + fmtIdx + " frm=" + frmIdx + " ---");
        byte[] p = new byte[34];
        ByteBuffer bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort((short) 0);
        bb.put((byte) fmtIdx).put((byte) frmIdx);
        bb.putInt(frameInt);
        bb.putShort((short) 0).putShort((short) 0).putShort((short) 1).putShort((short) 0).putShort((short) 0);
        bb.putInt(frameBuf > 0 ? frameBuf : 230400);
        bb.putInt(maxPayload);
        bb.putInt(0);
        bb.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);

        log("SET_CUR PROBE -> " + ctrl(REQ_TYPE_OUT, UVC_SET_CUR, 0x0100, vsNum, p, 34, 2000));
        log("GET_CUR PROBE -> " + ctrl(REQ_TYPE_IN, UVC_GET_CUR, 0x0100, vsNum, p, 34, 2000));
        ByteBuffer rb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
        rb.position(17);
        log("  negotiated maxFrame=" + rb.getInt() + " maxPayload=" + rb.getInt());
        log("SET_CUR COMMIT -> " + ctrl(REQ_TYPE_OUT, UVC_SET_CUR, 0x0200, vsNum, p, 34, 2000));
        log("setInterface -> " + conn.setInterface(vsIf));

        streaming = true;
        frameCount = 0;
        thStream = new Thread(this::streamLoop, "uvc-rx");
        thStream.start();
        log("streaming...");
        ui.postDelayed(this::testXu, 1500);
    }

    void stopStream() {
        streaming = false;
        log("stop, frames=" + frameCount);
        try { thStream.join(500); } catch (Exception ignored) {}
    }

    void streamLoop() {
        byte[] buf = new byte[16384];
        byte[] acc = new byte[1 << 20];
        int accLen = 0;
        long t0 = System.currentTimeMillis(), tLog = t0;
        int f = 0;
        long lastFocusLog = 0;
        long lastRx = System.currentTimeMillis();
        while (streaming) {
            int n = conn.bulkTransfer(epIn, buf, buf.length, 500);
            if (n > 0) lastRx = System.currentTimeMillis();
            if (System.currentTimeMillis() - lastRx > 3000) {
                log("watchdog: no data for 3s, restarting stream");
                break;
            }
            if (n > 0) {
                // each UVC payload packet starts with a header: [bHeaderLength][bmHeaderInfo]
                int off = 0;
                boolean eof = false;
                int hl = buf[0] & 0xFF;
                if (hl >= 2 && hl <= 12 && hl < n) {
                    eof = (buf[1] & 0x02) != 0;   // UVC_STREAM_EOF
                    off = hl;
                    hdrSeen++;
                    if ((buf[1] & 0xFF) != 0x80 && sniffCount < 40) {
                        sniffCount++;
                        log("RX flags=0x" + Integer.toHexString(buf[1] & 0xFF) + " " + hex(buf, 0, Math.min(n, 24)));
                    }
                    if (off + 1 < n && (buf[off] & 0xFF) != 0xFF && sniffCount < 40) {
                        sniffCount++;
                        log("RX nonjpeg " + hex(buf, 0, Math.min(n, 24)));
                    }
                } else {
                    rawSeen++;
                    if (sniffCount < 40) {
                        sniffCount++;
                        log("RX raw " + hex(buf, 0, Math.min(n, 24)));
                    }
                }
                int pay = n - off;
                if (accLen + pay > acc.length) accLen = 0;
                System.arraycopy(buf, off, acc, accLen, pay);
                accLen += pay;
                int soi = -1;
                for (int i = 0; i + 1 < accLen; i++) if ((acc[i] & 0xFF) == 0xFF && (acc[i + 1] & 0xFF) == 0xD8) { soi = i; break; }
                if (soi >= 0) {
                    int eoi = -1;
                    for (int i = soi + 2; i + 1 < accLen; i++) if ((acc[i] & 0xFF) == 0xFF && (acc[i + 1] & 0xFF) == 0xD9) { eoi = i + 2; break; }
                    if (eoi > 0) {
                        byte[] jpg = Arrays.copyOfRange(acc, soi, eoi);
                        int rest = accLen - eoi;
                        System.arraycopy(acc, eoi, acc, 0, rest);
                        accLen = rest;
                        final Bitmap bm = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                        if (bm != null) {
                            f++;
                            frameCount = f;
                            lastJpg = jpg;
                            final Bitmap disp;
                            if (f % 2 == 0 || showPeaks) {
                                disp = processFrame(bm);
                                ui.post(() -> iv.setImageBitmap(disp));
                            } else {
                                ui.post(() -> iv.setImageBitmap(bm));
                            }
                        }
                    }
                }
                if (accLen > acc.length - 65536) accLen = 0;
            }
            long now = System.currentTimeMillis();
            if (now - lastFocusLog > 500) {
                lastFocusLog = now;
                log(String.format("F roi=%.1f ten=%.1f lap=%.1f peak=%.1f base=%.1f pct=%.0f inf=%b", focusRoi, focusTen, focusLap, peakTen, baseTen, lastPct, inFocus));
            }
            if (now - tLog > 2000) {
                fps = (int) (f * 1000 / (now - t0));
                log("frames=" + f + " fps~" + fps + " hdr=" + hdrSeen + " raw=" + rawSeen
                        + " lap=" + String.format("%.1f", focusLap) + " ten=" + String.format("%.1f", focusTen));
                tLog = now;
            }
        }
        log("stream loop exit, frames=" + f);
        if (streaming) {                 // watchdog tripped, not a user stop
            streaming = false;
            if (autoRestart) ui.postDelayed(this::restartStream, 500);
        }
    }

    // ---------------- focus ----------------

    static int gray(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    static final int HIST = 400;                 // ~40 s at ~10 Hz
    final double[] histTen = new double[HIST];
    final long[] histT = new long[HIST];
    int histIdx = 0, histCnt = 0;

    volatile double focusRoi = -1, peakTen = -1, baseTen = -1;
    boolean focusMode = false;       // relative focus mode (baseline + trend arrow)
    double baseAtEntry = -1, peakEntry = -1;
    long baseSettleAt = 0;
    final double[] trend = new double[8];
    int trendIdx = 0, trendCnt = 0;
    int[] prevS = null;              // previous decimated grayscale samples
    boolean havePrev = false;
    int bigDiff = 0;
    long lastReset = 0;
    double sceneDiff = 0;
    volatile boolean inFocus = false;
    double lastPct = 0;

    long lastAddMs = 0;

    void record(double v) {
        long now = System.currentTimeMillis();
        int last = (histIdx - 1 + HIST) % HIST;
        if (histCnt > 0 && now - lastAddMs < 90) {   // decimate: refresh latest value only
            histTen[last] = v;
            return;
        }
        histTen[histIdx] = v;
        histT[histIdx] = now;
        histIdx = (histIdx + 1) % HIST;
        if (histCnt < HIST) histCnt++;
        lastAddMs = now;
    }

    double windowPeak(long ms) {
        long now = System.currentTimeMillis();
        double[] tmp = new double[HIST];
        int n = 0;
        for (int k = 0; k < histCnt; k++) {
            int i = (histIdx - 1 - k + HIST) % HIST;
            if (now - histT[i] > ms) break;
            tmp[n++] = histTen[i];
        }
        if (n == 0) return 0;
        java.util.Arrays.sort(tmp, 0, n);
        return tmp[Math.min(n - 1, n * 98 / 100)];
    }

    double windowLow(long ms) {
        long now = System.currentTimeMillis();
        double[] tmp = new double[HIST];
        int n = 0;
        for (int k = 0; k < histCnt; k++) {
            int i = (histIdx - 1 - k + HIST) % HIST;
            if (now - histT[i] > ms) break;
            tmp[n++] = histTen[i];
        }
        if (n == 0) return 0;
        java.util.Arrays.sort(tmp, 0, n);
        return tmp[Math.max(0, n / 5)];
    }

    /** Tenengrad on full frame + center ROI, rolling peak lock, focus bar HUD, optional peak overlay. */
    Bitmap processFrame(Bitmap bm) {
        int w = bm.getWidth(), h = bm.getHeight();
        if (pxBuf == null || pxBuf.length != w * h) pxBuf = new int[w * h];
        bm.getPixels(pxBuf, 0, w, 0, 0, w, h);
        if (ovBuf == null || ovBuf.length != w * h) ovBuf = new int[w * h];

        double sumLap = 0, sumTen = 0, roiTen = 0;
        int n = 0, rn = 0;
        int rx0 = w / 4, rx1 = w - w / 4, ry0 = h / 4, ry1 = h - h / 4;
        int sw = w / 2 + 1;
        if (prevS == null || prevS.length != sw * (h / 2 + 1)) {
            prevS = new int[sw * (h / 2 + 1)];
            havePrev = false;
        }
        long diffAcc = 0;
        int diffN = 0;
        for (int y = 1; y < h - 1; y += 2) {
            for (int x = 1; x < w - 1; x += 2) {
                int i = y * w + x;
                int c = gray(pxBuf[i]);
                int u = gray(pxBuf[i - w]), d = gray(pxBuf[i + w]);
                int l = gray(pxBuf[i - 1]), r = gray(pxBuf[i + 1]);
                int lap = 4 * c - u - d - l - r;
                int gx = r - l, gy = d - u;
                double g2 = (double) gx * gx + (double) gy * gy;
                sumLap += (double) lap * lap;
                sumTen += g2;
                n++;
                if (x >= rx0 && x < rx1 && y >= ry0 && y < ry1) { roiTen += g2; rn++; }
                int si = (y / 2) * sw + (x / 2);
                if (havePrev) { diffAcc += Math.abs(c - prevS[si]); diffN++; }
                prevS[si] = c;
            }
        }
        if (n == 0) return bm;
        sceneDiff = diffN > 0 ? (double) diffAcc / diffN : 0;
        long nowMs = System.currentTimeMillis();
        if (havePrev && sceneDiff > 30 && nowMs - lastReset > 1500) {
            if (++bigDiff >= 3) {
                bigDiff = 0;
                histCnt = 0;
                histIdx = 0;
                peakTen = 0;
                lastReset = nowMs;
                baseAtEntry = -1;
                peakEntry = -1;
                baseSettleAt = nowMs + 700;
                log("scene change (diff=" + String.format("%.1f", sceneDiff) + ") -> peak reset");
            }
        } else {
            bigDiff = 0;
        }
        havePrev = true;
        focusTen = sumTen / n;
        focusLap = sumLap / n;
        focusRoi = rn > 0 ? roiTen / rn : focusTen;

        record(focusTen);
        peakTen = windowPeak(10000);
        baseTen = windowLow(6000);
        boolean warm = histCnt < 20 || System.currentTimeMillis() - lastReset < 1500;
        double thr = Math.max(60.0, peakTen * 0.85);  // absolute floor + 85% of peak
        inFocus = !warm && peakTen >= 45 && focusTen >= thr;
        lastPct = peakTen > 1 ? Math.min(100.0, focusTen * 100.0 / peakTen) : 0;

        StringBuilder sb = new StringBuilder();
        final String hud;
        if (focusMode) {
            if (baseAtEntry < 0) {
                if (System.currentTimeMillis() < baseSettleAt) {
                    ui.post(() -> bar.set(0f, 0, true));
                    return showPeaks ? peakOverlay(bm) : bm;
                }
                baseAtEntry = focusTen;
                peakEntry = focusTen;
            }
            if (focusTen > peakEntry) peakEntry = focusTen;
            boolean best = peakEntry > baseAtEntry * 1.08 && (peakEntry - baseAtEntry) > 8
                    && focusTen >= peakEntry * 0.97;
            double lv = peakEntry > 1 ? focusTen / peakEntry : 0;
            final float lf = (float) Math.max(0, Math.min(1, lv));
            final int st = best ? 1 : (lv > 0.75 ? 2 : 0);
            ui.post(() -> bar.set(lf, st, false));
        } else if (warm || peakTen < 45) {
            ui.post(() -> bar.set(0f, 0, true));
        } else {
            double lv = Math.max(0, Math.min(1, focusTen / Math.max(peakTen, 1)));
            final float lf = (float) lv;
            final int st = inFocus ? 1 : (lv > 0.75 ? 2 : 0);
            ui.post(() -> bar.set(lf, st, false));
        }

        if (!showPeaks) return bm;
        return peakOverlay(bm);
    }

    Bitmap peakOverlay(Bitmap bm) {
        int w = bm.getWidth(), h = bm.getHeight();
        if (pxBuf == null || pxBuf.length != w * h) pxBuf = new int[w * h];
        bm.getPixels(pxBuf, 0, w, 0, 0, w, h);
        if (ovBuf == null || ovBuf.length != w * h) ovBuf = new int[w * h];
        int[] mag = ovBuf;
        int[] hist = new int[256];
        int total = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                int gx = gray(pxBuf[i + 1]) - gray(pxBuf[i - 1]);
                int gy = gray(pxBuf[i + w]) - gray(pxBuf[i - w]);
                int g = (int) Math.sqrt(gx * gx + gy * gy);
                if (g > 255) g = 255;
                mag[i] = g;
                hist[g]++;
                total++;
            }
        }
        int target = total * 975 / 1000, acc = 0, thrP = 8;
        for (int v = 0; v < 256; v++) { acc += hist[v]; if (acc >= target) { thrP = Math.max(8, v); break; } }
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                if (mag[i] >= thrP) {
                    ovBuf[i] = 0xFFFF1010;
                    if (x + 1 < w - 1) ovBuf[i + 1] = 0xFFFF1010;
                    if (y + 1 < h - 1) ovBuf[i + w] = 0xFFFF1010;
                } else {
                    ovBuf[i] = pxBuf[i];
                }
            }
        }
        ovBuf[0] = pxBuf[0];
        return Bitmap.createBitmap(ovBuf, w, h, Bitmap.Config.ARGB_8888);
    }

    void snap() {
        byte[] j = lastJpg;
        if (j == null) { log("snap: no frame yet"); return; }
        try {
            File dir = new File(getExternalFilesDir(null), "snap");
            if (!dir.exists()) dir.mkdirs();
            String ts = new java.text.SimpleDateFormat("HHmmss", java.util.Locale.US).format(new java.util.Date());
            File f = new File(dir, "snap_" + ts + ".jpg");
            try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(j); }
            log("SNAP " + f.getName() + " " + j.length + "B lap=" + String.format("%.1f", focusLap)
                    + " ten=" + String.format("%.1f", focusTen));
        } catch (Exception e) {
            log("snap err " + e);
        }
    }

    // ---------------- photo / video ----------------

    void toast(final String s) {
        ui.post(() -> android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show());
    }

    void photo() {
        byte[] j = lastJpg;
        if (j == null) { toast("no frame"); return; }
        String name = "4117_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date()) + ".jpg";
        try {
            if (android.os.Build.VERSION.SDK_INT < 29) {
                java.io.File dir = new java.io.File(getExternalFilesDir(null), "photos");
                if (!dir.exists()) dir.mkdirs();
                java.io.File f = new java.io.File(dir, name);
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) { fo.write(j); }
                toast("saved " + f.getAbsolutePath());
                log("photo " + f.getAbsolutePath() + " " + j.length + "B");
                return;
            }
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/4117");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(j); }
            toast("saved " + name);
            log("photo " + name + " " + j.length + "B");
        } catch (Exception e) {
            log("photo err " + e);
            toast("save failed");
        }
    }

    // ---------------- motion-JPEG AVI recorder ----------------
    // (MediaCodec/MediaMuxer kills this board's process on stop, so we mux MJPEG into AVI by hand)

    volatile boolean recording = false;
    Thread vThread;
    AviWriter avi;
    String vPath;
    volatile int vSamples = 0;

    // ---------------- recording: MP4 (H.264) primary, AVI (MJPEG) fallback ----------------

    MediaCodec mpEnc;
    MediaMuxer mpMux;
    int mpTrack = -1, mpW, mpH;
    long mpPts;
    boolean mpMuxStarted;
    volatile boolean mpActive = false;
    Thread mpThread;
    String mpPath;

    void startRecording() {
        if (recording || saving) return;
        if (tryStartMp4()) return;
        log("mp4 unavailable -> avi fallback");
        startRecordingAvi();
    }

    void stopRecording() {
        if (mrActive) { stopMp4(); return; }
        stopRecordingAvi();
    }

    MediaRecorder mrec;
    android.view.Surface msurf;
    Thread mrThread;
    volatile boolean mrActive = false;
    volatile boolean saving = false;
    String mrPath;
    int mrW, mrH;

    boolean tryStartMp4() {
        byte[] j = lastJpg;
        if (j == null) return false;
        Bitmap bm = BitmapFactory.decodeByteArray(j, 0, j.length);
        if (bm == null) return false;
        mrW = bm.getWidth();
        mrH = bm.getHeight();
        try {
            mrec = new MediaRecorder();
            mrec.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mrec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mrec.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mrec.setVideoSize(mrW, mrH);
            mrec.setVideoFrameRate(25);
            mrec.setVideoEncodingBitRate(2000000);
            mrPath = new java.io.File(getFilesDir(), "vid_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();
            mrec.setOutputFile(mrPath);
            mrec.prepare();
            msurf = mrec.getSurface();
            mrec.start();
            mrActive = true;
            recording = true;
            mrThread = new Thread(this::mrLoop, "mr-rec");
            mrThread.start();
            toast("REC (MP4)");
            log("rec start mediarecorder " + mrW + "x" + mrH);
            return true;
        } catch (Throwable e) {
            log("mediarecorder init failed: " + e);
            try { if (mrec != null) mrec.release(); } catch (Throwable ignored) {}
            mrec = null;
            msurf = null;
            mrActive = false;
            recording = false;
            return false;
        }
    }

    void mrLoop() {
        long lastAt = 0;
        while (mrActive) {
            long now = System.currentTimeMillis();
            if (now - lastAt < 40) {
                try { Thread.sleep(5); } catch (Exception ignored) {}
                continue;
            }
            lastAt = now;
            byte[] j = lastJpg;
            if (j == null) continue;
            Bitmap bm = BitmapFactory.decodeByteArray(j, 0, j.length);
            if (bm == null) continue;
            Canvas c = null;
            try {
                c = msurf.lockCanvas(null);
                if (c != null) {
                    c.drawBitmap(bm, 0, 0, null);
                }
            } catch (Throwable e) {
                log("mr draw err: " + e);
                break;
            } finally {
                if (c != null) {
                    try { msurf.unlockCanvasAndPost(c); } catch (Throwable ignored) {}
                }
            }
        }
        log("mr loop exit");
    }

    void stopMp4() {
        if (!mrActive) return;
        mrActive = false;
        recording = false;
        saving = true;
        ui.post(this::styleToggles);          // button flips back immediately
        new Thread(() -> {
            try { if (mrThread != null) mrThread.join(2000); } catch (Exception ignored) {}
            try { mrec.stop(); log("mr stopped"); } catch (Throwable e) { log("mr stop err: " + e); }
            try { mrec.release(); } catch (Throwable ignored) {}
            mrec = null;
            msurf = null;
            long sz = new java.io.File(mrPath).length();
            log("mp4 saved " + mrPath + " size=" + sz);
            if (sz > 0) publishVideo(mrPath, "video/mp4", "Movies/4117");
            saving = false;
            ui.post(() -> { styleToggles(); toast("MP4 已保存"); });
        }, "mr-stop").start();
    }

    void startRecordingAvi() {
        if (recording) return;
        byte[] j = lastJpg;
        if (j == null) { toast("no frame"); return; }
        Bitmap bm = BitmapFactory.decodeByteArray(j, 0, j.length);
        if (bm == null) return;
        try {
            vPath = new java.io.File(getFilesDir(), "vid_" + System.currentTimeMillis() + ".avi").getAbsolutePath();
            avi = new AviWriter(vPath, bm.getWidth(), bm.getHeight(), 25);
            vSamples = 0;
            recording = true;
            vThread = new Thread(this::recordLoop, "mjpeg-avi");
            vThread.start();
            toast("录像");
            log("rec start " + bm.getWidth() + "x" + bm.getHeight() + " " + vPath);
        } catch (Exception e) {
            recording = false;
            log("rec err " + e);
            toast("rec failed");
        }
    }

    void stopRecordingAvi() {
        if (!recording) return;
        recording = false;
        saving = true;
        ui.post(this::styleToggles);
        final AviWriter w = avi;
        final String path = vPath;
        new Thread(() -> {
            try { if (vThread != null) vThread.join(3000); } catch (Exception ignored) {}
            try { if (w != null) w.close(); } catch (Exception e) { log("avi close err " + e); }
            log("rec stop frames=" + vSamples + " file=" + path);
            publishVideo(path, "video/x-msvideo", "Movies/4117");
            saving = false;
            ui.post(() -> { styleToggles(); toast("saved"); });
        }, "avi-stop").start();
        avi = null;
    }

    void recordLoop() {
        long lastAt = 0;
        while (recording) {
            long now = System.currentTimeMillis();
            if (now - lastAt >= 40) {
                lastAt = now;
                byte[] j = lastJpg;
                if (j != null && avi != null) {
                    try { avi.addFrame(j); vSamples++; } catch (Exception e) { log("avi add err " + e); }
                }
            }
            try { Thread.sleep(5); } catch (Exception ignored) {}
        }
    }

    static void rgbToNv12(int[] argb, byte[] out, int w, int h) {
        int frames = w * h;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = argb[y * w + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                int yy = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                out[y * w + x] = (byte) Math.max(0, Math.min(255, yy));
                if ((y & 1) == 0 && (x & 1) == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    int i = frames + (y / 2) * w + x;
                    out[i] = (byte) Math.max(0, Math.min(255, u));
                    out[i + 1] = (byte) Math.max(0, Math.min(255, v));
                }
            }
        }
    }

    void publishVideo(String path, String mime, String relPath) {
        try {
            java.io.File f = new java.io.File(path);
            if (android.os.Build.VERSION.SDK_INT < 29) {
                log("video kept at " + f.getAbsolutePath() + " (API<29, no MediaStore publish)");
                return;
            }
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, f.getName());
            cv.put(MediaStore.Video.Media.MIME_TYPE, mime);
            cv.put(MediaStore.Video.Media.RELATIVE_PATH, relPath);
            Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            try (java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                 java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            log("video published " + f.getName());
        } catch (Exception e) {
            log("publish err " + e);
        }
    }

    /** Minimal AVI (MJPEG) writer: header is patched on close. */
    static class AviWriter {
        java.io.RandomAccessFile raf;
        int w, h, fps, frames = 0;
        long riffSizePos, totalFramesPos, moviListSizePos, moviStart;
        final java.util.ArrayList<Integer> offs = new java.util.ArrayList<>();
        final java.util.ArrayList<Integer> sizes = new java.util.ArrayList<>();

        AviWriter(String path, int w, int h, int fps) throws Exception {
            this.w = w; this.h = h; this.fps = fps;
            raf = new java.io.RandomAccessFile(path, "rw");
            raf.setLength(0);
            w4("RIFF"); riffSizePos = raf.getFilePointer(); w4(0); w4("AVI ");
            long hdrl = beginList("hdrl");
            w4("avih"); w4(56);
            w4(1000000 / fps); w4(0); w4(0); w4(0x10);
            totalFramesPos = raf.getFilePointer(); w4(0);
            w4(0); w4(1); w4(w * h * 3); w4(w); w4(h);
            w4(0); w4(0); w4(0); w4(0);
            long strl = beginList("strl");
            w4("strh"); w4(56);                 // AVIStreamHeader = 56 bytes exactly
            w4("vids"); w4("MJPG");             // fccType, fccHandler
            w4(0);                              // dwFlags
            w4(0);                              // wPriority + wLanguage
            w4(0);                              // dwInitialFrames
            w4(1);                              // dwScale
            w4(fps);                            // dwRate
            w4(0);                              // dwStart
            w4(0);                              // dwLength
            w4(w * h * 3);                      // dwSuggestedBufferSize
            w4(0);                              // dwQuality
            w4(0);                              // dwSampleSize
            w4(0);                              // rcFrame left/top
            w4((h << 16) | w);                  // rcFrame right/bottom
            w4("strf"); w4(40);
            w4(40); w4(w); w4(h); w4(0x00180001); w4("MJPG");
            w4(w * h * 3); w4(0); w4(0); w4(0); w4(0);
            endList(strl);
            endList(hdrl);
            moviListSizePos = raf.getFilePointer() + 4;
            w4("LIST"); w4(0); moviStart = raf.getFilePointer(); w4("movi");
        }

        void w4(int v) throws Exception { raf.write(v & 0xFF); raf.write((v >> 8) & 0xFF); raf.write((v >> 16) & 0xFF); raf.write((v >> 24) & 0xFF); }
        void w4(String s) throws Exception { for (int i = 0; i < 4; i++) raf.write(s.charAt(i)); }

        long beginList(String type) throws Exception {
            long p = raf.getFilePointer();
            w4("LIST"); w4(0); w4(type);
            return p;
        }

        void endList(long p) throws Exception {
            long cur = raf.getFilePointer();
            raf.seek(p + 4);
            w4((int) (cur - p - 8));
            raf.seek(cur);
        }

        void addFrame(byte[] jpg) throws Exception {
            int off = (int) (raf.getFilePointer() - moviStart);
            w4("00dc"); w4(jpg.length); raf.write(jpg);
            if ((jpg.length & 1) == 1) raf.write(0);
            offs.add(off);
            sizes.add(jpg.length);
            frames++;
        }

        void close() throws Exception {
            long idxPos = raf.getFilePointer();
            w4("idx1"); w4(frames * 16);
            for (int i = 0; i < frames; i++) {
                w4("00dc"); w4(0x10); w4(offs.get(i)); w4(sizes.get(i));
            }
            long end = raf.getFilePointer();
            raf.seek(moviListSizePos);
            w4((int) (idxPos - moviListSizePos - 4));
            raf.seek(totalFramesPos);
            w4(frames);
            raf.seek(riffSizePos);
            w4((int) (end - 8));
            raf.seek(end);
            raf.close();
        }
    }

    // ---------------- capture ----------------

    byte[] readPayload(int group, int total) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int guard = 0;
        while (out.size() < total && guard++ < 64) {
            int remain = total - out.size();
            int want = Math.min(512, remain + 5);
            byte[] b = new byte[want];
            int n = ctrl(REQ_TYPE_IN, UVC_GET_CUR, group << 8, 0x0A00, b, want, 3000);
            if (n <= 0) { log("  chunk ERR " + n); break; }
            int off = 0;
            if (n >= 5 && (b[0] & 0xFF) == 0x02) off = 5;
            out.write(b, off, n - off);
        }
        return out.toByteArray();
    }

    void capture() {
        if (conn == null) { log("cap: no conn"); return; }
        log("--- capture 2046 ---");
        hold(3, 9);
        byte[] req = new byte[13];
        req[0] = 1;
        log("SET_CUR sel3 req -> " + xuOut(3, req));
        int l = getLen(3);
        log("GET_LEN sel3 -> " + l);
        byte[] h = xuIn(3, Math.max(l, 5), UVC_GET_CUR);
        log("phase A head " + hex(h, 0, h.length));
        if (h.length < 5) { log("bad phase A"); return; }
        int total = le32(h, 1);
        log("total=" + total);
        byte[] body = readPayload(3, total);
        log("body=" + body.length);
        if (body.length < 27) { log("short body " + hex(body, 0, body.length)); return; }
        int jl = le32(body, 1), w = le32(body, 5), hh = le32(body, 9), bl = le32(body, 13);
        log("jpegLen=" + jl + " w=" + w + " h=" + hh + " matrixBytes=" + bl);
        try {
            File dir = new File(getExternalFilesDir(null), "cap");
            dir.mkdirs();
            String ts = "" + System.currentTimeMillis();
            File jf = new File(dir, "cap_" + ts + ".jpg");
            try (FileOutputStream fo = new FileOutputStream(jf)) { fo.write(body, 27, jl); }
            File rf = new File(dir, "cap_" + ts + ".raw");
            try (FileOutputStream fo = new FileOutputStream(rf)) { fo.write(body, 27 + jl, Math.min(bl, body.length - 27 - jl)); }
            log("saved " + jf.getName() + " " + jf.length() + "B, " + rf.getName() + " " + rf.length() + "B");
            Bitmap bm = BitmapFactory.decodeByteArray(body, 27, jl);
            if (bm != null) ui.post(() -> iv.setImageBitmap(bm));
        } catch (Exception e) {
            log("save err " + e);
        }
    }

    static String hex(byte[] b, int off, int len) {
        if (b == null) return "null";
        StringBuilder s = new StringBuilder();
        for (int i = off; i < off + len && i < b.length; i++) s.append(String.format("%02x", b[i]));
        return s.toString();
    }

    @Override
    protected void onDestroy() {
        streaming = false;
        try { unregisterReceiver(permRx); } catch (Exception ignored) {}
        if (conn != null) conn.close();
        super.onDestroy();
    }
}

