# 👁️ Android-EyeMouse (OptiKey Gaze)

Hands-free eye-tracking mouse and accessibility controller for Android, powered by **MediaPipe Face Landmarker**, **CameraX**, and Android **Accessibility Services**.

## ✨ Features
* **Real-Time Iris & Head Tracking:** Uses the front camera and 478 facial landmarks to map gaze coordinates onto the screen.
* **15-Point Auto-Calibration:** Median-filtered calibration saved locally on the device.
* **Smart Button Snap (Magnetic Cursor):** Detects clickable UI elements via Accessibility nodes and snaps the cursor to their center to prevent misclicks.
* **Customizable Dwell-Time Click & Auto-Scroll:** Configurable fixation timer (default 2.0s) for hands-free tapping and edge scrolling.
* **Gaze Quick-Action Dock:** Side overlay with hands-free controls for **Pause/Resume**, **Back**, **Home**, and **Recalibrate**.

## 📥 Download & Test
1. Go to the **[Releases tab](https://github.com/Giorgx12/Android-EyeMouse/releases)**) and download the latest `OptiKeyAndroid-APK` build.
2. Install `app-debug.apk` on your Android device.
3. Grant **Camera** permission and enable **OptiKey Gaze** in *Settings → Accessibility*.
