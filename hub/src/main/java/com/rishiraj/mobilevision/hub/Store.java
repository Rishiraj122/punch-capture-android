package com.rishiraj.mobilevision.hub;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import androidx.exifinterface.media.ExifInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Phone-hub storage. Same folder layout and rules as the laptop server (app.py):
 * PunchCapture/<Variant>/<Color>/<interior|exterior>/<checkpoint>_<Variant>/<Variant>_<checkpoint>_<Color>_<date>_<time>.jpg
 *
 * Every change is written to events.jsonl (add / delete / move) so the laptop can mirror it.
 * Once the laptop confirms it has a photo, the phone keeps only a small copy (1280 px) to save space.
 */
public class Store {
    public static final String VERSION = "2.1 hub";
    static final String[] SECTIONS = {"interior", "exterior"};
    static final Pattern VEH_RE = Pattern.compile("_V(\\d{3,})_(\\d{2,})\\.[A-Za-z]+$");
    static final Pattern STAMP_RE = Pattern.compile("^(.+)_(\\d{8}_\\d{6}(?:_\\d+)?)\\.(jpg|jpeg|png)$", Pattern.CASE_INSENSITIVE);
    static final Pattern VAR_ID = Pattern.compile("^[A-Za-z0-9_]+$");
    static final Pattern ITEM_ID = Pattern.compile("^[a-z0-9_]+$");
    static final int SLIM_SIDE = 1280;

    public static class UserError extends RuntimeException {
        UserError(String m) { super(m); }
    }

    private static Store instance;

    public static synchronized Store get(Context c) {
        if (instance == null) instance = new Store(c);
        return instance;
    }

    final Context ctx;
    final File root, cache, eventsFile;
    final String rootCanon;
    final Object lock = new Object();
    final Map<String, Integer> reserved = new HashMap<>();
    JSONObject index, results, synced, state;
    long seq = 0;

    private Store(Context c) {
        ctx = c.getApplicationContext();
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        root = new File(base, "PunchCapture");
        root.mkdirs();
        String rc;
        try { rc = root.getCanonicalPath(); } catch (IOException e) { rc = root.getAbsolutePath(); }
        rootCanon = rc;
        cache = new File(ctx.getCacheDir(), "img");
        cache.mkdirs();
        index = readJson(f("photo_index.json"));
        results = readJson(f("results.json"));
        synced = readJson(f("synced.json"));
        state = readJson(f("sync_state.json"));
        try {
            if (!state.has("hubId")) {
                state.put("hubId", UUID.randomUUID().toString().substring(0, 8));
                writeJson(f("sync_state.json"), state);
            }
        } catch (Exception ignored) { }
        eventsFile = f("events.jsonl");
        for (JSONObject e : readEvents(0, Integer.MAX_VALUE)) seq = Math.max(seq, e.optLong("seq"));
    }

    File f(String name) { return new File(root, name); }

    public File root() { return root; }

    // ------------------------------------------------------------ small io helpers
    static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) { return new String(readAll(in), StandardCharsets.UTF_8); }
    }

    static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        copy(in, bos);
        return bos.toByteArray();
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    static void writeText(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) { out.write(s.getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(f)) {
            try (OutputStream out = new FileOutputStream(f)) { out.write(s.getBytes(StandardCharsets.UTF_8)); }
            tmp.delete();
        }
    }

    static JSONObject readJson(File f) {
        try { return f.exists() ? new JSONObject(readText(f)) : new JSONObject(); } catch (Exception e) { return new JSONObject(); }
    }

    static void writeJson(File f, JSONObject o) throws Exception { writeText(f, o.toString(1)); }

    static String now() { return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date()); }

    // ------------------------------------------------------------ paths
    String relOf(File p) {
        String c;
        try { c = p.getCanonicalPath(); } catch (IOException e) { c = p.getAbsolutePath(); }
        if (c.equals(rootCanon)) return "";
        return c.substring(rootCanon.length() + 1).replace(File.separatorChar, '/');
    }

    File underRoot(String rel) throws IOException {
        File p = (rel == null || rel.isEmpty()) ? root : new File(root, rel);
        String c = p.getCanonicalPath();
        if (!c.equals(rootCanon) && !c.startsWith(rootCanon + File.separator)) throw new UserError("Not found.");
        return p;
    }

    static boolean isPhoto(File p) {
        String n = p.getName().toLowerCase(Locale.US);
        return p.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"));
    }

    static String colorPart(String color) {
        String c = color == null ? "" : color.trim().replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return c.isEmpty() ? "NoColor" : c;
    }

    File cpFolder(String vid, String color, String sec, String cid) {
        return new File(root, vid + "/" + colorPart(color) + "/" + sec + "/" + cid + "_" + vid);
    }

    static String cidFromFolder(String name, String vid) {
        String s = "_" + vid;
        return name.endsWith(s) ? name.substring(0, name.length() - s.length()) : name;
    }

    static void walk(File d, List<File> out) {
        File[] list = d.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) walk(f, out);
            else if (isPhoto(f)) out.add(f);
        }
    }

    static List<File> walkPhotos(File d) {
        List<File> out = new ArrayList<>();
        walk(d, out);
        Collections.sort(out);
        return out;
    }

    static void pruneEmpty(File d, File stop) {
        while (d != null && !d.equals(stop) && d.isDirectory()) {
            String[] left = d.list();
            if (left == null || left.length > 0 || !d.delete()) return;
            d = d.getParentFile();
        }
    }

    // ------------------------------------------------------------ config & vehicles
    public JSONObject loadConfig() throws Exception {
        File cf = f("config.json");
        if (!cf.exists()) {
            try (InputStream in = ctx.getAssets().open("config.json")) { writeText(cf, new String(readAll(in), StandardCharsets.UTF_8)); }
        }
        return new JSONObject(readText(cf));
    }

    static void validateItems(JSONArray arr, String what) throws Exception {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.getJSONObject(i);
            String id = c.optString("id"), name = c.optString("name").trim();
            if (!ITEM_ID.matcher(id).matches() || name.isEmpty()) throw new UserError("Bad " + what + " entry: " + name);
            if (!seen.add(id)) throw new UserError("'" + name + "' is listed twice in " + what + ".");
        }
    }

    public JSONObject putConfig(String js) throws Exception {
        synchronized (lock) {
            JSONObject cfg = new JSONObject(js);
            if (!cfg.has("colors")) cfg.put("colors", new JSONArray());
            validateItems(cfg.getJSONArray("colors"), "colors");
            JSONObject common = cfg.optJSONObject("common");
            if (common == null) throw new UserError("Common checkpoints are missing.");
            for (String s : SECTIONS) validateItems(common.getJSONArray(s), "common " + s);
            JSONArray vars = cfg.getJSONArray("variants");
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < vars.length(); i++) {
                JSONObject v = vars.getJSONObject(i);
                if (!VAR_ID.matcher(v.optString("id")).matches() || v.optString("name").trim().isEmpty())
                    throw new UserError("Bad variant: " + v.optString("id"));
                if (!ids.add(v.getString("id").toLowerCase(Locale.US)))
                    throw new UserError("Variant '" + v.optString("name") + "' already exists.");
                for (String s : SECTIONS) validateItems(v.getJSONArray(s), v.optString("name") + " " + s);
            }
            writeJson(f("config.json"), cfg);
            return cfg;
        }
    }

    JSONObject vehicles() { return readJson(f("vehicles.json")); }

    String vehicleColor(String vid, int n) {
        JSONObject m = vehicles().optJSONObject(vid);
        return m == null ? "" : m.optString(String.valueOf(n), "");
    }

    void saveVehicleColor(String vid, int n, String color) throws Exception {
        JSONObject all = vehicles();
        JSONObject m = all.optJSONObject(vid);
        if (m == null) { m = new JSONObject(); all.put(vid, m); }
        if (color == null || color.isEmpty()) m.remove(String.valueOf(n));
        else m.put(String.valueOf(n), color);
        writeJson(f("vehicles.json"), all);
        StringBuilder csv = new StringBuilder("variant,vehicle,color\n");
        List<String> vids = new ArrayList<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) vids.add(it.next());
        Collections.sort(vids);
        for (String id : vids) {
            JSONObject vm = all.getJSONObject(id);
            List<Integer> nums = new ArrayList<>();
            for (Iterator<String> it = vm.keys(); it.hasNext(); ) nums.add(Integer.parseInt(it.next()));
            Collections.sort(nums);
            for (int k : nums)
                csv.append(id).append(',').append(String.format(Locale.US, "V%03d", k)).append(',')
                        .append(vm.getString(String.valueOf(k)).replace(",", " ")).append('\n');
        }
        writeText(f("vehicles.csv"), csv.toString());
    }

    static JSONObject findVariant(JSONObject cfg, String vid) throws Exception {
        JSONArray a = cfg.getJSONArray("variants");
        for (int i = 0; i < a.length(); i++) if (a.getJSONObject(i).getString("id").equals(vid)) return a.getJSONObject(i);
        throw new UserError("Variant '" + vid + "' not found.");
    }

    static JSONObject findCheckpoint(JSONObject v, String sec, String cid) throws Exception {
        if (!"interior".equals(sec) && !"exterior".equals(sec)) throw new UserError("Section must be interior or exterior.");
        JSONArray a = v.getJSONArray(sec);
        for (int i = 0; i < a.length(); i++) if (a.getJSONObject(i).getString("id").equals(cid)) return a.getJSONObject(i);
        throw new UserError("Checkpoint '" + cid + "' not found in " + sec + ".");
    }

    // ------------------------------------------------------------ photos
    int vehicleOf(File p) {
        int v = index.optInt(relOf(p), 0);
        if (v > 0) return v;
        Matcher m = VEH_RE.matcher(p.getName());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    List<File> photosFor(String vid, String sec, String cid, int vehicle) {
        File folder = cpFolder(vid, vehicleColor(vid, vehicle), sec, cid);
        List<File> out = new ArrayList<>();
        File[] list = folder.listFiles();
        if (list == null) return out;
        for (File p : list) if (isPhoto(p) && vehicleOf(p) == vehicle) out.add(p);
        Collections.sort(out);
        return out;
    }

    int maxVehicle(String vid) {
        int best = 0;
        for (File p : walkPhotos(new File(root, vid))) {
            Matcher m = VEH_RE.matcher(p.getName());
            if (m.find()) best = Math.max(best, Integer.parseInt(m.group(1)));
        }
        for (Iterator<String> it = index.keys(); it.hasNext(); ) {
            String k = it.next();
            if (k.startsWith(vid + "/")) best = Math.max(best, index.optInt(k, 0));
        }
        return best;
    }

    JSONObject photoJson(File p) throws Exception {
        String rel = relOf(p);
        JSONObject o = new JSONObject();
        o.put("path", rel);
        o.put("name", p.getName());
        o.put("thumb", "/thumb/" + rel + "?v=" + p.lastModified());
        Object r = results.opt(rel);
        o.put("result", r == null ? JSONObject.NULL : r);
        o.put("synced", synced.has(rel));
        return o;
    }

    void appendLog(String... cols) {
        File log = f("capture_log.csv");
        boolean isNew = !log.exists();
        try (FileWriter w = new FileWriter(log, true)) {
            if (isNew) w.write("timestamp,variant,vehicle,section,checkpoint,file,action,device\n");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cols.length; i++) {
                if (i > 0) sb.append(',');
                String c = cols[i] == null ? "" : cols[i];
                if (c.contains(",") || c.contains("\"")) c = "\"" + c.replace("\"", "\"\"") + "\"";
                sb.append(c);
            }
            w.write(sb.append('\n').toString());
        } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------ change log for the laptop
    void appendEvent(JSONObject e) throws Exception {
        seq++;
        e.put("seq", seq);
        e.put("t", System.currentTimeMillis());
        try (FileWriter w = new FileWriter(eventsFile, true)) { w.write(e.toString() + "\n"); }
    }

    List<JSONObject> readEvents(long after, int limit) {
        List<JSONObject> out = new ArrayList<>();
        if (!eventsFile.exists()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(eventsFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null && out.size() < limit) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JSONObject e = new JSONObject(line);
                    if (e.optLong("seq") > after) out.add(e);
                } catch (Exception ignored) { }
            }
        } catch (IOException ignored) { }
        return out;
    }

    void saveIndexes() throws Exception {
        writeJson(f("photo_index.json"), index);
        writeJson(f("results.json"), results);
        writeJson(f("synced.json"), synced);
    }

    boolean movePhoto(File src, File dst, int vehicle) throws Exception {
        if (src.equals(dst) || dst.exists()) return false;
        dst.getParentFile().mkdirs();
        String oldRel = relOf(src);
        if (!src.renameTo(dst)) return false;
        String newRel = relOf(dst);
        index.remove(oldRel);
        if (vehicle > 0) index.put(newRel, vehicle);
        if (results.has(oldRel)) { results.put(newRel, results.get(oldRel)); results.remove(oldRel); }
        if (synced.has(oldRel)) { synced.put(newRel, true); synced.remove(oldRel); }
        JSONObject e = new JSONObject();
        e.put("type", "move"); e.put("from", oldRel); e.put("to", newRel); e.put("vehicle", vehicle);
        appendEvent(e);
        return true;
    }

    // ------------------------------------------------------------ images
    static int exifDegrees(File f) {
        try { return new ExifInterface(f.getAbsolutePath()).getRotationDegrees(); } catch (Exception e) { return 0; }
    }

    /** Decodes a downscaled, upright bitmap with its longest side at most maxSide. */
    static Bitmap decodeUpright(File src, int maxSide) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getAbsolutePath(), b);
        int side = Math.max(b.outWidth, b.outHeight), sample = 1;
        while (side / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(src.getAbsolutePath(), o);
        if (bmp == null) return null;
        int longest = Math.max(bmp.getWidth(), bmp.getHeight());
        float scale = longest > maxSide ? (float) maxSide / longest : 1f;
        int deg = exifDegrees(src);
        if (scale < 1f || deg != 0) {
            Matrix m = new Matrix();
            if (scale < 1f) m.postScale(scale, scale);
            if (deg != 0) m.postRotate(deg);
            Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (r != bmp) bmp.recycle();
            bmp = r;
        }
        return bmp;
    }

    public File thumbFile(File src, int size) throws IOException {
        File out = new File(cache, size + "/" + relOf(src) + "." + src.lastModified() + ".jpg");
        if (out.exists()) return out;
        out.getParentFile().mkdirs();
        Bitmap bmp = decodeUpright(src, size);
        if (bmp == null) return src;
        File tmp = new File(out.getPath() + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) { bmp.compress(Bitmap.CompressFormat.JPEG, 82, os); }
        bmp.recycle();
        tmp.renameTo(out);
        return out;
    }

    /** After the laptop has the full photo, keep a 1280 px upright copy here (same name and time). */
    void slim(File p) {
        try {
            long mtime = p.lastModified();
            Bitmap bmp = decodeUpright(p, SLIM_SIDE);
            if (bmp == null) return;
            File tmp = new File(p.getPath() + ".slim");
            try (OutputStream os = new FileOutputStream(tmp)) { bmp.compress(Bitmap.CompressFormat.JPEG, 85, os); }
            bmp.recycle();
            if (p.delete() && tmp.renameTo(p)) p.setLastModified(mtime);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ quick quality check (same rules as the laptop's processing.py)
    static final double BLUR_MIN_SHARPNESS = 60.0, DARK_MAX = 45.0, BRIGHT_MIN = 215.0;

    static JSONObject quality(File f) {
        JSONObject o = new JSONObject();
        try {
            long t0 = System.currentTimeMillis();
            Bitmap bmp = decodeUpright(f, 800);
            if (bmp == null) return o;
            int w = bmp.getWidth(), h = bmp.getHeight();
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            bmp.recycle();
            float[] g = new float[w * h];
            double sum = 0;
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                float v = 0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255);
                g[i] = v;
                sum += v;
            }
            double mean = sum / g.length, ls = 0, ls2 = 0;
            long n = 0;
            for (int y = 1; y < h - 1; y++)
                for (int x = 1; x < w - 1; x++) {
                    int i = y * w + x;
                    double l = 4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w];
                    ls += l; ls2 += l * l; n++;
                }
            double var = n > 0 ? ls2 / n - (ls / n) * (ls / n) : 0;
            JSONArray checks = new JSONArray();
            if (var < BLUR_MIN_SHARPNESS) checks.put("Looks blurry");
            if (mean < DARK_MAX) checks.put("Too dark");
            else if (mean > BRIGHT_MIN) checks.put("Too bright");
            o.put("sharpness", Math.round(var * 10) / 10.0);
            o.put("brightness", Math.round(mean * 10) / 10.0);
            o.put("checks", checks);
            o.put("status", checks.length() > 0 ? "warn" : "ok");
            o.put("ms", System.currentTimeMillis() - t0);
            o.put("by", "phone");
        } catch (Throwable ignored) { }
        return o;
    }

    // ------------------------------------------------------------ processing (simulation now, trained model later)
    /** Settings set from the laptop page: {enabled, delayMs, ngRate}. */
    public JSONObject sim() throws Exception {
        JSONObject o = readJson(f("simulation.json"));
        if (!o.has("enabled")) o.put("enabled", false);
        if (!o.has("delayMs")) o.put("delayMs", 1000);
        if (!o.has("ngRate")) o.put("ngRate", 10);
        return o;
    }

    public JSONObject putSim(String js) throws Exception {
        JSONObject in = new JSONObject(js), o = new JSONObject();
        o.put("enabled", in.optBoolean("enabled", false));
        o.put("delayMs", Math.max(0, Math.min(10000, in.optInt("delayMs", 1000))));
        o.put("ngRate", Math.max(0, Math.min(100, in.optInt("ngRate", 10))));
        writeJson(f("simulation.json"), o);
        return o;
    }

    final java.util.Random rnd = new java.util.Random();

    /**
     * Runs after a photo is saved, before the Zebra gets its answer.
     * Today: simulation (wait delayMs, then OK or NG at ngRate %).
     * Later: replace the simulated part with the trained model (e.g. a TFLite classifier) and keep the timing.
     */
    JSONObject process(File photo, String vid, String sec, String cid) throws Exception {
        JSONObject cfg = sim();
        if (!cfg.optBoolean("enabled")) return null;
        long t0 = System.currentTimeMillis();
        int delay = cfg.optInt("delayMs", 1000);
        if (delay > 0) Thread.sleep(delay);
        boolean ng = rnd.nextInt(100) < cfg.optInt("ngRate", 10);
        return new JSONObject().put("verdict", ng ? "NG" : "OK").put("ms", System.currentTimeMillis() - t0)
                .put("simulated", true).put("at", System.currentTimeMillis());
    }

    public JSONObject procStats() throws Exception {
        List<JSONObject> rs = new ArrayList<>();
        for (Iterator<String> it = results.keys(); it.hasNext(); ) {
            JSONObject r = results.optJSONObject(it.next());
            if (r != null && r.has("verdict")) rs.add(r);
        }
        Collections.sort(rs, (a, b) -> Long.compare(b.optLong("at"), a.optLong("at")));
        long sum = 0, min = Long.MAX_VALUE, max = 0;
        int ok = 0, ng = 0;
        JSONArray recent = new JSONArray();
        for (int i = 0; i < rs.size(); i++) {
            JSONObject r = rs.get(i);
            long ms = r.optLong("ms");
            sum += ms; min = Math.min(min, ms); max = Math.max(max, ms);
            if ("NG".equals(r.optString("verdict"))) ng++; else ok++;
            if (i < 20) recent.put(new JSONObject().put("ms", ms).put("verdict", r.optString("verdict")).put("at", r.optLong("at")));
        }
        return new JSONObject().put("count", rs.size()).put("ok", ok).put("ng", ng)
                .put("avgMs", rs.isEmpty() ? 0 : sum / rs.size()).put("minMs", rs.isEmpty() ? 0 : min).put("maxMs", max)
                .put("recent", recent);
    }

    // ------------------------------------------------------------ laptop view: variants and downloads
    public JSONObject variants() throws Exception {
        JSONObject cfg = loadConfig(), veh = vehicles();
        Map<String, String> names = new HashMap<>();
        List<String> order = new ArrayList<>();
        JSONArray cv = cfg.getJSONArray("variants");
        for (int i = 0; i < cv.length(); i++) {
            names.put(cv.getJSONObject(i).getString("id"), cv.getJSONObject(i).getString("name"));
            order.add(cv.getJSONObject(i).getString("id"));
        }
        File[] dirs = root.listFiles();
        if (dirs != null) for (File d : dirs) if (d.isDirectory() && !order.contains(d.getName())) order.add(d.getName());
        JSONArray out = new JSONArray();
        long totalBytes = 0;
        int totalPhotos = 0;
        for (String id : order) {
            List<File> ps = walkPhotos(new File(root, id));
            long bytes = 0, latest = 0;
            Set<Integer> vs = new HashSet<>();
            Set<String> colors = new HashSet<>();
            for (File p : ps) {
                bytes += p.length();
                latest = Math.max(latest, p.lastModified());
                int n = vehicleOf(p);
                if (n > 0) vs.add(n);
                colors.add(p.getParentFile().getParentFile().getParentFile().getName().replace('_', ' '));
            }
            JSONArray cl = new JSONArray();
            List<String> cs = new ArrayList<>(colors);
            Collections.sort(cs);
            for (String c : cs) cl.put(c);
            out.put(new JSONObject().put("id", id).put("name", names.containsKey(id) ? names.get(id) : id)
                    .put("photos", ps.size()).put("bytes", bytes).put("vehicles", vs.size())
                    .put("colors", cl).put("latest", latest));
            totalBytes += bytes;
            totalPhotos += ps.size();
        }
        return new JSONObject().put("variants", out).put("photos", totalPhotos).put("bytes", totalBytes)
                .put("freeMB", root.getUsableSpace() / (1024 * 1024));
    }

    /** Newest first. */
    public JSONObject variantPhotos(String vid, int offset, int limit) throws Exception {
        File d = underRoot(vid);
        List<File> ps = walkPhotos(d);
        Collections.sort(ps, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        JSONArray out = new JSONArray();
        for (int i = offset; i < Math.min(ps.size(), offset + limit); i++) {
            File p = ps.get(i);
            JSONObject j = photoJson(p);
            File cdir = p.getParentFile();
            j.put("time", p.lastModified()).put("vehicle", vehicleOf(p))
             .put("checkpoint", cidFromFolder(cdir.getName(), vid)).put("section", cdir.getParentFile().getName())
             .put("color", cdir.getParentFile().getParentFile().getName().replace('_', ' '));
            out.put(j);
        }
        return new JSONObject().put("photos", out).put("total", ps.size());
    }

    /**
     * Streams a zip with one flat folder per variant:
     *   Punch_Pure/Punch_Pure_Calypso_Red_20261009_101512_exterior_orvm_left.jpg
     *   Punch_Pure/photos.csv   (file, vehicle, color, section, checkpoint, time, quality)
     * vid null = every variant.
     */
    public void writeZip(String vid, OutputStream os) throws IOException {
        List<String> vids = new ArrayList<>();
        if (vid != null) vids.add(vid);
        else {
            File[] dirs = root.listFiles();
            if (dirs != null) for (File d : dirs) if (d.isDirectory()) vids.add(d.getName());
            Collections.sort(vids);
        }
        ZipOutputStream z = new ZipOutputStream(os);
        z.setLevel(java.util.zip.Deflater.NO_COMPRESSION);   // photos are already compressed
        SimpleDateFormat stampFmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        for (String v : vids) {
            List<File> ps = walkPhotos(underRoot(v));
            Collections.sort(ps, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            Set<String> used = new HashSet<>();
            StringBuilder csv = new StringBuilder("file,vehicle,color,section,checkpoint,time\n");
            for (File p : ps) {
                File cdir = p.getParentFile(), sdir = cdir.getParentFile(), coldir = sdir.getParentFile();
                String sec = sdir.getName(), color = coldir.getName(), cid = cidFromFolder(cdir.getName(), v);
                Matcher m = STAMP_RE.matcher(p.getName());
                // yyyyMMdd_HHmmss from the stored name (drop a same-second "_2" suffix), else the file time
                String stamp = m.matches() ? m.group(2).substring(0, 15) : stampFmt.format(new Date(p.lastModified()));
                String pn = p.getName(), ext = pn.substring(pn.lastIndexOf('.') + 1).toLowerCase(Locale.US);
                String base = v + "_" + color + "_" + stamp + "_" + sec + "_" + cid, name = base + "." + ext;
                int k = 2;
                while (!used.add(name)) name = base + "_" + (k++) + "." + ext;
                ZipEntry e = new ZipEntry(v + "/" + name);
                e.setTime(p.lastModified());
                z.putNextEntry(e);
                try (InputStream in = new FileInputStream(p)) { copy(in, z); }
                z.closeEntry();
                JSONObject r = results.optJSONObject(relOf(p));
                String checks = "";
                if (r != null && r.optJSONArray("checks") != null) {
                    JSONArray c = r.optJSONArray("checks");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < c.length(); i++) { if (i > 0) sb.append("; "); sb.append(c.optString(i)); }
                    checks = sb.toString();
                }
                int veh = vehicleOf(p);
                csv.append(name).append(',').append(veh > 0 ? String.format(Locale.US, "V%03d", veh) : "").append(',')
                   .append(color.replace('_', ' ')).append(',').append(sec).append(',').append(cid).append(',')
                   .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(p.lastModified()))).append('\n');
            }
            if (!ps.isEmpty()) {
                z.putNextEntry(new ZipEntry(v + "/photos.csv"));
                z.write(csv.toString().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        z.finish();
        z.flush();
    }

    // ------------------------------------------------------------ actions used by the capture screens
    public JSONObject storage() throws Exception {
        JSONObject o = new JSONObject();
        o.put("granted", true); o.put("root", "phone hub"); o.put("version", VERSION); o.put("hub", true);
        return o;
    }

    public JSONObject lastVehicles() throws Exception {
        JSONObject out = new JSONObject();
        JSONArray a = loadConfig().getJSONArray("variants");
        for (int i = 0; i < a.length(); i++) {
            String id = a.getJSONObject(i).getString("id");
            out.put(id, maxVehicle(id));
        }
        return out;
    }

    public JSONObject newVehicle(String vid) throws Exception {
        findVariant(loadConfig(), vid);
        synchronized (lock) {
            Integer r = reserved.get(vid);
            int n = Math.max(maxVehicle(vid), r == null ? 0 : r) + 1;
            reserved.put(vid, n);
            return new JSONObject().put("vehicle", n);
        }
    }

    public JSONObject progress(String vid, int vehicle) throws Exception {
        JSONObject v = findVariant(loadConfig(), vid), out = new JSONObject();
        for (String s : SECTIONS) {
            JSONObject sec = new JSONObject();
            JSONArray cps = v.getJSONArray(s);
            for (int i = 0; i < cps.length(); i++) {
                String cid = cps.getJSONObject(i).getString("id");
                JSONArray shots = new JSONArray();
                for (File p : photosFor(vid, s, cid, vehicle)) shots.put(photoJson(p));
                sec.put(cid, shots);
            }
            out.put(s, sec);
        }
        out.put("color", vehicleColor(vid, vehicle));
        return out;
    }

    public JSONObject setVehicleColor(String vid, int vehicle, String color) throws Exception {
        findVariant(loadConfig(), vid);
        int n = 0;
        synchronized (lock) {
            saveVehicleColor(vid, vehicle, color);
            File vdir = new File(root, vid);
            for (File p : walkPhotos(vdir)) {
                if (vehicleOf(p) != vehicle) continue;
                File cdir = p.getParentFile();
                String sec = cdir.getParentFile().getName();
                if (!"interior".equals(sec) && !"exterior".equals(sec)) continue;
                String cid = cidFromFolder(cdir.getName(), vid);
                String name = p.getName();
                Matcher m = STAMP_RE.matcher(name);
                if (m.matches() && name.startsWith(vid + "_" + cid + "_"))
                    name = vid + "_" + cid + "_" + colorPart(color) + "_" + m.group(2) + "." + m.group(3).toLowerCase(Locale.US);
                if (movePhoto(p, new File(cpFolder(vid, color, sec, cid), name), vehicle)) {
                    n++;
                    pruneEmpty(cdir, vdir);
                }
            }
            if (n > 0) saveIndexes();
        }
        return new JSONObject().put("color", color).put("renamed", n);
    }

    public JSONObject bigPreview(String rel) throws Exception {
        File p = underRoot(rel);
        if (!isPhoto(p)) throw new UserError("Photo not found.");
        return new JSONObject().put("url", "/preview/" + rel + "?v=" + p.lastModified());
    }

    public JSONObject deletePhoto(String rel) throws Exception {
        File p = underRoot(rel);
        if (!isPhoto(p)) throw new UserError("Photo not found.");
        synchronized (lock) {
            if (!p.delete()) throw new UserError("Could not delete the photo.");
            index.remove(rel); results.remove(rel); synced.remove(rel);
            appendEvent(new JSONObject().put("type", "delete").put("rel", rel));
            saveIndexes();
        }
        appendLog(now(), "", "", "", "", rel, "deleted", "");
        return new JSONObject().put("ok", true);
    }

    public JSONObject browse(String rel) throws Exception {
        File d = underRoot(rel);
        if (!d.isDirectory()) throw new UserError("Folder not found.");
        boolean top = relOf(d).isEmpty();
        JSONArray dirs = new JSONArray(), photos = new JSONArray();
        File[] list = d.listFiles();
        List<File> items = new ArrayList<>();
        if (list != null) Collections.addAll(items, list);
        Collections.sort(items);
        for (File p : items) {
            if (p.isDirectory()) {
                dirs.put(new JSONObject().put("name", p.getName()).put("rel", relOf(p)).put("count", walkPhotos(p).size()));
            } else if (!top && isPhoto(p)) {
                JSONObject j = photoJson(p);
                j.put("time", p.lastModified()); j.put("vehicle", vehicleOf(p));
                photos.put(j);
            }
        }
        return new JSONObject().put("rel", top ? "" : relOf(d)).put("dirs", dirs).put("photos", photos);
    }

    public JSONObject stats() throws Exception {
        JSONObject colors = vehicles();
        JSONArray out = new JSONArray();
        File[] vdirs = root.listFiles();
        if (vdirs != null) for (File vdir : vdirs) {
            if (!vdir.isDirectory()) continue;
            String vid = vdir.getName();
            JSONObject vc = colors.optJSONObject(vid);
            for (File p : walkPhotos(vdir)) {
                String sec = p.getParentFile().getParentFile().getName();
                if (!"interior".equals(sec) && !"exterior".equals(sec)) continue;
                int n = vehicleOf(p);
                JSONObject r = results.optJSONObject(relOf(p));
                out.put(new JSONObject().put("v", vid).put("s", sec).put("c", cidFromFolder(p.getParentFile().getName(), vid))
                        .put("n", n).put("col", vc == null ? "" : vc.optString(String.valueOf(n), ""))
                        .put("t", p.lastModified()).put("q", r == null ? "" : r.optString("status")));
            }
        }
        return new JSONObject().put("photos", out);
    }

    /** Saves an uploaded photo. replaceRel set = retake (the old photo is removed). */
    public JSONObject upload(String vid, String sec, String cid, int vehicle, String replaceRel, String device, File tmp, String origName) throws Exception {
        String ext = "jpg";
        if (origName != null) {
            String n = origName.toLowerCase(Locale.US);
            if (n.endsWith(".png")) ext = "png";
            else if (n.endsWith(".jpeg")) ext = "jpeg";
        }
        JSONObject v = findVariant(loadConfig(), vid);
        findCheckpoint(v, sec, cid);
        if (tmp == null || !tmp.exists() || tmp.length() == 0) throw new UserError("The photo was empty. Take it again.");
        String color = vehicleColor(vid, vehicle);
        File folder = cpFolder(vid, color, sec, cid);
        File target, old = null;
        synchronized (lock) {
            folder.mkdirs();
            if (replaceRel != null && !replaceRel.isEmpty()) {
                File o = underRoot(replaceRel);
                if (isPhoto(o) && o.getParentFile().getCanonicalFile().equals(folder.getCanonicalFile())) old = o;
            }
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String base = vid + "_" + cid + "_" + colorPart(color) + "_" + stamp;
            target = new File(folder, base + "." + ext);
            int k = 2;
            while (target.exists()) target = new File(folder, base + "_" + (k++) + "." + ext);
            File part = new File(folder, target.getName() + ".part");
            try (InputStream in = new FileInputStream(tmp); OutputStream out = new FileOutputStream(part)) { copy(in, out); }
            if (!part.renameTo(target)) throw new IOException("could not finish writing the file");
            String rel = relOf(target);
            index.put(rel, vehicle);
            if (old != null) {
                String oldRel = relOf(old);
                index.remove(oldRel); results.remove(oldRel); synced.remove(oldRel);
                old.delete();
                appendEvent(new JSONObject().put("type", "delete").put("rel", oldRel));
            }
            appendEvent(new JSONObject().put("type", "add").put("rel", rel).put("vehicle", vehicle)
                    .put("variant", vid).put("section", sec).put("checkpoint", cid).put("color", color));
            Integer r = reserved.get(vid);
            reserved.put(vid, Math.max(r == null ? 0 : r, vehicle));
            saveIndexes();
        }
        String rel = relOf(target);
        JSONObject pr = process(target, vid, sec, cid);
        if (pr != null) synchronized (lock) { results.put(rel, pr); writeJson(f("results.json"), results); }
        appendLog(now(), vid, String.format(Locale.US, "V%03d", vehicle), sec, cid, rel, old != null ? "retaken" : "saved", device);
        JSONObject o = photoJson(target);
        o.put("ok", true); o.put("replaced", old != null); o.put("variant", vid); o.put("section", sec);
        o.put("checkpoint", cid); o.put("vehicle", vehicle); o.put("folder", folder.getName());
        return o;
    }

    // ------------------------------------------------------------ sync API used by the laptop
    void touchLaptop(String who) {
        try {
            state.put("lastSeen", System.currentTimeMillis());
            if (who != null) state.put("laptop", who);
        } catch (Exception ignored) { }
    }

    public JSONObject hello() throws Exception {
        return new JSONObject().put("hub", true).put("id", state.optString("hubId")).put("version", VERSION).put("seq", seq);
    }

    public JSONObject events(long after, int limit) throws Exception {
        JSONArray a = new JSONArray();
        for (JSONObject e : readEvents(after, limit)) a.put(e);
        return new JSONObject().put("events", a).put("seq", seq).put("id", state.optString("hubId"));
    }

    public File syncFile(String rel) throws IOException {
        File p = underRoot(rel);
        return isPhoto(p) ? p : null;
    }

    public boolean isSynced(String rel) { return synced.has(rel); }

    public JSONObject putResults(String js) throws Exception {
        JSONObject in = new JSONObject(js);
        synchronized (lock) {
            for (Iterator<String> it = in.keys(); it.hasNext(); ) {
                String rel = it.next();
                if (underRoot(rel).exists()) results.put(rel, in.get(rel));
            }
            writeJson(f("results.json"), results);
        }
        return new JSONObject().put("ok", true);
    }

    /** The laptop has everything up to seq: shrink those photos here. */
    public JSONObject ack(long upTo) throws Exception {
        long prev = state.optLong("ack", 0);
        List<File> toSlim = new ArrayList<>();
        synchronized (lock) {
            if (upTo > prev) {
                for (JSONObject e : readEvents(prev, Integer.MAX_VALUE)) {
                    if (e.optLong("seq") > upTo) break;
                    if (!"add".equals(e.optString("type"))) continue;
                    String rel = e.optString("rel");
                    File p = new File(root, rel);
                    if (p.exists() && !synced.has(rel)) { synced.put(rel, true); toSlim.add(p); }
                }
                state.put("ack", upTo);
            }
            state.put("lastSeen", System.currentTimeMillis());
            writeJson(f("sync_state.json"), state);
            writeJson(f("synced.json"), synced);
        }
        // Full-size photos stay on the phone: the laptop downloads them from the browser.
        return new JSONObject().put("ok", true).put("slimmed", 0);
    }

    public JSONObject meta() throws Exception {
        JSONObject o = new JSONObject();
        o.put("config", loadConfig());
        o.put("vehicles", vehicles());
        o.put("index", index);
        File log = f("capture_log.csv");
        o.put("log", log.exists() ? readText(log) : "");
        return o;
    }

    public JSONObject syncStatus() throws Exception {
        long ack = state.optLong("ack", 0), last = state.optLong("lastSeen", 0);
        Set<String> waiting = new HashSet<>();
        for (JSONObject e : readEvents(ack, Integer.MAX_VALUE)) {
            String t = e.optString("type");
            if ("add".equals(t)) waiting.add(e.optString("rel"));
            else if ("delete".equals(t)) waiting.remove(e.optString("rel"));
            else if ("move".equals(t) && waiting.remove(e.optString("from"))) waiting.add(e.optString("to"));
        }
        int total = walkPhotos(root).size();
        return new JSONObject().put("laptopSeenMs", last == 0 ? -1 : System.currentTimeMillis() - last)
                .put("laptop", state.optString("laptop", "")).put("waiting", waiting.size()).put("photos", total)
                .put("synced", synced.length()).put("freeMB", root.getUsableSpace() / (1024 * 1024));
    }
}
