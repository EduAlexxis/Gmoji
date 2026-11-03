package com.edualexxis.gmoji;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The font library, kept inside Gboard's own storage (Gboard cannot see other apps' files).
 *
 *   files/gmoji/<id>.ttf   every imported font
 *   files/gmoji/library.json   [{"id","name"}]
 *   files/gmoji.ttf        symlink to the active font (this is what Hook loads at startup)
 *
 * The active font is simply where the symlink points, so there is no second piece of state to drift.
 */
final class Store {
  static Store I;
  static final AtomicInteger busy = new AtomicInteger(); // imports running in the background

  final File files, dir, libFile, link;

  static synchronized void init(File files) {
    if (I == null) I = new Store(files);
  }

  private Store(File files) {
    this.files = files;
    dir = new File(files, "gmoji");
    dir.mkdirs();
    libFile = new File(dir, "library.json");
    link = new File(files, Hook.FONT_NAME);
  }

  File fileOf(String id) {
    return new File(dir, id + ".ttf");
  }

  synchronized JSONArray fonts() {
    try {
      if (!libFile.exists()) return new JSONArray();
      java.io.FileInputStream in = new java.io.FileInputStream(libFile);
      try {
        byte[] b = new byte[(int) libFile.length()];
        int n = 0;
        while (n < b.length) {
          int r = in.read(b, n, b.length - n);
          if (r < 0) break;
          n += r;
        }
        return new JSONArray(new String(b, 0, n, "UTF-8"));
      } finally {
        in.close();
      }
    } catch (Throwable t) {
      return new JSONArray();
    }
  }

  synchronized void save(JSONArray a) throws Exception {
    FileOutputStream out = new FileOutputStream(libFile);
    try {
      out.write(a.toString().getBytes("UTF-8"));
    } finally {
      out.close();
    }
  }

  synchronized String nameOf(String id) {
    JSONArray a = fonts();
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o != null && id.equals(o.optString("id"))) return o.optString("name", id);
    }
    return id;
  }

  /** Id of the active font, or null for the system emoji. */
  String activeId() {
    try {
      if (!Files.isSymbolicLink(link.toPath())) return null;
      String n = Files.readSymbolicLink(link.toPath()).getFileName().toString();
      return n.endsWith(".ttf") ? n.substring(0, n.length() - 4) : null;
    } catch (Throwable t) {
      return null;
    }
  }

  /** A font from before the library existed was a plain file at files/gmoji.ttf: adopt it. */
  synchronized void migrateLegacy() throws Exception {
    if (!link.exists() || Files.isSymbolicLink(link.toPath())) return;
    File dst = fileOf("legacy");
    if (!link.renameTo(dst)) return;
    JSONArray a = fonts();
    a.put(new JSONObject().put("id", "legacy").put("name", "Imported font"));
    save(a);
    activate("legacy");
  }

  /** Copies the stream into the library (validating it as a font) and registers it. */
  synchronized void importFont(InputStream in, String id, String name) throws Exception {
    File tmp = new File(dir, id + ".tmp");
    try {
      FileOutputStream out = new FileOutputStream(tmp);
      try {
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
      } finally {
        out.close();
        in.close();
      }
      Fonts.openEmoji(tmp); // throws if it isn't a usable font (handles .ttc collections)
      if (!tmp.renameTo(fileOf(id))) throw new java.io.IOException("rename failed");
    } catch (Throwable t) {
      tmp.delete();
      throw t instanceof Exception ? (Exception) t : new Exception(t);
    }
    JSONArray a = fonts();
    a.put(new JSONObject().put("id", id).put("name", name));
    save(a);
  }

  synchronized boolean activate(String id) {
    try {
      if (!fileOf(id).exists()) return false;
      link.delete();
      Files.createSymbolicLink(link.toPath(), fileOf(id).toPath());
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  synchronized void reset() {
    link.delete();
  }

  synchronized void remove(String id) throws Exception {
    if (id.equals(activeId())) link.delete();
    fileOf(id).delete();
    JSONArray old = fonts(), keep = new JSONArray();
    for (int i = 0; i < old.length(); i++) {
      JSONObject o = old.optJSONObject(i);
      if (o != null && !id.equals(o.optString("id"))) keep.put(o);
    }
    save(keep);
  }

  /** What the companion app shows. */
  synchronized JSONObject state() throws Exception {
    JSONArray out = new JSONArray(), a = fonts();
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o == null || !fileOf(o.optString("id")).exists()) continue;
      out.put(new JSONObject().put("id", o.optString("id")).put("name", o.optString("name"))
          .put("mb", fileOf(o.optString("id")).length() / (1024 * 1024)));
    }
    String act = activeId();
    return new JSONObject().put("active", act == null ? "" : act).put("busy", busy.get() > 0).put("fonts", out);
  }
}
