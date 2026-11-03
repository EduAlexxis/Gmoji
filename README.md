# Gmoji

Use your own color emoji font in **Gboard**, and nowhere else. An Xposed-style module for [Vector](https://github.com/JingMatrix/Vector) / LSPosed.

- Import `.ttf` and `.ttc` color emoji fonts and switch between them in one tap.
- A **Gmoji** row appears in Gboard's own settings (right under *Emojis, stickers & GIFs*) and opens a sheet with every imported font and a live preview of each.
- A small companion app does the same, as a backup.
- Only Gboard is affected. Other apps keep the system emoji.
- Emoji your font doesn't have fall back to the system emoji, so nothing turns into an empty box.

## Requirements

- Android 10 or newer, rooted
- Vector (or another LSPosed-compatible framework) with module scope for Gboard
- Gboard (`com.google.android.inputmethod.latin`)

Developed and tested on Android 17 with Gboard 18.4 and Vector 2.2 (Zygisk Next on KernelSU).

## Install

1. Install the APK.
2. In Vector, enable **Gmoji** and make sure **Gboard** is in its scope.
3. Force-stop Gboard (or reboot) so the module loads.
4. Open Gboard settings, tap **Gmoji**, and import a font. Gboard restarts to apply it.

## Fonts

Color emoji fonts in `CBDT/CBLC` (Noto style) or `COLR` format work. Other color formats are untested. For `.ttc` collections, Gmoji uses the face that contains the color glyph data.

Fonts are copied into Gboard's private storage. Removing a font in Gmoji deletes that copy.

## How it works

Gboard has no emoji font of its own; it draws emoji through Android's system font fallback. Inside Gboard's process only, Gmoji rebuilds the system typefaces so the chain becomes:

1. the system UI font (so digits, letters, symbols and line metrics don't change),
2. your emoji font,
3. the original system fallbacks.

The font library lives in Gboard's storage (`files/gmoji/`), and the active font is a symlink at `files/gmoji.ttf` that is loaded when Gboard starts. The companion app can't read that storage, so it asks the hook running inside Gboard for the library and sends it commands (import, switch, remove, reset) by launching Gboard's settings activity with extras. Only requests whose referrer is the Gmoji app are accepted.

Gboard's settings are a stock AndroidX preference screen, but its code is obfuscated. The Gmoji row is a clone of an existing row, and the members it needs are found by signature at runtime instead of by name, so it doesn't depend on obfuscated identifiers. A big Gboard update could still break it; if so, it only logs an error and the font swap keeps working.

## Build

Needs a JDK and the Android SDK (build-tools and a platform). No Gradle.

```sh
./build.sh            # produces build/Gmoji.apk
./build.sh install    # also installs it with adb
```

The APK is signed with a throwaway key created on first build at `~/.android/gmoji-debug.jks` (override with `GMOJI_KEYSTORE`). Keep that file: updates only install over a copy signed with the same key.

## Layout

```
src/co/edualexxis/gmoji/
  Hook.java        entry point; swaps the typefaces inside Gboard
  Fonts.java       system font families, .ttc handling, ready-made typefaces
  Store.java       the font library inside Gboard's storage
  SettingsUi.java  the Gmoji row, and requests from the companion app
  Sheet.java       the bottom sheet opened by the row
  MainActivity.java  companion app
  Ui.java, Previews.java  shared look, and preview rendering
stubs/             compile-time stubs of the Xposed API (not shipped)
```

## License

[MIT](LICENSE)
