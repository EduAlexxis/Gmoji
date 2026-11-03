package com.edualexxis.gmoji;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/** The bottom sheet opened by the Gmoji row in Gboard's settings: lists every imported font. */
final class Sheet {
  static final int REQ_IMPORT = 0x4D47;

  static void show(final Activity act) {
    final Store store = Store.I;
    if (store == null) return;
    final Ui ui = new Ui(act);
    final Dialog d = new Dialog(act);
    d.requestWindowFeature(Window.FEATURE_NO_TITLE);

    LinearLayout sheet = new LinearLayout(act);
    sheet.setOrientation(LinearLayout.VERTICAL);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(ui.bg);
    float r = ui.dp(28);
    bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
    sheet.setBackground(bg);
    sheet.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(20));

    View handle = new View(act);
    handle.setBackground(ui.rounded(ui.muted & 0x00FFFFFF | 0x66000000, 2));
    LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(4));
    hlp.gravity = Gravity.CENTER_HORIZONTAL;
    sheet.addView(handle, hlp);

    TextView title = ui.label("Emoji fonts", 22, ui.text, true);
    title.setPadding(ui.dp(4), ui.dp(18), 0, ui.dp(4));
    sheet.addView(title);
    TextView sub = ui.label("Only Gboard uses the selected font. Switching restarts the keyboard.", 14, ui.muted, false);
    sub.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(14));
    sheet.addView(sub);

    final String active = store.activeId();
    LinearLayout list = new LinearLayout(act);
    list.setOrientation(LinearLayout.VERTICAL);
    JSONArray fonts = store.fonts();
    int shown = 0;
    for (int i = 0; i < fonts.length(); i++) {
      JSONObject o = fonts.optJSONObject(i);
      if (o == null || !store.fileOf(o.optString("id")).exists()) continue;
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.bottomMargin = ui.dp(10);
      list.addView(row(act, ui, d, store, o.optString("id"), o.optString("name"), o.optString("id").equals(active)), lp);
      shown++;
    }
    if (shown == 0) {
      TextView empty = ui.label("No fonts yet. Import a color emoji .ttf to get started.", 15, ui.muted, false);
      empty.setPadding(ui.dp(4), ui.dp(4), 0, ui.dp(12));
      list.addView(empty);
    }
    final int max = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.5f);
    ScrollView scroll = new ScrollView(act) {
      @Override protected void onMeasure(int w, int h) {
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST));
      }
    };
    scroll.setVerticalScrollBarEnabled(false);
    scroll.addView(list);
    sheet.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

    View add = ui.button("Import font", true);
    add.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        d.dismiss();
        act.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT);
      }
    });
    View sys = ui.button("Use system emoji", false);
    sys.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        d.dismiss();
        store.reset();
        SettingsUi.restart(act.getApplicationContext(), "Back to system emoji");
      }
    });
    LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(-1, ui.dp(56));
    a1.topMargin = ui.dp(8);
    LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(-1, ui.dp(56));
    a2.topMargin = ui.dp(10);
    sheet.addView(add, a1);
    sheet.addView(sys, a2);

    d.setContentView(sheet);
    Window w = d.getWindow();
    w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    w.setGravity(Gravity.BOTTOM);
    w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    w.setDimAmount(0.5f);
    d.show();
  }

  static View row(final Activity act, final Ui ui, final Dialog d, final Store store, final String id, final String name, boolean isActive) {
    LinearLayout r = new LinearLayout(act);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setPadding(ui.dp(18), ui.dp(14), ui.dp(8), ui.dp(14));
    GradientDrawable bg = ui.rounded(ui.surface, 22);
    if (isActive) bg.setStroke(ui.dp(2), ui.accent);
    r.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ui.text & 0x00FFFFFF | 0x22000000), bg, null));
    r.setClickable(true);
    r.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        d.dismiss();
        if (id.equals(store.activeId())) return;
        if (store.activate(id)) SettingsUi.restart(act.getApplicationContext(), "Switched to " + name);
        else SettingsUi.toast(act.getApplicationContext(), "That font is missing");
      }
    });

    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20));
    rlp.rightMargin = ui.dp(16);
    r.addView(ui.radio(isActive), rlp);

    LinearLayout text = new LinearLayout(act);
    text.setOrientation(LinearLayout.VERTICAL);
    TextView t = ui.label(name, 17, ui.text, true);
    t.setSingleLine(true);
    t.setEllipsize(android.text.TextUtils.TruncateAt.END);
    TextView preview = ui.label(Ui.sample(5), 22, ui.text, false);
    preview.setSingleLine(true);
    preview.setPadding(0, ui.dp(4), 0, 0);
    Typeface tf = Fonts.faceFor(store.fileOf(id));
    if (tf != null) preview.setTypeface(tf);
    text.addView(t);
    text.addView(preview);
    r.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));

    TextView del = ui.label("✕", 16, ui.muted, false);
    del.setGravity(Gravity.CENTER);
    del.setClickable(true);
    del.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        d.dismiss();
        new AlertDialog.Builder(act)
            .setTitle("Remove " + name + "?")
            .setMessage("The font is deleted from Gboard's storage.")
            .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
              @Override public void onClick(DialogInterface x, int w) {
                boolean was = id.equals(store.activeId());
                try {
                  store.remove(id);
                } catch (Throwable ignored) {
                }
                if (was) SettingsUi.restart(act.getApplicationContext(), "Back to system emoji");
                else show(act);
              }
            })
            .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
              @Override public void onClick(DialogInterface x, int w) {
                show(act);
              }
            })
            .show();
      }
    });
    r.addView(del, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
    return r;
  }
}
