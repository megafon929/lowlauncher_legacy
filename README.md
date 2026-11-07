# LowLauncher v1.2 (Android 4.0.4 – 7.1, API 15–25)

**English** · [Русский](README.ru.md)

## About
LowLauncher is a lightweight launcher for Android 4.0.4 – 7.1 written in plain Java with no external libraries. Package: `com.m929.lowlauncher`.

- **Home screen:** configurable grid (4x4 … 5x6; 4x5 by default, 4x4 on short screens), 5 pages, drag & drop of shortcuts and system widgets (pick, place, resize, remove), long-press menu (widgets, wallpaper, settings), swipe down opens the notification shade.
- **Folders (new in 1.1):** drop an icon onto another icon to create a folder, or onto a folder to add it. Tap a folder to open it, rename it in the title field, long-press an icon inside to drag it out (to the home screen, the Dock or "Remove"). A folder with a single app turns back into a plain icon.
- **Dock:** exactly 5 slots shared by all pages, no drawer button, empty slots allowed.
- **App drawer:** swipe up from anywhere, translucent background, search, configurable number of columns.
- **Drag & drop:** "App info" and "Remove" zones, edge auto-scroll between pages.
- **Settings:** grid size, drawer columns, icon pack (ADW/Nova/Apex/Go), label font size.
- **Compatibility:** minSdk 15 (Android 4.0.3/4.0.4), targetSdk 25 (Android 7.1). On Android 4.1+ widgets are chosen from the launcher's own list and bound through AppWidgetManager; widgets are told their size.
- **Localization:** English, Russian and 44 more languages available in Android 4.0.4 (47 locales, see "Languages").
- **Build:** Gradle, R8, hardware acceleration.

## Build
Requires JDK 17+ and the Android SDK with platform 34 and build-tools (installed by Android Studio).

    ./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Running from Android Studio: the launcher is a home screen and has no LAUNCHER entry, so the default "Run" fails with "Default Activity not found". In Run → Edit Configurations → Launch Options choose "Specified Activity" and `com.m929.lowlauncher.Launcher` (or "Nothing" to only install), then press Home on the device and pick LowLauncher.

Release builds use R8 (minify + shrinkResources, rules in app/proguard-rules.pro).
Gradle 8.7 (wrapper) + AGP 8.5.2; use `./gradlew` (system Gradle 9 does not work with AGP 8.5).
`compileSdk 34` is for the build tools only; `minSdk = 15`, `targetSdk = 25`.

Check that the code compiles against a platform android.jar (android-25 recommended):

    scripts/check-api.sh /path/to/android-25/android.jar

Calls to API 16+ are guarded by `Build.VERSION.SDK_INT`, so compiling against the android-15 jar reports only those guarded calls.

After installing, press Home and choose "LowLauncher" -> "Always".

## Usage
- Swipe down on the home screen: notification shade.
- Swipe up anywhere on the home screen or Dock: app drawer; swipe down (list at top) or Back closes it.
- Long-press an empty area: "Add widget" / "Wallpaper" / "Settings".
- Settings: home grid (4x4 ... 5x6), drawer columns, icon pack (any installed ADW/Nova/Apex/Go pack; the pack's back/mask is applied to apps it lacks), label font size.
- The search field at the top of the drawer hides non-matching apps as you type.
- Long-press an icon, folder or widget to drag it (screen edges flip pages). Drawer icons can be dropped on the home screen, the Dock, or onto another icon/folder.
- Folders: drop an icon onto the middle of another icon to make a folder; tap it to open; edit the name at the top; long-press an icon inside to take it out.
- A placed widget gets a frame: edge handles resize it by cells, the red button inside removes it.
- The Dock has 5 slots; on first run it is filled (phone, SMS, browser, camera, music), afterwards slots may stay empty. A Dock icon can be moved to the home screen, to "Remove", or replaced.
- While dragging, two zones appear at the top: "App info" and "Remove".

## Structure (app/src/main/java/com/m929/lowlauncher)
- `Launcher` - activity: model, widgets (AppWidgetHost), folders, menus, drop operations.
- `DragLayer` - root layer: drawer gesture and drag & drop.
- `Workspace` + `CellLayout` - 5 pages with a configurable grid (default 4x5, 4x4 on screens shorter than 590dp).
- `Dock`, `AppDrawer`, `IconView`, `PageDots`, `ResizeFrame`, `HomeWidgetView`, `FolderPopup` (open folder), `FolderDrawable` (folder icon).
- `LauncherModel` (PackageManager, icon packs, icons scaled to launcher icon size), `Store` (SharedPreferences).
- `ItemInfo` / `AppInfo` (data), `U` (helpers); `scripts/check-api.sh` - compile check against a platform android.jar.

## Languages
The app name is "LowLauncher" in every language. The UI is translated into 47 locales of Android 4.0.x: English (default) plus 46 `values-*` folders, i.e. 46 languages with Chinese in two variants (see `app/src/main/res/values-*`). The translations were made automatically, so native speakers should review the wording.

## Hardware acceleration
Enabled in the manifest (`hardwareAccelerated`). During animations (app drawer, page scrolling, the dragged icon) views temporarily get a GPU hardware layer, which is released afterwards to save memory.

## Status
Concept and requirements (ТЗ) by the author; the code was generated with Claude (Anthropic). Version 1.0 was tested on a real device; the 1.1 and 1.2 changes (folders, Android 7.1 adaptation, fixes) are compile-checked and still need testing on devices. A separate build for Android 9-17 lives in `launcher-new`.

## Changelog
- **1.2** (build 3) - bug-fix release.
  - **The layout is kept:** icons of temporarily unavailable apps (SD card, system still starting, disabled app) and icons that do not fit after shrinking the grid are no longer erased; they come back when possible.
  - **Fixes:** no duplicates in the Dock; icons and labels refresh after app updates; correct widget names on Android 5.0+; the home screen no longer turns transparent when Home is pressed while the app drawer is opening; an empty app list at system start is retried; no blank screen after returning from the "App info" window.
- **1.1** - adaptation for Android 4.0.4 – 7.1 and folders on the home screen.
  - **Folders:** drop an icon onto another one to create a folder; open it, rename it and drag icons out.
  - **Android 4.0.4 – 7.1** (targetSdk 25): own widget list and binding on Android 4.1+, widgets are told their size.
- **1.0** - first release.
