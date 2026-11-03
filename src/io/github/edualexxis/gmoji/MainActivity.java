package io.github.edualexxis.gmoji;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Companion app: a remote for the emoji font library that lives inside Gboard.
 *
 * Gboard's storage is private (and Android hides other apps from it), so this app holds no fonts.
 * It asks Gboard's settings activity for the library ("state", answered with a result intent) and
 * sends it commands (import / activate / remove / reset) as extras on the same activity; the hook
 * running inside Gboard (SettingsUi) carries them out. No root needed.
 */
public class MainActivity extends Activity {
  static final String GBOARD = "com.google.android.inputmethod.latin";
  static final String GBOARD_SETTINGS = "com.google.android.apps.inputmethod.latin.preference.SettingsActivity";
  static final String EXTRA_ACTION = "gmoji.action";
  static final int REQ_PICK = 1, REQ_STATE = 2;

  SharedPreferences prefs;
  Ui ui;
  Handler handler = new Handler();
  LinearLayout list;
  TextView dot, badge, headline, detail, sample, note;
  ImageView samplePng;
  JSONObject model = new JSONObject(); // last known state of Gboard's library
  int pullTries;

  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    prefs = getSharedPreferences("gmoji", MODE_PRIVATE);
    ui = new Ui(this);
    try {
      model = new JSONObject(prefs.getString("model", "{}"));
    } catch (Throwable ignored) {
    }

    // edge to edge, with the system bars blending into the background
    Window w = getWindow();
    w.setDecorFitsSystemWindows(false);
    w.setStatusBarColor(0);
    w.setNavigationBarColor(0);

    final LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(ui.bg);
    root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
      @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
        v.setPadding(0, in.getSystemWindowInsetTop() + ui.dp(28), 0, in.getSystemWindowInsetBottom() + ui.dp(20));
        return in;
      }
    });

    final int side = ui.dp(24);
    LinearLayout page = new LinearLayout(this);
    page.setOrientation(LinearLayout.VERTICAL);
    page.setPadding(side, 0, side, ui.dp(16));

    TextView mark = ui.label("Gmoji", 15, ui.accent, true);
    mark.setLetterSpacing(0.12f);

    // status card with a sample of the active font
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setBackground(ui.rounded(ui.surface, 28));
    card.setPadding(ui.dp(24), ui.dp(22), ui.dp(24), ui.dp(24));
    card.setClickable(true); // tap to refresh
    card.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        pull();
      }
    });

    LinearLayout state = new LinearLayout(this);
    state.setGravity(Gravity.CENTER_VERTICAL);
    dot = new TextView(this);
    LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(ui.dp(10), ui.dp(10));
    dotLp.rightMargin = ui.dp(10);
    badge = ui.label("", 12, ui.muted, true);
    badge.setLetterSpacing(0.1f);
    state.addView(dot, dotLp);
    state.addView(badge);

    headline = ui.label("", 28, ui.text, true);
    headline.setPadding(0, ui.dp(16), 0, 0);
    headline.setSingleLine(true);
    headline.setEllipsize(android.text.TextUtils.TruncateAt.END);
    detail = ui.label("", 14, ui.muted, false);
    detail.setPadding(0, ui.dp(4), 0, 0);
    sample = ui.label(Ui.sample(Ui.SAMPLE.length), 30, ui.text, false);
    sample.setPadding(0, ui.dp(18), 0, 0);
    sample.setSingleLine(true);
    samplePng = new ImageView(this);
    samplePng.setAdjustViewBounds(true);
    samplePng.setScaleType(ImageView.ScaleType.FIT_START);
    samplePng.setPadding(0, ui.dp(18), 0, 0);
    card.addView(state);
    card.addView(headline);
    card.addView(detail);
    card.addView(sample);
    card.addView(samplePng, new LinearLayout.LayoutParams(-1, -2));

    note = ui.label("", 13, ui.muted, false);
    note.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), 0);

    TextView fontsLabel = ui.label("FONTS", 12, ui.muted, true);
    fontsLabel.setLetterSpacing(0.1f);
    fontsLabel.setPadding(ui.dp(4), ui.dp(24), 0, ui.dp(10));
    list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);

    page.addView(mark);
    LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
    cardLp.topMargin = ui.dp(24);
    page.addView(card, cardLp);
    page.addView(note);
    page.addView(fontsLabel);
    page.addView(list);

    ScrollView scroll = new ScrollView(this);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.addView(page);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

    // actions stay pinned at the bottom
    LinearLayout bar = new LinearLayout(this);
    bar.setOrientation(LinearLayout.VERTICAL);
    bar.setPadding(side, ui.dp(8), side, 0);
    View add = ui.button("Import font", true);
    add.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_PICK);
      }
    });
    View reset = ui.button("Use system emoji", false);
    reset.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        command(new Intent().putExtra(EXTRA_ACTION, "reset"));
        setActive("");
      }
    });
    bar.addView(add, new LinearLayout.LayoutParams(-1, ui.dp(58)));
    LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, ui.dp(58));
    rLp.topMargin = ui.dp(12);
    bar.addView(reset, rLp);
    root.addView(bar);

    setContentView(root);
    render();
    pull();
  }

  // ---- talking to Gboard ----------------------------------------------------------------------

  Intent toGboard(Intent i) {
    return i.setComponent(new ComponentName(GBOARD, GBOARD_SETTINGS)).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
  }

  void command(Intent i) {
    try {
      startActivity(toGboard(i));
      pullTries = 0;
      pullLater(3000); // let Gboard finish, then re-read what it really has
    } catch (Throwable t) {
      Toast.makeText(this, "Couldn't reach Gboard: " + t.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  void pull() {
    try {
      startActivityForResult(toGboard(new Intent().putExtra(EXTRA_ACTION, "state")), REQ_STATE);
    } catch (Throwable t) {
      note.setText("Gboard isn't reachable.");
    }
  }

  void pullLater(long ms) {
    handler.removeCallbacksAndMessages(null);
    handler.postDelayed(new Runnable() {
      @Override public void run() {
        pull();
      }
    }, ms);
  }

  @Override
  protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (req == REQ_PICK) {
      if (res != RESULT_OK || data == null || data.getData() == null) return;
      Uri uri = data.getData();
      String id = "f" + System.currentTimeMillis(), name = Ui.stripExt(displayName(uri));
      command(new Intent().setData(uri).putExtra(EXTRA_ACTION, "import").putExtra("gmoji.id", id).putExtra("gmoji.name", name)
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
      try { // show it right away; the next pull replaces this with what Gboard really has
        model.getJSONArray("fonts").put(new JSONObject().put("id", id).put("name", name).put("mb", 0));
        model.put("active", id);
      } catch (Throwable ignored) {
        try {
          model = new JSONObject().put("active", id).put("fonts", new JSONArray().put(new JSONObject().put("id", id).put("name", name).put("mb", 0)));
        } catch (Throwable ignored2) {
        }
      }
      note.setText("Importing… this can take a few seconds");
      render();
    } else if (req == REQ_STATE) {
      if (res != RESULT_OK || data == null || data.getStringExtra("json") == null) {
        note.setText("Couldn't read Gboard's fonts. Is Gmoji enabled for Gboard in Vector?");
        return;
      }
      try {
        model = new JSONObject(data.getStringExtra("json"));
        prefs.edit().putString("model", model.toString()).apply();
        File dir = new File(getFilesDir(), "previews");
        dir.mkdirs();
        JSONArray fonts = model.getJSONArray("fonts");
        for (int i = 0; i < fonts.length(); i++) {
          String id = fonts.getJSONObject(i).getString("id");
          byte[] png = data.getByteArrayExtra("png:" + id);
          if (png != null) {
            FileOutputStream o = new FileOutputStream(new File(dir, id + ".png"));
            o.write(png);
            o.close();
          }
        }
        note.setText("");
        if (model.optBoolean("busy") && pullTries++ < 15) pullLater(2000); // an import is still running
      } catch (Throwable t) {
        note.setText("Unexpected reply from Gboard.");
      }
      render();
    }
  }

  void setActive(String id) {
    try {
      model.put("active", id);
    } catch (Throwable ignored) {
    }
    render();
  }

  // ---- rendering ------------------------------------------------------------------------------

  Bitmap preview(String id) {
    File f = new File(new File(getFilesDir(), "previews"), id + ".png");
    return f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
  }

  void render() {
    String act = model.optString("active", "");
    JSONArray fonts = model.optJSONArray("fonts");
    if (fonts == null) fonts = new JSONArray();
    String activeName = null;
    for (int i = 0; i < fonts.length(); i++) {
      JSONObject o = fonts.optJSONObject(i);
      if (o != null && o.optString("id").equals(act)) activeName = o.optString("name");
    }
    boolean on = activeName != null;
    dot.setBackground(ui.rounded(on ? ui.green : ui.muted, 5));
    badge.setText(on ? "ACTIVE FONT" : "DEFAULT");
    headline.setText(on ? activeName : "System emoji");
    detail.setText(on ? "Used in Gboard's emoji picker and suggestions" : "Import a color emoji .ttf to replace Gboard's emoji");
    Bitmap bp = on ? preview(act) : null;
    sample.setVisibility(bp == null ? View.VISIBLE : View.GONE);
    samplePng.setVisibility(bp == null ? View.GONE : View.VISIBLE);
    if (bp != null) samplePng.setImageBitmap(bp);
    sample.setAlpha(on ? 0.35f : 1f); // no preview yet for the active font: dim the system sample

    list.removeAllViews();
    int shown = 0;
    for (int i = 0; i < fonts.length(); i++) {
      JSONObject o = fonts.optJSONObject(i);
      if (o == null) continue;
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.bottomMargin = ui.dp(10);
      list.addView(row(o.optString("id"), o.optString("name"), o.optString("id").equals(act)), lp);
      shown++;
    }
    if (shown == 0) {
      TextView empty = ui.label("No fonts yet. Import one to get started.", 15, ui.muted, false);
      empty.setPadding(ui.dp(4), 0, 0, 0);
      list.addView(empty);
    }
  }

  View row(final String id, final String name, boolean isActive) {
    LinearLayout r = new LinearLayout(this);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setPadding(ui.dp(18), ui.dp(14), ui.dp(8), ui.dp(14));
    GradientDrawable bg = ui.rounded(ui.surface, 22);
    if (isActive) bg.setStroke(ui.dp(2), ui.accent);
    r.setBackground(new RippleDrawable(ColorStateList.valueOf(ui.text & 0x00FFFFFF | 0x22000000), bg, null));
    r.setClickable(true);
    r.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        if (id.equals(model.optString("active", ""))) return;
        command(new Intent().putExtra(EXTRA_ACTION, "activate").putExtra("gmoji.id", id));
        setActive(id);
      }
    });

    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20));
    rlp.rightMargin = ui.dp(16);
    r.addView(ui.radio(isActive), rlp);

    LinearLayout text = new LinearLayout(this);
    text.setOrientation(LinearLayout.VERTICAL);
    TextView title = ui.label(name, 17, ui.text, true);
    title.setSingleLine(true);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    text.addView(title);
    Bitmap bp = preview(id);
    if (bp != null) {
      ImageView iv = new ImageView(this);
      iv.setImageBitmap(bp);
      iv.setAdjustViewBounds(true);
      iv.setScaleType(ImageView.ScaleType.FIT_START);
      iv.setPadding(0, ui.dp(6), 0, 0);
      text.addView(iv, new LinearLayout.LayoutParams(-1, ui.dp(30)));
    }
    r.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));

    TextView del = ui.label("✕", 16, ui.muted, false);
    del.setGravity(Gravity.CENTER);
    del.setClickable(true);
    del.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        new AlertDialog.Builder(MainActivity.this)
            .setTitle("Remove " + name + "?")
            .setMessage("The font is deleted from Gboard's storage.")
            .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
              @Override public void onClick(DialogInterface d, int w) {
                command(new Intent().putExtra(EXTRA_ACTION, "remove").putExtra("gmoji.id", id));
                try {
                  JSONArray old = model.getJSONArray("fonts"), keep = new JSONArray();
                  for (int i = 0; i < old.length(); i++) if (!id.equals(old.getJSONObject(i).optString("id"))) keep.put(old.getJSONObject(i));
                  model.put("fonts", keep);
                  if (id.equals(model.optString("active", ""))) model.put("active", "");
                } catch (Throwable ignored) {
                }
                render();
              }
            })
            .setNegativeButton("Cancel", null)
            .show();
      }
    });
    r.addView(del, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
    return r;
  }

  String displayName(Uri uri) {
    try {
      Cursor c = getContentResolver().query(uri, null, null, null, null);
      try {
        if (c != null && c.moveToFirst()) {
          int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
          if (i >= 0) return c.getString(i);
        }
      } finally {
        if (c != null) c.close();
      }
    } catch (Throwable ignored) {
    }
    return uri.getLastPathSegment();
  }
}
