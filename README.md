# LowLauncher (Android 4.0.4, API 15)

**English** · [Русский](README.ru.md)

## About
LowLauncher is a lightweight launcher for Android 4.0.4 written in plain Java with no external libraries. Package: `com.m929.lowlauncher`.

- **Home screen:** 4x4 / 4x5 grid, 5 pages, drag & drop of shortcuts and system widgets (pick, place, resize, remove), long-press menu (widgets, wallpaper, settings), swipe down opens the notification shade.
- **Dock:** exactly 5 slots shared by all pages, no drawer button, empty slots allowed.
- **App drawer:** swipe up from anywhere, translucent background, search, configurable number of columns.
- **Drag & drop:** "App info" and "Remove" zones, edge auto-scroll between pages.
- **Settings:** grid size, drawer columns, icon pack (ADW/Nova/Apex/Go), label font size.
- **Localization:** English, Russian and 44 more languages available in Android 4.0.4.
- **Build:** Gradle, R8, hardware acceleration.

## Build
Requires JDK 17+ and the Android SDK with platform 34 and build-tools (installed by Android Studio).

    ./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Release builds use R8 (minify + shrinkResources, rules in app/proguard-rules.pro).
Gradle 8.7 (wrapper) + AGP 8.5.2; use `./gradlew` (system Gradle 9 does not work with AGP 8.5).
`compileSdk 34` is for the build tools only; `minSdk = targetSdk = 15`.

Check that the code uses only API 15 (needs the android-15 android.jar):

    scripts/check-api15.sh /path/to/android-15/android.jar

After installing, press Home and choose "LowLauncher" -> "Always".

## Usage
- Swipe down on the home screen: notification shade.
- Swipe up anywhere on the home screen or Dock: app drawer; swipe down (list at top) or Back closes it.
- Long-press an empty area: "Add widget" / "Wallpaper" / "Settings".
- Settings: home grid (4x4 ... 5x6), drawer columns, icon pack (any installed ADW/Nova/Apex/Go pack; the pack's back/mask is applied to apps it lacks), label font size.
- The search field at the top of the drawer hides non-matching apps as you type.
- Long-press an icon or widget to drag it (screen edges flip pages). Drawer icons can be dropped on the home screen or Dock.
- A placed widget gets a frame: edge handles resize it by cells, the red button inside removes it.
- The Dock has 5 slots; on first run it is filled (phone, SMS, browser, camera, music), afterwards slots may stay empty. A Dock icon can be moved to the home screen, to "Remove", or replaced.
- While dragging, two zones appear at the top: "App info" and "Remove".

## Structure (app/src/main/java/com/m929/lowlauncher)
- `Launcher` - activity: model, widgets (AppWidgetHost), menus, drop operations.
- `DragLayer` - root layer: drawer gesture and drag & drop.
- `Workspace` + `CellLayout` - 5 pages, 4x5 grid (4x4 on screens shorter than 590dp).
- `Dock`, `AppDrawer`, `IconView`, `PageDots`, `ResizeFrame`, `HomeWidgetView`.
- `LauncherModel` (PackageManager, icons scaled to launcher icon size), `Store` (SharedPreferences).

## Status
Generated with Claude (Anthropic). Tested on a real device.
