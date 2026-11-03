package com.edualexxis.gmoji;

import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes Gboard draw emoji with a custom TTF instead of the system NotoColorEmoji.
 *
 * Gboard has no emoji font of its own: it relies on the system fallback chain. So inside Gboard's
 * process only, every system Typeface gets its native font collection replaced with
 *     [system UI font of that family] -> [custom emoji font] -> [original system fallbacks]
 * The UI font stays first so digits, symbols and line metrics are untouched; the custom font only
 * wins for characters the UI font lacks (i.e. emoji). Other apps are not affected.
 *
 * Font location: <Gboard data dir>/files/gmoji.ttf, chosen from the "Gmoji" row that SettingsUi
 * adds to Gboard's own settings screen, or from the companion app.
 */
public class Hook implements IXposedHookLoadPackage {
  static final String TARGET = "com.google.android.inputmethod.latin";
  static final String TAG = "Gmoji";
  static final String FONT_NAME = "gmoji.ttf";
  // strong refs: the swapped-in native objects are freed when their Java owner is collected
  static final List<Typeface> KEEP = new ArrayList<Typeface>();
  static volatile File fontFile;
  static volatile boolean active;

  static void log(String s) {
    Log.i(TAG, s);
    XposedBridge.log(TAG + ": " + s);
  }

  @Override
  public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
    if (!TARGET.equals(lp.packageName)) return;
    File files = new File(lp.appInfo.dataDir, "files");
    files.mkdirs();
    fontFile = new File(files, FONT_NAME); // symlink to the active font in the library (see Store)
    try {
      Store.init(files);
      Store.I.migrateLegacy();
    } catch (Throwable t) {
      log("library init failed: " + Log.getStackTraceString(t));
    }
    try {
      apply(fontFile);
    } catch (Throwable t) {
      log("font failed: " + Log.getStackTraceString(t));
    }
    try {
      SettingsUi.install(lp.classLoader);
    } catch (Throwable t) {
      log("settings ui failed: " + Log.getStackTraceString(t));
    }
  }

  static Object get(Class<?> c, Object o, String name) throws Exception {
    Field f = c.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(o);
  }

  // ---- the swap -------------------------------------------------------------------------------

  static void apply(File font) throws Exception {
    if (!font.canRead()) {
      log("no custom font at " + font + " (import one in Gboard settings > Gmoji, or in the Gmoji app)");
      return;
    }
    FontFamily emoji = new FontFamily.Builder(Fonts.openEmoji(font)).build();
    Map<String, List<Fonts.Spec>> sysFamilies = Fonts.parseSystemFamilies();
    Map<List<Fonts.Spec>, FontFamily> built = new IdentityHashMap<List<Fonts.Spec>, FontFamily>();

    @SuppressWarnings("unchecked")
    Map<String, Typeface> sysMap = (Map<String, Typeface>) get(Typeface.class, null, "sSystemFontMap");

    // target Typeface -> system family name it was built from
    IdentityHashMap<Typeface, String> targets = new IdentityHashMap<Typeface, String>();
    synchronized (sysMap) {
      for (Map.Entry<String, Typeface> e : sysMap.entrySet()) {
        if (e.getValue() != null && !targets.containsKey(e.getValue())) targets.put(e.getValue(), e.getKey());
      }
    }
    Typeface[] fixed = {Typeface.DEFAULT, Typeface.DEFAULT_BOLD, Typeface.SANS_SERIF, Typeface.SERIF, Typeface.MONOSPACE};
    String[] fixedFam = {"sans-serif", "sans-serif", "sans-serif", "serif", "monospace"};
    for (int i = 0; i < fixed.length; i++) if (fixed[i] != null && !targets.containsKey(fixed[i])) targets.put(fixed[i], fixedFam[i]);
    Typeface[] defs = (Typeface[]) get(Typeface.class, null, "sDefaults");
    if (defs != null) for (Typeface t : defs) if (t != null && !targets.containsKey(t)) targets.put(t, "sans-serif");

    // phase 1: build every replacement while the originals are still intact (they serve as the fallback)
    IdentityHashMap<Typeface, Typeface> repl = new IdentityHashMap<Typeface, Typeface>();
    for (Map.Entry<Typeface, String> e : targets.entrySet()) {
      Typeface old = e.getKey();
      try {
        List<Fonts.Spec> specs = sysFamilies.get(e.getValue());
        if (specs == null) {
          log("skip " + e.getValue() + ": not in fonts.xml");
          continue;
        }
        FontFamily ui = built.get(specs);
        if (ui == null) {
          ui = Fonts.buildFamily(specs);
          if (ui == null) {
            log("skip " + e.getValue() + ": font files unreadable");
            continue;
          }
          built.put(specs, ui);
        }
        int w = old.getWeight();
        int slant = old.isItalic() ? FontStyle.FONT_SLANT_ITALIC : FontStyle.FONT_SLANT_UPRIGHT;
        Typeface n = new Typeface.CustomFallbackBuilder(ui)
            .addCustomFallback(emoji)
            .setSystemFallback(e.getValue())
            .setStyle(new FontStyle(w <= 0 ? FontStyle.FONT_WEIGHT_NORMAL : w, slant))
            .build();
        repl.put(old, n);
        KEEP.add(n);
      } catch (Throwable t) {
        log("skip " + e.getValue() + ": " + t);
      }
    }

    // phase 2: point the originals at the new native collections
    Field ni = Typeface.class.getDeclaredField("native_instance");
    ni.setAccessible(true);
    int swapped = 0;
    for (Map.Entry<Typeface, Typeface> e : repl.entrySet()) {
      ni.setLong(e.getKey(), ni.getLong(e.getValue()));
      swapped++;
    }

    // derived-typeface caches were built from the old native objects
    for (String cache : new String[]{"sStyledTypefaceCache", "sWeightTypefaceCache", "sDynamicTypefaceCache", "sVariableCache"}) {
      try {
        Object c = get(Typeface.class, null, cache);
        if (c == null) continue;
        for (String m : new String[]{"evictAll", "clear"}) {
          try {
            Method mm = c.getClass().getMethod(m);
            mm.invoke(c);
            break;
          } catch (NoSuchMethodException ignored) {
          }
        }
      } catch (Throwable t) {
        log("cache " + cache + ": " + t);
      }
    }

    // native default (used by Paints / TextViews with no explicit typeface)
    try {
      Method sd = Typeface.class.getDeclaredMethod("setDefault", Typeface.class);
      sd.setAccessible(true);
      sd.invoke(null, Typeface.DEFAULT);
    } catch (Throwable t) {
      log("setDefault: " + t);
    }

    active = swapped > 0;
    log("active: " + swapped + "/" + targets.size() + " system typefaces now use " + font);
  }
}
