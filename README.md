# Stereo Viewer

Landscape-only Android app that shows two photos side by side for stereoscopic (3D) viewing.

- **Tap** a half to pick that image from your gallery.
- The halves touch with no gap, and each image fills its half.
- **Drag** to pan and **pinch** to zoom. By default both images move together; long-press and choose the link toggle to adjust just one image for fine alignment.
- **Long-press** a half for: rotate left/right, **Save aligned image**, link toggle, reset, or choose a different image.
- "Save aligned image" saves exactly what that half shows (rotation, zoom, pan) at full photo resolution (capped at 6000px) to `Pictures/StereoImageView`.

Build: `./gradlew assembleDebug` (APK in `app/build/outputs/apk/debug/`). The GitHub Actions workflow also builds it and uploads the APK as an artifact.
