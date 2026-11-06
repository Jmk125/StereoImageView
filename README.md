# Stereo Viewer

Landscape-only Android app that shows two photos side by side for stereoscopic (3D) viewing.

- **Tap** a half to pick that image from your gallery.
- **Long-press** a half for: rotate left, rotate right, **Save aligned image**, or choose a different image.
- "Save aligned image" writes the image with EXIF orientation and your rotation baked in (full resolution, capped at 6000px on the long side) to `Pictures/StereoImageView`.

Build: `./gradlew assembleDebug` (APK in `app/build/outputs/apk/debug/`). The GitHub Actions workflow also builds it and uploads the APK as an artifact.
