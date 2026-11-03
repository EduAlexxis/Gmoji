package io.github.edualexxis.gmoji;

import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.util.Xml;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.xmlpull.v1.XmlPullParser;

/** System font families (from fonts.xml) as FontFamily objects. No Xposed dependency: also used by the companion app. */
final class Fonts {
  private Fonts() {}

  static final class Spec {
    String file;
    int weight;
    boolean italic;
    StringBuilder axes = new StringBuilder();
  }

  /** family name -> its font entries; aliases resolve to their target. */
  static Map<String, List<Spec>> parseSystemFamilies() throws Exception {
    Map<String, List<Spec>> fam = new HashMap<String, List<Spec>>();
    Map<String, String> alias = new HashMap<String, String>();
    FileInputStream in = new FileInputStream("/system/etc/fonts.xml");
    try {
      XmlPullParser x = Xml.newPullParser();
      x.setInput(in, "UTF-8");
      List<Spec> cur = null;
      Spec spec = null;
      for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
        if (ev == XmlPullParser.START_TAG) {
          String n = x.getName();
          if ("family".equals(n)) {
            String name = x.getAttributeValue(null, "name");
            cur = name != null && !fam.containsKey(name) ? new ArrayList<Spec>() : null;
            if (cur != null) fam.put(name, cur);
          } else if ("alias".equals(n)) {
            alias.put(x.getAttributeValue(null, "name"), x.getAttributeValue(null, "to"));
          } else if ("font".equals(n) && cur != null) {
            spec = new Spec();
            String w = x.getAttributeValue(null, "weight");
            spec.weight = w == null ? 400 : Integer.parseInt(w);
            spec.italic = "italic".equals(x.getAttributeValue(null, "style"));
            cur.add(spec);
          } else if ("axis".equals(n) && spec != null) {
            if (spec.axes.length() > 0) spec.axes.append(',');
            spec.axes.append('\'').append(x.getAttributeValue(null, "tag")).append("' ").append(x.getAttributeValue(null, "stylevalue"));
          }
        } else if (ev == XmlPullParser.TEXT && spec != null && spec.file == null) {
          String t = x.getText().trim();
          if (t.length() > 0) spec.file = t;
        } else if (ev == XmlPullParser.END_TAG && "family".equals(x.getName())) {
          cur = null;
          spec = null;
        }
      }
    } finally {
      in.close();
    }
    for (Map.Entry<String, String> e : alias.entrySet()) {
      if (!fam.containsKey(e.getKey()) && fam.containsKey(e.getValue())) fam.put(e.getKey(), fam.get(e.getValue()));
    }
    return fam;
  }

  static File findSystemFont(String name) {
    for (String dir : new String[]{"/system/fonts/", "/product/fonts/", "/system_ext/fonts/", "/vendor/fonts/"}) {
      File f = new File(dir + name);
      if (f.canRead()) return f;
    }
    return null;
  }

  static FontFamily buildFamily(List<Spec> specs) throws Exception {
    FontFamily.Builder b = null;
    for (Spec s : specs) {
      File f = s.file == null ? null : findSystemFont(s.file);
      if (f == null) continue;
      Font.Builder fb = new Font.Builder(f).setWeight(s.weight).setSlant(s.italic ? FontStyle.FONT_SLANT_ITALIC : FontStyle.FONT_SLANT_UPRIGHT);
      if (s.axes.length() > 0) fb.setFontVariationSettings(s.axes.toString());
      Font font = fb.build();
      if (b == null) b = new FontFamily.Builder(font);
      else b.addFont(font);
    }
    return b == null ? null : b.build();
  }

  // ---- ready-made typefaces: UI font first, a given emoji font after it --------------------------

  private static FontFamily uiFamily;
  private static final Map<String, android.graphics.Typeface> faces = new HashMap<String, android.graphics.Typeface>();

  /** Typeface that draws text with the system UI font and emoji with the given font file. Null if it can't be loaded. */
  static synchronized android.graphics.Typeface faceFor(File font) {
    String key = font.getAbsolutePath() + ":" + font.lastModified();
    android.graphics.Typeface t = faces.get(key);
    if (t != null) return t;
    try {
      if (uiFamily == null) uiFamily = buildFamily(parseSystemFamilies().get("sans-serif"));
      FontFamily emoji = new FontFamily.Builder(openEmoji(font)).build();
      t = new android.graphics.Typeface.CustomFallbackBuilder(uiFamily).addCustomFallback(emoji).setSystemFallback("sans-serif").build();
      faces.put(key, t);
      return t;
    } catch (Throwable e) {
      return null;
    }
  }

  // ---- .ttc collections --------------------------------------------------------------------------

  /** Opens an emoji font file. For a .ttc collection, picks the face that holds color emoji data (else the first). */
  static Font openEmoji(File f) throws Exception {
    return new Font.Builder(f).setTtcIndex(ttcIndex(f)).build();
  }

  static int ttcIndex(File f) {
    try {
      java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r");
      try {
        if (r.readInt() != 0x74746366) return 0; // 'ttcf': a plain .ttf/.otf
        r.readInt(); // version
        int num = Math.min(r.readInt(), 64);
        for (int i = 0; i < num; i++) {
          r.seek(12 + 4L * i);
          long off = r.readInt() & 0xFFFFFFFFL;
          r.seek(off + 4);
          int tables = r.readUnsignedShort();
          r.skipBytes(6);
          for (int t = 0; t < tables; t++) {
            int tag = r.readInt();
            r.skipBytes(12);
            if (tag == 0x43424454 /* CBDT */ || tag == 0x434F4C52 /* COLR */ || tag == 0x73626978 /* sbix */ || tag == 0x53564720 /* SVG  */) return i;
          }
        }
      } finally {
        r.close();
      }
    } catch (Throwable ignored) {
    }
    return 0;
  }
}
