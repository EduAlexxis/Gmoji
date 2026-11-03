package io.github.edualexxis.gmoji;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Renders the sample emoji of a font to a PNG (what the companion app shows; it never loads the fonts itself). */
final class Previews {
  private static final Map<String, byte[]> cache = new HashMap<String, byte[]>();

  static synchronized byte[] png(File font) {
    String key = font.getAbsolutePath() + ":" + font.lastModified();
    byte[] hit = cache.get(key);
    if (hit != null) return hit;
    Typeface tf = Fonts.faceFor(font);
    if (tf == null) return null;
    try {
      Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
      p.setTypeface(tf);
      p.setTextSize(64);
      String s = Ui.sample(Ui.SAMPLE.length);
      Paint.FontMetrics fm = p.getFontMetrics();
      Bitmap b = Bitmap.createBitmap((int) Math.ceil(p.measureText(s)) + 8, (int) Math.ceil(fm.descent - fm.ascent) + 8, Bitmap.Config.ARGB_8888);
      new Canvas(b).drawText(s, 4, 4 - fm.ascent, p);
      ByteArrayOutputStream o = new ByteArrayOutputStream();
      b.compress(Bitmap.CompressFormat.PNG, 100, o);
      byte[] out = o.toByteArray();
      cache.put(key, out);
      return out;
    } catch (Throwable t) {
      return null;
    }
  }
}
