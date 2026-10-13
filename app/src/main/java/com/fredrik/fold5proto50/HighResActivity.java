package com.fredrik.fold5proto50;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
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
import android.view.WindowManager;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Reproduces the One UI 50 MP recipe through the public Camera2 API; the patched
 * cameraserver injects Samsung's PDK parameter string for high-resolution streams.
 *
 * Streams (Samsung's OutputConfiguration "option" rides in streamUseCase bits 16+):
 *   RAW10 8160x6120  useCase 0x20000  (option 2 = high resolution)
 *   JPEG  8160x6120  useCase 0x20000
 *   YUV   1920x1440  useCase 0x10000  (option 1 = preview)
 * The repeating request and the still carry shootingMode=40, ssmShotMode=2 and
 * capturePhysicalId=56; the still fires on raw+jpeg after a few preview frames.
 */
public class HighResActivity extends Activity {
    static final String TAG = "HIGHRES50";
    static final int W = 8160, H = 6120;
    static final long OPT_HR = 0x20000L;
    static final long OPT_PREVIEW = 0x10000L;
    static final String CAM_ID = "56";
    static final int PREVIEWS_BEFORE_STILL = 8;

    TextView status;
    volatile boolean busy = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status = new TextView(this);
        status.setTextSize(11f);
        setContentView(status);
        if (checkSelfPermission("android.permission.CAMERA") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.CAMERA"}, 1);
        } else {
            new Handler(getMainLooper()).postDelayed(new Runnable() {
                public void run() { start(); }
            }, 1000);
        }
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] res) {
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) start();
        else set("no camera permission");
    }

    void set(final String s) {
        Log.i(TAG, s);
        try {
            File f = new File(getExternalFilesDir(null), "highres.log");
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
            try { capture(); } catch (Throwable t) { set("FATAL " + t); }
            busy = false;
        }}).start();
    }

    static int jpegWidth(byte[] buf) {
        int i = 2;
        while (i + 9 < buf.length) {
            if ((buf[i] & 0xff) != 0xff) { i++; continue; }
            int marker = buf[i + 1] & 0xff;
            if (marker == 0xd8 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) { i += 2; continue; }
            int len = ((buf[i + 2] & 0xff) << 8) | (buf[i + 3] & 0xff);
            if (marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
                return ((buf[i + 7] & 0xff) << 8) | (buf[i + 8] & 0xff);
            }
            i += 2 + len;
        }
        return -1;
    }

    static int jpegHeight(byte[] buf) {
        int i = 2;
        while (i + 9 < buf.length) {
            if ((buf[i] & 0xff) != 0xff) { i++; continue; }
            int marker = buf[i + 1] & 0xff;
            if (marker == 0xd8 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) { i += 2; continue; }
            int len = ((buf[i + 2] & 0xff) << 8) | (buf[i + 3] & 0xff);
            if (marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
                return ((buf[i + 5] & 0xff) << 8) | (buf[i + 6] & 0xff);
            }
            i += 2 + len;
        }
        return -1;
    }

    void capture() throws Exception {
        final CameraManager mgr = (CameraManager) getSystemService(CAMERA_SERVICE);
        final File dir = getExternalFilesDir(null);
        final HandlerThread ht = new HandlerThread("hr");
        ht.start();
        final Handler handler = new Handler(ht.getLooper());
        final Executor exec = new Executor() {
            public void execute(Runnable r) { handler.post(r); }
        };

        set("=== 50MP recipe start, camera " + CAM_ID + " ===");
        set("session streams: RAW10 " + W + "x" + H + " useCase 0x" + Long.toHexString(OPT_HR)
                + ", JPEG " + W + "x" + H + " useCase 0x" + Long.toHexString(OPT_HR)
                + ", YUV 1920x1440 useCase 0x" + Long.toHexString(OPT_PREVIEW));

        final ImageReader raw = ImageReader.newInstance(W, H, ImageFormat.RAW10, 3);
        final ImageReader jpg = ImageReader.newInstance(W, H, ImageFormat.JPEG, 2);
        final ImageReader prev = ImageReader.newInstance(1920, 1440, ImageFormat.YUV_420_888, 4);

        final Object lock = new Object();
        final int[] previews = new int[1];
        final boolean[] fired = new boolean[1];
        final CameraCaptureSession[] sess = new CameraCaptureSession[1];
        final CameraDevice[] dev = new CameraDevice[1];

        jpg.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            public void onImageAvailable(ImageReader ir) {
                try {
                    Image i = ir.acquireLatestImage();
                    if (i == null) return;
                    ByteBuffer bb = i.getPlanes()[0].getBuffer();
                    byte[] buf = new byte[bb.remaining()];
                    bb.get(buf);
                    File f = new File(dir, "highres.jpg");
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(buf);
                    fos.close();
                    set("JPEG saved " + buf.length + " bytes, SOF " + jpegWidth(buf) + "x" + jpegHeight(buf));
                    i.close();
                } catch (Throwable t) { set("jpeg cb " + t); }
                synchronized (lock) { lock.notifyAll(); }
            }
        }, handler);

        raw.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            public void onImageAvailable(ImageReader ir) {
                try {
                    Image i = ir.acquireLatestImage();
                    if (i == null) return;
                    int rowStride = i.getPlanes()[0].getRowStride();
                    int bytes = i.getPlanes()[0].getBuffer().remaining();
                    set("RAW10 arrived " + i.getWidth() + "x" + i.getHeight() + " rowStride=" + rowStride
                            + " bytes=" + bytes);
                    i.close();
                } catch (Throwable t) { set("raw cb " + t); }
                synchronized (lock) { lock.notifyAll(); }
            }
        }, handler);

        final CameraDevice.StateCallback openCb = new CameraDevice.StateCallback() {
            public void onOpened(CameraDevice d) {
                dev[0] = d;
                synchronized (lock) { lock.notifyAll(); }
            }
            public void onDisconnected(CameraDevice d) { set("camera disconnected"); synchronized (lock) { lock.notifyAll(); } }
            public void onError(CameraDevice d, int e) { set("camera open error " + e); synchronized (lock) { lock.notifyAll(); } }
        };
        mgr.openCamera(CAM_ID, openCb, handler);
        synchronized (lock) { lock.wait(6000); }
        if (dev[0] == null) { set("open failed"); return; }
        set("camera " + CAM_ID + " opened");

        List<OutputConfiguration> ocs = new ArrayList<OutputConfiguration>();
        OutputConfiguration or = new OutputConfiguration(raw.getSurface());
        or.setStreamUseCase(OPT_HR);
        ocs.add(or);
        OutputConfiguration oj = new OutputConfiguration(jpg.getSurface());
        oj.setStreamUseCase(OPT_HR);
        ocs.add(oj);
        OutputConfiguration op = new OutputConfiguration(prev.getSurface());
        op.setStreamUseCase(OPT_PREVIEW);
        ocs.add(op);

        final CameraCaptureSession.StateCallback cfgCb = new CameraCaptureSession.StateCallback() {
            public void onConfigured(CameraCaptureSession s) {
                sess[0] = s;
                synchronized (lock) { lock.notifyAll(); }
            }
            public void onConfigureFailed(CameraCaptureSession s) {
                set("session configure FAILED");
                synchronized (lock) { lock.notifyAll(); }
            }
        };
        dev[0].createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, ocs, exec, cfgCb));
        synchronized (lock) { lock.wait(8000); }
        if (sess[0] == null) { set("no session"); return; }
        set("session configured");

        CaptureRequest.Builder pb = dev[0].createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        pb.addTarget(prev.getSurface());
        try {
            pb.set(new CaptureRequest.Key<Integer>("samsung.android.control.shootingMode", Integer.class), 40);
            pb.set(new CaptureRequest.Key<Integer>("samsung.android.control.ssmShotMode", Integer.class), 2);
            pb.set(new CaptureRequest.Key<Integer>("samsung.android.control.capturePhysicalId", Integer.class), 56);
        } catch (Throwable t) { set("vendor key set failed: " + t); }

        final CaptureRequest.Builder sb = dev[0].createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        sb.addTarget(raw.getSurface());
        sb.addTarget(jpg.getSurface());
        try {
            sb.set(new CaptureRequest.Key<Integer>("samsung.android.control.shootingMode", Integer.class), 40);
            sb.set(new CaptureRequest.Key<Integer>("samsung.android.control.ssmShotMode", Integer.class), 2);
            sb.set(new CaptureRequest.Key<Integer>("samsung.android.control.capturePhysicalId", Integer.class), 56);
        } catch (Throwable t) {}

        sess[0].setRepeatingRequest(pb.build(), new CameraCaptureSession.CaptureCallback() {
            public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                int n;
                synchronized (previews) { n = ++previews[0]; }
                if (n == 1) set("preview frames flowing");
                if (n >= PREVIEWS_BEFORE_STILL && !fired[0]) {
                    fired[0] = true;
                    try {
                        set("firing still capture after " + n + " previews");
                        s.capture(sb.build(), new CameraCaptureSession.CaptureCallback() {
                            public void onCaptureCompleted(CameraCaptureSession s2, CaptureRequest r2, TotalCaptureResult res2) {
                                set("still completed");
                                synchronized (lock) { lock.notifyAll(); }
                            }
                        }, handler);
                    } catch (Throwable t) { set("still failed: " + t); }
                }
            }
        }, handler);
        set("repeating preview request set");

        synchronized (lock) { lock.wait(25000); }
        set("=== done ===");
        try { sess[0].close(); } catch (Throwable t) {}
        try { dev[0].close(); } catch (Throwable t) {}
        ht.quitSafely();
    }
}
