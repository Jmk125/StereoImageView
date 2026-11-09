# Stereo Viewer

Landscape-only Android app that shows two photos side by side for stereoscopic (3D) viewing.

- **Tap** a half to pick that image from your gallery.
- The halves touch with no gap, and each image fills its half.
- **Drag** to pan and **pinch** to zoom. By default both images move together; long-press and choose the link toggle to adjust just one image for fine alignment.
- **Long-press** a half for: rotate left/right, **Save aligned image**, link toggle, reset, or choose a different image.
- "Save aligned image" saves exactly what that half shows (rotation, zoom, pan) at full photo resolution (capped at 6000px) to `Pictures/StereoImageView`.

Build: `./gradlew assembleDebug` (APK in `app/build/outputs/apk/debug/`). The GitHub Actions workflow also builds it and uploads the APK as an artifact.

- Long-press > "Image size" shrinks the image pair toward the center of the screen (100% down to 40%) so your eyes need less travel to fuse them. The choice is remembered.
- Tap a half and select **two** photos at once in the picker: the first goes to the half you tapped, the second to the other half. Long-press > "Swap left and right images" flips them.
- Tapping uses the system photo picker (checkboxes, pick two). Long-press > "Browse files" uses the file picker, which reopens in the folder of the last photo chosen that way.
