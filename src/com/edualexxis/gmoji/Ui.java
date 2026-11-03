package com.edualexxis.gmoji;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/** Palette and small view helpers shared by the companion app and the sheet shown inside Gboard. */
final class Ui {
  static final int[] SAMPLE = {0x1F600, 0x1F60D, 0x1F525, 0x1F389, 0x1F44D, 0x1F64F, 0x1F62D, 0x2764};

  final Context ctx;
  final int bg, surface, text, muted, accent, onAccent, tonal, green;

  Ui(Context c) {
    ctx = c;
    boolean dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    bg = dark ? 0xFF0D0F12 : 0xFFF4F5F7;
    surface = dark ? 0xFF181B20 : 0xFFFFFFFF;
    text = dark ? 0xFFEEF0F3 : 0xFF14161A;
    muted = dark ? 0xFF979DA7 : 0xFF666D77;
    tonal = dark ? 0xFF23272E : 0xFFE8EAEE;
    green = dark ? 0xFF4CD27A : 0xFF1FA85A;
    if (Build.VERSION.SDK_INT >= 31) { // Material You: follow the wallpaper accent
      accent = c.getColor(dark ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600);
      onAccent = c.getColor(dark ? android.R.color.system_accent1_900 : android.R.color.system_accent1_0);
    } else {
      accent = dark ? 0xFFA3B8FF : 0xFF3D5AFE;
      onAccent = dark ? 0xFF0B1226 : 0xFFFFFFFF;
    }
  }

  int dp(int v) {
    return (int) (v * ctx.getResources().getDisplayMetrics().density);
  }

  GradientDrawable rounded(int color, int radiusDp) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(color);
    g.setCornerRadius(dp(radiusDp));
    return g;
  }

  TextView label(String s, int sp, int color, boolean medium) {
    TextView t = new TextView(ctx);
    t.setText(s);
    t.setTextSize(sp);
    t.setTextColor(color);
    t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
    return t;
  }

  View button(String text, boolean primary) {
    TextView t = label(text, 17, primary ? onAccent : this.text, true);
    t.setGravity(Gravity.CENTER);
    t.setClickable(true);
    t.setFocusable(true);
    int ripple = (primary ? onAccent : this.text) & 0x00FFFFFF | 0x22000000;
    t.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), rounded(primary ? accent : tonal, 29), null));
    return t;
  }

  /** Round radio mark: filled when selected. */
  View radio(boolean on) {
    View v = new View(ctx);
    GradientDrawable g = new GradientDrawable();
    g.setShape(GradientDrawable.OVAL);
    if (on) g.setColor(accent);
    else g.setStroke(dp(2), muted);
    v.setBackground(g);
    return v;
  }

  static String sample(int count) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < count && i < SAMPLE.length; i++) {
      if (i > 0) sb.append("  ");
      sb.appendCodePoint(SAMPLE[i]);
      if (SAMPLE[i] == 0x2764) sb.append('️');
    }
    return sb.toString();
  }

  static String stripExt(String n) {
    if (n == null) return "Font";
    int i = n.lastIndexOf('.');
    return i > 0 ? n.substring(0, i) : n;
  }
}
