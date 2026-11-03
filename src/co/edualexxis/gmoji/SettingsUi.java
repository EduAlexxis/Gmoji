package co.edualexxis.gmoji;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Adds a "Gmoji" row to Gboard's own settings, right after "Emojis, stickers & GIFs"; tapping it
 * opens Sheet. Also serves requests from the companion app (it starts Gboard's settings activity
 * with an extra, see handleRequest).
 *
 * Gboard's settings screen is a stock androidx PreferenceScreen of categories of HeaderPreference
 * rows, but R8 renamed every method. So nothing here relies on a method NAME: the few members we
 * need are found by signature at runtime, and the new row is a field-by-field clone of the Emojis
 * row (so it inherits Gboard's own layout/icon). The row is spliced into the emoji category by
 * hooking PreferenceGroup's child count/getter, which is what the list adapter reads.
 * If anything here fails it only logs; the keyboard and the font swap are unaffected.
 */
final class SettingsUi {
  static final String ANCHOR = "ExpressionSettingsFragment"; // fragment behind "Emojis, stickers & GIFs"
  static final ThreadLocal<Boolean> busy = new ThreadLocal<Boolean>();
  // per category instance: Gboard rebuilds its settings screens, and every rebuild needs its own row
  static final Map<Object, Integer> evaluated = Collections.synchronizedMap(new WeakHashMap<Object, Integer>());
  static final Map<Object, Object> rows = Collections.synchronizedMap(new WeakHashMap<Object, Object>());
  static final Handler MAIN = new Handler(Looper.getMainLooper());

  static Class<?> P, PG, PC;
  static Method count, get;

  static boolean busy() {
    return Boolean.TRUE.equals(busy.get());
  }

  static void install(ClassLoader cl) throws Exception {
    P = Class.forName("androidx.preference.Preference", false, cl);
    PG = Class.forName("androidx.preference.PreferenceGroup", false, cl);
    PC = Class.forName("androidx.preference.PreferenceCategory", false, cl);

    for (Method m : PG.getDeclaredMethods()) {
      if (Modifier.isStatic(m.getModifiers())) continue;
      Class<?>[] pt = m.getParameterTypes();
      if (pt.length == 0 && m.getReturnType() == int.class) count = m;
      if (pt.length == 1 && pt[0] == int.class && m.getReturnType() == P) get = m;
    }
    if (count == null || get == null) throw new IllegalStateException("PreferenceGroup count/get not found");
    count.setAccessible(true);
    get.setAccessible(true);

    // children count: one more for the category that holds the Emojis row
    XposedBridge.hookAllMethods(PG, count.getName(), new XC_MethodHook() {
      @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
        if (busy() || !PC.isInstance(p.thisObject)) return;
        int n = (Integer) p.getResult();
        if (rows.containsKey(p.thisObject)) {
          p.setResult(n + 1);
          return;
        }
        Integer last = evaluated.get(p.thisObject);
        if (last != null && last == n) return;
        evaluated.put(p.thisObject, n);
        busy.set(true);
        try {
          Object anchor = null;
          for (int i = 0; i < n && anchor == null; i++) {
            Object c = get.invoke(p.thisObject, i);
            if (c != null && hasStringEndingWith(c, ANCHOR)) anchor = c;
          }
          if (anchor != null) {
            rows.put(p.thisObject, cloneRow(anchor));
            Hook.log("settings row ready (category had " + n + " rows)");
            p.setResult(n + 1);
          }
        } catch (Throwable t) {
          Hook.log("settings row: " + android.util.Log.getStackTraceString(t));
        } finally {
          busy.set(false);
        }
      }
    });

    // child getter: the extra slot is our row
    XposedBridge.hookAllMethods(PG, get.getName(), new XC_MethodHook() {
      @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
        if (busy()) return;
        Object row = rows.get(p.thisObject);
        if (row == null) return;
        int oc;
        busy.set(true);
        try {
          oc = (Integer) count.invoke(p.thisObject);
        } finally {
          busy.set(false);
        }
        if ((Integer) p.args[0] == oc) p.setResult(row);
      }
    });

    // requests from the companion app: it starts Gboard's settings activity with an extra
    XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
      @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
        if (!(p.thisObject instanceof Activity) || !p.thisObject.getClass().getName().endsWith(".SettingsActivity")) return;
        if (p.args.length > 0 && p.args[0] != null) return; // recreated, not a fresh launch
        Activity a = (Activity) p.thisObject;
        Intent i = a.getIntent();
        String action = i == null ? null : i.getStringExtra("gmoji.action");
        if (action == null) return;
        Uri ref = a.getReferrer(); // set by the system, so other apps can't claim to be us
        if (ref == null || !"co.edualexxis.gmoji".equals(ref.getHost())) {
          Hook.log("ignored gmoji request from " + ref);
          return;
        }
        try {
          handleRequest(a, i, action);
        } catch (Throwable t) {
          Hook.log("request " + action + ": " + android.util.Log.getStackTraceString(t));
        }
        a.overridePendingTransition(0, 0);
        a.finish(); // back to the companion app; any real work continues in the background
      }
    });

    // result of the file picker opened from the sheet
    XposedBridge.hookAllMethods(Activity.class, "onActivityResult", new XC_MethodHook() {
      @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
        if ((Integer) p.args[0] != Sheet.REQ_IMPORT || !(p.thisObject instanceof Activity)) return;
        if ((Integer) p.args[1] != Activity.RESULT_OK || p.args[2] == null) return;
        Uri uri = ((Intent) p.args[2]).getData();
        if (uri != null) {
          Activity a = (Activity) p.thisObject;
          importFont(a, uri, "f" + System.currentTimeMillis(), Ui.stripExt(displayName(a, uri)));
        }
      }
    });
  }

  /** gmoji.action: state | import | activate | remove | reset (the companion app is the only caller allowed). */
  static void handleRequest(Activity a, Intent i, String action) throws Exception {
    Store s = Store.I;
    Context app = a.getApplicationContext();
    String id = i.getStringExtra("gmoji.id");
    if ("state".equals(action)) {
      Intent r = new Intent();
      JSONObject st = s.state();
      r.putExtra("json", st.toString());
      JSONArray fonts = st.getJSONArray("fonts");
      int budget = 600 * 1024; // keep the reply well under the binder limit
      for (int k = 0; k < fonts.length(); k++) {
        String fid = fonts.getJSONObject(k).getString("id");
        byte[] png = Previews.png(s.fileOf(fid));
        if (png != null && png.length < budget) {
          budget -= png.length;
          r.putExtra("png:" + fid, png);
        }
      }
      a.setResult(Activity.RESULT_OK, r);
    } else if ("import".equals(action) && i.getData() != null && id != null) {
      importFont(a, i.getData(), id, i.getStringExtra("gmoji.name") == null ? "Font" : i.getStringExtra("gmoji.name"));
    } else if ("activate".equals(action) && id != null) {
      if (s.activate(id)) restart(app, "Switched to " + s.nameOf(id));
      else toast(app, "That font is missing");
    } else if ("remove".equals(action) && id != null) {
      boolean was = id.equals(s.activeId());
      s.remove(id);
      if (was) restart(app, "Back to system emoji");
    } else if ("reset".equals(action)) {
      s.reset();
      restart(app, "Back to system emoji");
    }
  }

  // ---- finding things by shape ----------------------------------------------------------------

  static boolean hasStringEndingWith(Object o, String suffix) {
    for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
      for (Field f : c.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
        try {
          f.setAccessible(true);
          Object v = f.get(o);
          if (v instanceof String && ((String) v).endsWith(suffix)) return true;
        } catch (Throwable ignored) {
        }
      }
    }
    return false;
  }

  /** Field-by-field copy of the Emojis row, retitled and detached from any navigation. */
  static Object cloneRow(Object src) throws Exception {
    Class<?> uc = Class.forName("sun.misc.Unsafe");
    Field uf = uc.getDeclaredField("theUnsafe");
    uf.setAccessible(true);
    Object unsafe = uf.get(null);
    Object row = uc.getMethod("allocateInstance", Class.class).invoke(unsafe, src.getClass());

    // the title is whichever CharSequence getter of Preference returns something for the source row
    String srcTitle = null;
    for (Method m : P.getDeclaredMethods()) {
      if (Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 0 || m.getReturnType() != CharSequence.class) continue;
      m.setAccessible(true);
      Object v = m.invoke(src);
      if (v != null && srcTitle == null) srcTitle = v.toString();
    }

    for (Class<?> c = src.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
      for (Field f : c.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers())) continue;
        f.setAccessible(true);
        Object v = f.get(src);
        Class<?> t = f.getType();
        if (t == View.OnClickListener.class) {
          // the source row's listener is bound to the SOURCE row (it would open the Emojis page)
          v = new View.OnClickListener() {
            @Override public void onClick(View view) {
              Activity a = activityOf(view.getContext());
              if (a != null) Sheet.show(a);
            }
          };
        } else if (t == String.class || t == Intent.class) {
          v = null; // key, fragment, dependency, intent: nothing may navigate or persist
        } else if (t == long.class) {
          f.setLong(row, f.getLong(src) ^ 0x4D4F4A49L); // distinct stable id
          continue;
        } else if (t == CharSequence.class) {
          v = srcTitle != null && srcTitle.contentEquals((CharSequence) (v == null ? "" : v)) ? "Gmoji" : summary();
        }
        f.set(row, v);
      }
    }
    return row;
  }

  static String summary() {
    Store s = Store.I;
    if (s == null) return null;
    String act = s.activeId();
    return act == null ? "Using the system emoji" : s.nameOf(act);
  }

  static Activity activityOf(Context c) {
    while (c instanceof ContextWrapper) {
      if (c instanceof Activity) return (Activity) c;
      c = ((ContextWrapper) c).getBaseContext();
    }
    return null;
  }

  // ---- applying a font ------------------------------------------------------------------------

  static void toast(final Context c, final String msg) {
    MAIN.post(new Runnable() {
      @Override public void run() {
        Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
      }
    });
  }

  static String displayName(Context c, Uri uri) {
    try {
      Cursor cur = c.getContentResolver().query(uri, null, null, null, null);
      try {
        if (cur != null && cur.moveToFirst()) {
          int i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
          if (i >= 0) return cur.getString(i);
        }
      } finally {
        if (cur != null) cur.close();
      }
    } catch (Throwable ignored) {
    }
    return uri.getLastPathSegment();
  }

  /**
   * Adds the font to the library and switches to it. The copy (it can be 100+ MB) runs in the
   * background; the stream is opened up front so the caller may finish its activity meanwhile.
   */
  static void importFont(Activity act, Uri uri, final String id, final String name) {
    final Context app = act.getApplicationContext();
    final InputStream in;
    try {
      in = act.getContentResolver().openInputStream(uri);
    } catch (Throwable t) {
      Hook.log("import open failed: " + android.util.Log.getStackTraceString(t));
      toast(app, "Couldn't read that font");
      return;
    }
    Store.busy.incrementAndGet();
    new Thread(new Runnable() {
      @Override public void run() {
        try {
          Store.I.importFont(in, id, name);
          Store.I.activate(id);
          Store.busy.decrementAndGet();
          restart(app, "Added " + name);
        } catch (Throwable t) {
          Store.busy.decrementAndGet();
          Hook.log("import failed: " + android.util.Log.getStackTraceString(t));
          toast(app, "That file isn't a usable font");
        }
      }
    }).start();
  }

  /** The Typeface swap happens once at process start, so Gboard has to restart to pick up changes. */
  static void restart(Context ctx, String msg) {
    toast(ctx, msg + ". Restarting Gboard…");
    MAIN.postDelayed(new Runnable() {
      @Override public void run() {
        Process.killProcess(Process.myPid());
      }
    }, 1200);
  }
}
