package com.fredrik.fold5proto50;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    static final String TAG = "FOLD5PROTO";

    TextView status;
    CameraManager mgr;
    volatile boolean busy = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Keep the probe usable over adb: it must stay visible (Android denies camera
        // access to background apps) and must not be dimmed away mid-sweep.
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        Button shoot = new Button(this);
        shoot.setText("Run sweep");
        shoot.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { start(); }
        });
        Button hr = new Button(this);
        hr.setText("Run 50MP recipe");
        hr.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new android.content.Intent(MainActivity.this, HighResActivity.class));
            }
        });
        status = new TextView(this);
        status.setText("idle");
        ScrollView sv = new ScrollView(this);
        sv.addView(status);
        ll.addView(shoot);
        ll.addView(hr);
        ll.addView(sv);
        setContentView(ll);
        if (checkSelfPermission("android.permission.CAMERA") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.CAMERA"}, 1);
        } else autoStart();
    }

    @Override public void onRequestPermissionsResult(int rc, String[] perms, int[] res) {
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) autoStart();
        else set("no camera permission");
    }

    void autoStart() { new Handler(getMainLooper()).postDelayed(new Runnable() { public void run() { start(); } }, 1200); }

    void set(final String s) {
        Log.i(TAG, s);
        try {
            File f = new File(getExternalFilesDir(null), "probe.log");
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write((s + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {}
        runOnUiThread(new Runnable() { public void run() { status.append(s + "\n"); } });
    }

    void start() {
        if (busy) { set("busy"); return; }
        busy = true;
        new Thread(new Runnable() { public void run() {
            try { sweep(); } catch (Throwable t) { set("FATAL " + Log.getStackTraceString(t)); }
            busy = false;
        }}).start();
    }

    static class C {
        String label;
        int format = ImageFormat.JPEG, w = 8160, h = 6120;
        long useCase = 0x10000L;
        boolean triple = false;
        String phys = null;
        List<String> iks = new ArrayList<String>();   // "name=value" int32
        List<String> lks = new ArrayList<String>();   // int64
        List<String> bks = new ArrayList<String>();   // byte
        int captureIntent = -1, controlMode = -1;
        C(String l) { label = l; }
        C size(int a, int b) { w = a; h = b; return this; }
        C fmt(int f) { format = f; return this; }
        C uc(long u) { useCase = u; return this; }
        C trip() { triple = true; return this; }
        C phys(String p) { phys = p; return this; }
        C ik(String n, int v) { iks.add(n + "=" + v); return this; }
        C lk(String n, long v) { lks.add(n + "=" + v); return this; }
        C bk(String n, int v) { bks.add(n + "=" + v); return this; }
        C std(int intent, int mode) { captureIntent = intent; controlMode = mode; return this; }
    }

    void sweep() throws Exception {
        mgr = (CameraManager) getSystemService(CAMERA_SERVICE);
        File d = getExternalFilesDir(null);
        if (d != null) d.mkdirs();
        String C = "samsung.android.control.";
        List<C> cases = new ArrayList<C>();

        // Post-patch sweep: Samsung's own recipe is an 8160x6120 stream created with
        // option=2 (=> streamUseCase 0x20000) plus the PDK session keys. Before the
        // filter patch those keys were dropped; now they should reach the HAL.
        cases.add(new C("P1_uc20000_keys").uc(0x20000L).ik(C+"shootingMode", 40).ik(C+"ssmShotMode", 2).ik(C+"capturePhysicalId", 56));
        cases.add(new C("P2_uc10000_keys").uc(0x10000L).ik(C+"shootingMode", 40).ik(C+"ssmShotMode", 2).ik(C+"capturePhysicalId", 56));
        cases.add(new C("P3_uc0_keys").uc(0L).ik(C+"shootingMode", 40).ik(C+"ssmShotMode", 2).ik(C+"capturePhysicalId", 56));
        cases.add(new C("P4_uc20000_apm1").uc(0x20000L).ik(C+"shootingMode", 40).ik(C+"ssmShotMode", 2).ik(C+"capturePhysicalId", 56).ik(C+"adaptivePixelMode", 1));
        cases.add(new C("P5_uc20000_allkeys").uc(0x20000L).ik(C+"shootingMode", 40).ik(C+"ssmShotMode", 2).ik(C+"capturePhysicalId", 56)
            .ik(C+"adaptivePixelMode", 1).ik(C+"fastCaptureOption", 1).ik(C+"unihalEnableMode", 1).bk(C+"samsungCamera", 1).bk(C+"samsungSDK", 1));
        cases.add(new C("P6_triple_uc20000").trip().uc(0x20000L).ik(C+"shootingMode", 40).ik(C+"ssmShotMode", 2).ik(C+"capturePhysicalId", 56));
        cases.add(new C("P7_uc20000_shooting1").uc(0x20000L).ik(C+"shootingMode", 1));
        cases.add(new C("P8_uc20000_nokeys").uc(0x20000L));
        cases.add(new C("P9_control_binned").size(4080, 3060));

        preflight();

        set("=== sweep start: " + cases.size() + " cases ===");
        for (C c : cases) {
            String r;
            try { r = runCase(c); } catch (Throwable t) { r = "EX " + t; }
            set(c.label + " -> " + r);
        }
        set("=== sweep done ===");
    }

    // Dump the session-key tags each candidate camera advertises, so the cameraserve
    // patch can be verified against the camera id the sweep actually opens (56).
    void preflight() {
        for (String id : new String[]{"0", "20", "56"}) {
            try {
                CameraCharacteristics ch = mgr.getCameraCharacteristics(id);
                int[] vsk = null;
                try {
                    vsk = ch.get(new CameraCharacteristics.Key<int[]>(
                            "samsung.android.request.availableSessionKeys", int[].class));
                } catch (Throwable t) {}
                List<CaptureRequest.Key<?>> ask = null;
                try { ask = ch.getAvailableSessionKeys(); } catch (Throwable t) {}
                StringBuilder sb = new StringBuilder("preflight cam " + id
                        + ": vendorSessionKeys=" + (vsk == null ? "ABSENT" : String.valueOf(vsk.length))
                        + " aospSessionKeys=" + (ask == null ? "null" : String.valueOf(ask.size())));
                if (vsk != null) {
                    sb.append(" [");
                    for (int i = 0; i < Math.min(vsk.length, 30); i++) {
                        sb.append(i == 0 ? "" : ",").append(Integer.toHexString(vsk[i]));
                    }
                    sb.append("]");
                }
                set(sb.toString());
            } catch (Throwable t) { set("preflight cam " + id + " FAIL " + t); }
        }
    }

    String runCase(C c) throws Exception {
        final CountDownLatch openL = new CountDownLatch(1);
        final CountDownLatch cfgL = new CountDownLatch(1);
        final CountDownLatch imgL = new CountDownLatch(1);
        final CameraDevice[] dev = new CameraDevice[1];
        final CameraCaptureSession[] sess = new CameraCaptureSession[1];
        final String[] out = new String[1];
        final String[] fail = new String[1];
        HandlerThread ht = new HandlerThread("c");
        ht.start();
        final Handler handler = new Handler(ht.getLooper());
        final File dir = getExternalFilesDir(null);

        ImageReader still = null, raw = null, prev = null;
        try {
            still = ImageReader.newInstance(c.w, c.h, c.format, 3);
            if (c.triple) {
                raw = ImageReader.newInstance(8160, 6120, ImageFormat.RAW10, 3);
                prev = ImageReader.newInstance(1920, 1440, ImageFormat.YUV_420_888, 4);
            }
        } catch (Throwable t) { ht.quit(); return "reader FAIL"; }
        final ImageReader sr = still;
        sr.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            public void onImageAvailable(ImageReader ir) {
                try {
                    Image i = ir.acquireLatestImage();
                    if (i == null) return;
                    ByteBuffer bb = i.getPlanes()[0].getBuffer();
                    byte[] buf = new byte[bb.remaining()];
                    bb.get(buf);
                    FileOutputStream fos = new FileOutputStream(new File(dir, "s_" + c.label + ".jpg"));
                    fos.write(buf); fos.close();
                    out[0] = "declared=" + i.getWidth() + "x" + i.getHeight() + " " + jpegSize(buf);
                    i.close(); imgL.countDown();
                } catch (Throwable t) { out[0] = "cb " + t; imgL.countDown(); }
            }
        }, handler);
        if (raw != null) {
            final ImageReader rr = raw;
            rr.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                public void onImageAvailable(ImageReader ir) {
                    try { Image i = ir.acquireLatestImage(); if (i != null) { set("    RAW arrived " + i.getWidth() + "x" + i.getHeight()); i.close(); } } catch (Throwable t) {}
                }
            }, handler);
        }
        if (prev != null) {
            final ImageReader pr = prev;
            pr.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                public void onImageAvailable(ImageReader ir) {
                    try { Image i = ir.acquireLatestImage(); if (i != null) i.close(); } catch (Throwable t) {}
                }
            }, handler);
        }

        String camId = (c.phys != null) ? "20" : "56";
        try {
            mgr.openCamera(camId, new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice d) { dev[0] = d; openL.countDown(); }
                public void onDisconnected(CameraDevice d) { fail[0] = "disconnected"; openL.countDown(); }
                public void onError(CameraDevice d, int e) { fail[0] = "open err " + e; openL.countDown(); }
            }, handler);
        } catch (Throwable t) { ht.quit(); return "open throw"; }
        if (!openL.await(5, TimeUnit.SECONDS) || dev[0] == null) { ht.quit(); return "open fail " + fail[0]; }

        try {
            OutputConfiguration oc = new OutputConfiguration(sr.getSurface());
            if (c.useCase >= 0) oc.setStreamUseCase(c.useCase);
            if (c.phys != null) oc.setPhysicalCameraId(c.phys);
            List<OutputConfiguration> ocs = new ArrayList<OutputConfiguration>();
            ocs.add(oc);
            if (raw != null) {
                OutputConfiguration or = new OutputConfiguration(raw.getSurface());
                or.setStreamUseCase(c.useCase);
                ocs.add(or);
            }
            if (prev != null) ocs.add(new OutputConfiguration(prev.getSurface()));

            SessionConfiguration sc = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, ocs,
                new Executor() { public void execute(Runnable r2) { handler.post(r2); } },
                new CameraCaptureSession.StateCallback() {
                    public void onConfigured(CameraCaptureSession s) { sess[0] = s; cfgL.countDown(); }
                    public void onConfigureFailed(CameraCaptureSession s) { fail[0] = "cfg failed"; cfgL.countDown(); }
                });
            CaptureRequest.Builder sb = dev[0].createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            apply(sb, c);
            sc.setSessionParameters(sb.build());
            dev[0].createCaptureSession(sc);
        } catch (Throwable t) { try { dev[0].close(); } catch (Throwable t2) {} ht.quit(); return "session throw " + t.getMessage(); }
        if (!cfgL.await(6, TimeUnit.SECONDS) || sess[0] == null) {
            try { dev[0].close(); } catch (Throwable t) {}
            ht.quit();
            return fail[0] == null ? "cfg timeout" : fail[0];
        }

        try {
            CaptureRequest.Builder rb = dev[0].createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            rb.addTarget(sr.getSurface());
            if (raw != null) rb.addTarget(raw.getSurface());
            apply(rb, c);
            sess[0].capture(rb.build(), null, handler);
        } catch (Throwable t) { }

        boolean got = imgL.await(8, TimeUnit.SECONDS);
        try { sess[0].close(); } catch (Throwable t) {}
        try { dev[0].close(); } catch (Throwable t) {}
        try { sr.close(); } catch (Throwable t) {}
        if (raw != null) { try { raw.close(); } catch (Throwable t) {} }
        if (prev != null) { try { prev.close(); } catch (Throwable t) {} }
        ht.quit();
        return got ? out[0] : "no image";
    }

    void apply(CaptureRequest.Builder b, C c) {
        String C = "samsung.android.control.";
        for (String s : c.iks) {
            String[] kv = s.split("=");
            try { b.set(new CaptureRequest.Key<Integer>(kv[0], Integer.class), Integer.valueOf(Integer.parseInt(kv[1]))); }
            catch (Throwable t) { set("    ! int key " + kv[0] + " failed"); }
        }
        for (String s : c.lks) {
            String[] kv = s.split("=");
            try { b.set(new CaptureRequest.Key<Long>(kv[0], Long.class), Long.valueOf(Long.parseLong(kv[1]))); }
            catch (Throwable t) { set("    ! long key " + kv[0] + " failed"); }
        }
        for (String s : c.bks) {
            String[] kv = s.split("=");
            try { b.set(new CaptureRequest.Key<Byte>(kv[0], Byte.class), Byte.valueOf((byte) Integer.parseInt(kv[1]))); }
            catch (Throwable t) { set("    ! byte key " + kv[0] + " failed"); }
        }
        if (c.captureIntent >= 0) {
            try { b.set(CaptureRequest.CONTROL_CAPTURE_INTENT, Integer.valueOf(c.captureIntent)); } catch (Throwable t) {}
        }
        if (c.controlMode >= 0) {
            try { b.set(CaptureRequest.CONTROL_MODE, Integer.valueOf(c.controlMode)); } catch (Throwable t) {}
        }
    }

    String jpegSize(byte[] b) {
        int i = 2;
        while (i + 9 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) { i++; continue; }
            int m = b[i + 1] & 0xFF;
            if (m == 0xC0 || m == 0xC2) {
                int h = ((b[i + 5] & 0xFF) << 8) | (b[i + 6] & 0xFF);
                int w = ((b[i + 7] & 0xFF) << 8) | (b[i + 8] & 0xFF);
                return "actual=" + w + "x" + h;
            }
            int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            i += 2 + len;
        }
        return "?";
    }
}
