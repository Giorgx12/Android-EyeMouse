package com.optikey.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GazeAccessibilityService extends AccessibilityService implements LifecycleOwner {

    public static GazeAccessibilityService instance = null;
    private LifecycleRegistry lifecycleRegistry;
    private WindowManager windowManager;
    private GazeOverlayView overlayView;
    private FaceLandmarker faceLandmarker;
    private ExecutorService cameraExecutor;

    private int sw = 1080, sh = 2400;
    private static final int CAL_PTS = 15;
    private int calStep = 0;
    private float calProg = 0f, dwellProg = 0f;
    private boolean faceOk = false, initEye = false;
    private double rawSmX = 0, rawSmY = 0, smX = 500, smY = 500, anchorX = 500, anchorY = 500;
    private long calTimer = 0, dwellTimer = 0, lastActionTime = 0, lastScrollTime = 0;

    private final List<double[]> calTargets = new ArrayList<>();
    private final List<double[]> calEye = new ArrayList<>();
    private final List<double[]> stepSamples = new ArrayList<>();
    private final List<Double> calX = new ArrayList<>();
    private final List<Double> calY = new ArrayList<>();

    static class LinearGaze {
        double ax = 0, bx = 0, cx = 0, ay = 0, by = 0, cy = 0;
        boolean ready = false;

        void train(List<double[]> e, List<Double> sx, List<Double> sy) {
            int n = e.size();
            if (n < 4) return;
            double sX = 0, sY = 0, sXX = 0, sYY = 0, sXY = 0, s1 = n;
            double bX0 = 0, bX1 = 0, bX2 = 0, bY0 = 0, bY1 = 0, bY2 = 0;
            for (int i = 0; i < n; i++) {
                double x = e.get(i)[0], y = e.get(i)[1];
                sX += x; sY += y; sXX += x * x; sYY += y * y; sXY += x * y;
                bX0 += sx.get(i); bX1 += x * sx.get(i); bX2 += y * sx.get(i);
                bY0 += sy.get(i); bY1 += x * sy.get(i); bY2 += y * sy.get(i);
            }
            sXX += 1e-4; sYY += 1e-4;
            double[] rx = solve3(s1, sX, sY, sXX, sXY, sYY, bX0, bX1, bX2);
            double[] ry = solve3(s1, sX, sY, sXX, sXY, sYY, bY0, bY1, bY2);
            if (rx != null && ry != null) {
                cx = rx[0]; ax = rx[1]; bx = rx[2];
                cy = ry[0]; ay = ry[1]; by = ry[2];
                ready = true;
            }
        }

        private double[] solve3(double a00, double a01, double a02, double a11, double a12, double a22, double r0, double r1, double r2) {
            double det = a00 * (a11 * a22 - a12 * a12) - a01 * (a01 * a22 - a12 * a02) + a02 * (a01 * a12 - a11 * a02);
            if (Math.abs(det) < 1e-12) return null;
            double c0 = (r0 * (a11 * a22 - a12 * a12) - a01 * (r1 * a22 - a12 * r2) + a02 * (r1 * a12 - a11 * r2)) / det;
            double c1 = (a00 * (r1 * a22 - a12 * r2) - r0 * (a01 * a22 - a12 * a02) + a02 * (a01 * r2 - r1 * a02)) / det;
            double c2 = (a00 * (a11 * r2 - r1 * a12) - a01 * (a01 * r2 - r1 * a02) + r0 * (a01 * a12 - a11 * a02)) / det;
            return new double[]{c0, c1, c2};
        }

        double[] predict(double x, double y) {
            return new double[]{ax * x + bx * y + cx, ay * x + by * y + cy};
        }
    }

    private final LinearGaze mapper = new LinearGaze();

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        sw = dm.widthPixels;
        sh = dm.heightPixels;
        smX = sw * 0.5;
        smY = sh * 0.5;

        double[] mx = {0.08, 0.29, 0.50, 0.71, 0.92};
        double[] my = {0.08, 0.50, 0.92};
        for (double y : my) {
            for (double x : mx) {
                calTargets.add(new double[]{sw * x, sh * y});
            }
        }

        if (loadOpti()) calStep = CAL_PTS;

        overlayView = new GazeOverlayView(this);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(overlayView, params);

        initMediaPipe();
        cameraExecutor = Executors.newSingleThreadExecutor();
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
        startCamera();
    }

    public void startCalibration() {
        calStep = 0;
        initEye = false;
        calEye.clear();
        calX.clear();
        calY.clear();
        stepSamples.clear();
        calTimer = System.currentTimeMillis();
        overlayView.postInvalidate();
    }

    private void saveOpti() {
        if (!mapper.ready) return;
        SharedPreferences sp = getSharedPreferences("optikey_cal", MODE_PRIVATE);
        sp.edit()
                .putString("ax", String.valueOf(mapper.ax))
                .putString("bx", String.valueOf(mapper.bx))
                .putString("cx", String.valueOf(mapper.cx))
                .putString("ay", String.valueOf(mapper.ay))
                .putString("by", String.valueOf(mapper.by))
                .putString("cy", String.valueOf(mapper.cy))
                .apply();
    }

    private boolean loadOpti() {
        SharedPreferences sp = getSharedPreferences("optikey_cal", MODE_PRIVATE);
        if (!sp.contains("ax")) return false;
        try {
            mapper.ax = Double.parseDouble(sp.getString("ax", "0"));
            mapper.bx = Double.parseDouble(sp.getString("bx", "0"));
            mapper.cx = Double.parseDouble(sp.getString("cx", "0"));
            mapper.ay = Double.parseDouble(sp.getString("ay", "0"));
            mapper.by = Double.parseDouble(sp.getString("by", "0"));
            mapper.cy = Double.parseDouble(sp.getString("cy", "0"));
            mapper.ready = true;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void initMediaPipe() {
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath("face_landmarker.task")
                    .build();
            FaceLandmarker.FaceLandmarkerOptions options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(1)
                    .build();
            faceLandmarker = FaceLandmarker.createFromOptions(this, options);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::processFrame);
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processFrame(@NonNull ImageProxy image) {
        if (faceLandmarker == null) {
            image.close();
            return;
        }
        try {
            android.graphics.Bitmap rawBmp = image.toBitmap();
            Matrix m = new Matrix();
            m.postRotate(image.getImageInfo().getRotationDegrees());
            m.postScale(-1f, 1f);
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    rawBmp, 0, 0, rawBmp.getWidth(), rawBmp.getHeight(), m, true);

            MPImage mpImage = new BitmapImageBuilder(bmp).build();
            FaceLandmarkerResult res = faceLandmarker.detect(mpImage);

            if (res != null && !res.faceLandmarks().isEmpty() && res.faceLandmarks().get(0).size() >= 478) {
                faceOk = true;
                List<NormalizedLandmark> lm = res.faceLandmarks().get(0);

                double lOutX = lm.get(33).x(), lOutY = lm.get(33).y();
                double lInX = lm.get(133).x(), lInY = lm.get(133).y();
                double lIrisX = lm.get(468).x(), lIrisY = lm.get(468).y();

                double rInX = lm.get(362).x(), rInY = lm.get(362).y();
                double rOutX = lm.get(263).x(), rOutY = lm.get(263).y();
                double rIrisX = lm.get(473).x(), rIrisY = lm.get(473).y();

                double noseX = lm.get(1).x(), noseY = lm.get(1).y();

                double lSpan = Math.max(0.01, Math.hypot(lInX - lOutX, lInY - lOutY));
                double rSpan = Math.max(0.01, Math.hypot(rOutX - rInX, rOutY - rInY));

                double combX = (((lIrisX - (lOutX + lInX) * 0.5) / lSpan + (rIrisX - (rInX + rOutX) * 0.5) / rSpan) * 0.5) * 0.80 + noseX * 0.20;
                double combY = (((lIrisY - (lOutY + lInY) * 0.5) / lSpan + (rIrisY - (rInY + rOutY) * 0.5) / rSpan) * 0.5) * 0.80 + noseY * 0.35;

                if (!initEye) {
                    rawSmX = combX;
                    rawSmY = combY;
                    initEye = true;
                } else {
                    rawSmX = rawSmX * 0.78 + combX * 0.22;
                    rawSmY = rawSmY * 0.78 + combY * 0.22;
                }

                long now = System.currentTimeMillis();
                if (calStep < CAL_PTS) {
                    if (calTimer == 0) calTimer = now;
                    long dt = now - calTimer;
                    if (dt < 900) {
                        calProg = 0f;
                    } else if (dt < 1800) {
                        calProg = (dt - 900) / 900f;
                        stepSamples.add(new double[]{rawSmX, rawSmY});
                    } else {
                        if (!stepSamples.isEmpty()) {
                            double sx = 0, sy = 0;
                            for (double[] p : stepSamples) {
                                sx += p[0];
                                sy += p[1];
                            }
                            calEye.add(new double[]{sx / stepSamples.size(), sy / stepSamples.size()});
                            calX.add(calTargets.get(calStep)[0]);
                            calY.add(calTargets.get(calStep)[1]);
                            stepSamples.clear();
                            calStep++;
                            calProg = 0f;
                            if (calStep == CAL_PTS) {
                                mapper.train(calEye, calX, calY);
                                saveOpti();
                            }
                        }
                        calTimer = now;
                    }
                } else if (mapper.ready) {
                    double[] pred = mapper.predict(rawSmX, rawSmY);
                    double tx = Math.max(0.0, Math.min(sw - 1, pred[0]));
                    double ty = Math.max(0.0, Math.min(sh - 1, pred[1]));
                    smX += (tx - smX) * 0.20;
                    smY += (ty - smY) * 0.20;

                    if (now - lastScrollTime > 800) {
                        if (smY < 80) {
                            performSwipe(sw / 2f, sh * 0.3f, sw / 2f, sh * 0.7f);
                            lastScrollTime = now;
                        } else if (smY > sh - 80) {
                            performSwipe(sw / 2f, sh * 0.7f, sw / 2f, sh * 0.3f);
                            lastScrollTime = now;
                        }
                    }

                    if (Math.hypot(smX - anchorX, smY - anchorY) > 55.0) {
                        anchorX = smX;
                        anchorY = smY;
                        dwellTimer = now;
                        dwellProg = 0f;
                    } else {
                        long elapsed = now - dwellTimer;
                        dwellProg = Math.min(1f, elapsed / 1000f);
                        if (elapsed >= 1000 && (now - lastActionTime > 1400)) {
                            lastActionTime = now;
                            dwellTimer = now;
                            dwellProg = 0f;
                            performTap((float) smX, (float) smY);
                        }
                    }
                }
            } else {
                faceOk = false;
            }
            overlayView.postInvalidate();
        } finally {
            image.close();
        }
    }

    private void performTap(float x, float y) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
            dispatchGesture(builder.build(), null, null);
        });
    }

    private void performSwipe(float x1, float y1, float x2, float y2) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Path path = new Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 300));
            dispatchGesture(builder.build(), null, null);
        });
    }

    private class GazeOverlayView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public GazeOverlayView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (calStep < CAL_PTS) {
                canvas.drawColor(Color.argb(210, 12, 14, 20));
                paint.setColor(Color.WHITE);
                paint.setTextSize(42f);
                paint.setTextAlign(Paint.Align.CENTER);
                String msg = !faceOk ? "ALLINEA IL VISO NELLA FOTOCAMERA"
                        : "CALIBRAZIONE " + (calStep + 1) + "/15 • Fissa il bersaglio rosso";
                canvas.drawText(msg, sw / 2f, 140f, paint);

                float tx = (float) calTargets.get(calStep)[0];
                float ty = (float) calTargets.get(calStep)[1];

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.rgb(244, 63, 94));
                canvas.drawCircle(tx, ty, 34f, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(8f);
                paint.setColor(Color.rgb(52, 211, 153));
                canvas.drawArc(new RectF(tx - 46f, ty - 46f, tx + 46f, ty + 46f), -90f, 360f * calProg, false, paint);

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.WHITE);
                canvas.drawCircle(tx, ty, 8f, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(6f);
                paint.setColor(faceOk ? Color.rgb(56, 189, 248) : Color.RED);
                canvas.drawCircle((float) smX, (float) smY, 28f, paint);

                if (dwellProg > 0.05f) {
                    paint.setColor(Color.rgb(52, 211, 153));
                    canvas.drawArc(new RectF((float) smX - 36f, (float) smY - 36f, (float) smX + 36f, (float) smY + 36f),
                            -90f, 360f * dwellProg, false, paint);
                }
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        if (overlayView != null) windowManager.removeView(overlayView);
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }
}
