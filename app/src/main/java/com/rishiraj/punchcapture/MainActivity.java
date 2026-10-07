package com.rishiraj.punchcapture;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {

    private static final int REQ_CAMERA = 41;
    private static final int REQ_STORAGE = 42;
    private static final String[] SECTIONS = {"interior", "exterior"};
    private static final Pattern VEH_RE = Pattern.compile("_V(\\d{3,})_(\\d{2,})\\.[A-Za-z]+$");

    private WebView web;
    private SharedPreferences prefs;
    private final Object lock = new Object();
    private final Map<String, Integer> reserved = new HashMap<>();
    private volatile boolean pageReady = false;
    private volatile String pendingResult = null;   // result waiting for the page to load

    // ---------- lifecycle ----------
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("punch", MODE_PRIVATE);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Native");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.onNativeResume && window.onNativeResume()", null);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.goBack ? window.goBack() : false", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    // ---------- storage ----------
    private File root() {
        return new File(Environment.getExternalStorageDirectory(), "PunchCapture");
    }

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void askStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        web.evaluateJavascript("window.onNativeResume && window.onNativeResume()", null);
    }

    private void scan(File... files) {
        String[] paths = new String[files.length];
        for (int i = 0; i < files.length; i++) paths[i] = files[i].getAbsolutePath();
        MediaScannerConnection.scanFile(this, paths, null, null);
    }

    // ---------- config ----------
    private File configFile() { return new File(root(), "config.json"); }

    private JSONObject loadConfig() throws IOException, JSONException {
        File f = configFile();
        if (!f.exists()) {
            f.getParentFile().mkdirs();
            try (InputStream in = getAssets().open("config.json"); OutputStream out = new FileOutputStream(f)) {
                copy(in, out);
            }
            scan(f);
        }
        return new JSONObject(readText(f));
    }

    private void saveConfig(JSONObject cfg) throws IOException, JSONException {
        File f = configFile();
        File tmp = new File(f.getPath() + ".tmp");
        writeText(tmp, cfg.toString(2));
        if (!tmp.renameTo(f)) {
            writeText(f, cfg.toString(2));
            tmp.delete();
        }
        scan(f);
    }

    private JSONObject findVariant(JSONObject cfg, String vid) throws JSONException {
        JSONArray arr = cfg.getJSONArray("variants");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject v = arr.getJSONObject(i);
            if (v.getString("id").equals(vid)) return v;
        }
        throw new UserError("Variant '" + vid + "' not found.");
    }

    private JSONObject findCheckpoint(JSONObject variant, String section, String cid) throws JSONException {
        checkSection(section);
        JSONArray arr = variant.getJSONArray(section);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.getJSONObject(i);
            if (c.getString("id").equals(cid)) return c;
        }
        throw new UserError("Checkpoint '" + cid + "' not found in " + section + ".");
    }

    private static void checkSection(String section) {
        if (!"interior".equals(section) && !"exterior".equals(section))
            throw new UserError("Section must be interior or exterior.");
    }

    private static String slug(String text) {
        String s = text.trim().replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (s.isEmpty()) throw new UserError("Name must contain letters or numbers.");
        return s;
    }

    // ---------- photos ----------
    private int maxVehicleOnDisk(String vid) {
        File base = new File(root(), vid);
        int[] best = {0};
        walk(base, f -> {
            Matcher m = VEH_RE.matcher(f.getName());
            if (m.find()) best[0] = Math.max(best[0], Integer.parseInt(m.group(1)));
        });
        return best[0];
    }

    private interface FileVisitor { void visit(File f); }

    private void walk(File dir, FileVisitor v) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) walk(f, v);
            else v.visit(f);
        }
    }

    /** Checkpoint folder is named <checkpoint>_<variant>, e.g. orvm_left_Punch_Pure. */
    private File cpFolder(String vid, String section, String cid) {
        File parent = new File(root(), vid + "/" + section);
        File folder = new File(parent, cid + "_" + vid);
        File old = new File(parent, cid);                 // layout used by v1.0.1
        if (old.isDirectory()) {
            synchronized (lock) {
                if (!folder.exists()) {
                    if (old.renameTo(folder)) scanTree(folder);
                } else {
                    File[] list = old.listFiles();
                    if (list != null) for (File f : list) {
                        File to = new File(folder, f.getName());
                        if (!to.exists() && f.renameTo(to)) scan(to, f);
                    }
                    String[] left = old.list();
                    if (left != null && left.length == 0) old.delete();
                }
            }
        }
        return folder;
    }

    private void scanTree(File dir) {
        List<File> all = new ArrayList<>();
        walk(dir, all::add);
        if (!all.isEmpty()) scan(all.toArray(new File[0]));
    }

    private List<File> photosFor(String vid, String section, String cid, int vehicle) {
        File folder = cpFolder(vid, section, cid);
        List<File> out = new ArrayList<>();
        File[] list = folder.listFiles();
        if (list == null) return out;
        String tag = String.format(Locale.US, "_V%03d_", vehicle);
        for (File f : list) {
            String n = f.getName().toLowerCase(Locale.US);
            if (f.isFile() && f.getName().contains(tag) && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")))
                out.add(f);
        }
        Collections.sort(out);
        return out;
    }

    /** Small cached copy for the screen, rotated upright. The original photo is never changed. */
    private File preview(File src, int maxSide) {
        String rel = src.getAbsolutePath().substring(root().getAbsolutePath().length());
        File out = new File(getCacheDir(), "preview" + maxSide + rel + "." + src.lastModified() + ".jpg");
        if (out.exists() && out.lastModified() >= src.lastModified()) return out;
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(src.getAbsolutePath(), b);
            int side = Math.max(b.outWidth, b.outHeight), sample = 1;
            while (side / (sample * 2) >= maxSide) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(src.getAbsolutePath(), o);
            if (bmp == null) return src;
            int deg = new ExifInterface(src.getAbsolutePath()).getRotationDegrees();
            if (deg != 0) {
                Matrix m = new Matrix();
                m.postRotate(deg);
                Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (r != bmp) bmp.recycle();
                bmp = r;
            }
            out.getParentFile().mkdirs();
            try (OutputStream os = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, os);
            }
            bmp.recycle();
            return out;
        } catch (Exception e) {
            return src;
        }
    }

    private String fileUrl(File f) {
        return Uri.fromFile(f).toString();
    }

    private JSONObject photoJson(File f) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("path", f.getAbsolutePath());
        o.put("name", f.getName());
        o.put("thumb", fileUrl(preview(f, 240)));
        return o;
    }

    private void appendLog(String... cols) {
        File log = new File(root(), "capture_log.csv");
        boolean isNew = !log.exists();
        try (FileWriter w = new FileWriter(log, true)) {
            if (isNew) w.write("timestamp,variant,vehicle,section,checkpoint,file,action\n");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cols.length; i++) {
                if (i > 0) sb.append(',');
                String c = cols[i] == null ? "" : cols[i];
                if (c.contains(",") || c.contains("\"")) c = "\"" + c.replace("\"", "\"\"") + "\"";
                sb.append(c);
            }
            w.write(sb.append('\n').toString());
        } catch (IOException ignored) { }
        scan(log);
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date());
    }

    // ---------- camera ----------
    private File camTemp() {
        File dir = new File(getCacheDir(), "cam");
        dir.mkdirs();
        return new File(dir, "shot.jpg");
    }

    private void launchCamera(String vid, String section, String cid, int vehicle, String replacePath) {
        File tmp = camTemp();
        if (tmp.exists()) tmp.delete();
        String label = vid + " · " + cid + String.format(Locale.US, " · V%03d", vehicle);
        try {
            JSONObject v = findVariant(loadConfig(), vid);
            label = v.getString("name") + " · " + findCheckpoint(v, section, cid).getString("name")
                    + String.format(Locale.US, " · V%03d", vehicle)
                    + (replacePath != null && !replacePath.isEmpty() ? " · retake" : "");
            JSONObject p = new JSONObject().put("variant", vid).put("section", section)
                    .put("checkpoint", cid).put("vehicle", vehicle).put("replace", replacePath == null ? "" : replacePath);
            prefs.edit().putString("pending", p.toString()).apply();
        } catch (Exception e) {
            deliver(err("Could not open the camera: " + e.getMessage()));
            return;
        }
        Intent i = new Intent(this, CameraActivity.class);
        i.putExtra(CameraActivity.EXTRA_OUT, tmp.getAbsolutePath());
        i.putExtra(CameraActivity.EXTRA_LABEL, label);
        startActivityForResult(i, REQ_CAMERA);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAMERA) return;
        String pending = prefs.getString("pending", null);
        prefs.edit().remove("pending").apply();
        File tmp = camTemp();
        if (pending == null) return;
        final JSONObject p;
        try { p = new JSONObject(pending); } catch (JSONException e) { return; }
        if (resultCode == RESULT_FIRST_USER) {
            String msg = data == null ? null : data.getStringExtra(CameraActivity.EXTRA_ERROR);
            deliver(err(msg == null ? "The camera closed unexpectedly." : msg));
            return;
        }
        if (resultCode != RESULT_OK || !tmp.exists() || tmp.length() == 0) {
            try { p.put("cancelled", true); } catch (JSONException ignored) { }
            deliver(p.toString());
            return;
        }
        new Thread(() -> deliver(storePhoto(p.optString("variant"), p.optString("section"),
                p.optString("checkpoint"), p.optInt("vehicle"), p.optString("replace"), tmp))).start();
    }

    /** Saves the new shot. With replacePath set, the new shot takes that photo's place (same file name). */
    private String storePhoto(String vid, String section, String cid, int vehicle, String replacePath, File tmp) {
        try {
            File folder = cpFolder(vid, section, cid);
            File target = null;
            boolean replaced = false;
            synchronized (lock) {
                folder.mkdirs();
                if (replacePath != null && !replacePath.isEmpty()) {
                    File old = new File(replacePath);
                    if (old.getCanonicalFile().getParentFile().equals(folder.getCanonicalFile())) {
                        target = old;
                        replaced = true;
                    }
                }
                if (target == null) {
                    int shot = photosFor(vid, section, cid, vehicle).size() + 1;
                    while (true) {
                        target = new File(folder, String.format(Locale.US, "%s_%s_V%03d_%02d.jpg", vid, cid, vehicle, shot));
                        if (!target.exists()) break;
                        shot++;
                    }
                }
                File part = new File(folder, target.getName() + ".part");
                try (InputStream in = new FileInputStream(tmp); OutputStream out = new FileOutputStream(part)) {
                    copy(in, out);
                }
                if (target.exists() && !target.delete()) {
                    part.delete();
                    throw new UserError("Could not replace the old photo.");
                }
                if (!part.renameTo(target)) throw new IOException("could not finish writing the file");
                tmp.delete();
                Integer r = reserved.get(vid);
                reserved.put(vid, Math.max(r == null ? 0 : r, vehicle));
            }
            scan(target);
            String rel = target.getAbsolutePath().substring(root().getAbsolutePath().length() + 1);
            appendLog(now(), vid, String.format(Locale.US, "V%03d", vehicle), section, cid, rel, replaced ? "retaken" : "saved");
            JSONObject o = photoJson(target);
            o.put("ok", true).put("replaced", replaced);
            o.put("variant", vid); o.put("section", section); o.put("checkpoint", cid); o.put("vehicle", vehicle);
            o.put("folder", folder.getName());
            return o.toString();
        } catch (UserError e) {
            return err(e.getMessage());
        } catch (Exception e) {
            return err("Photo not saved: " + e.getMessage());
        }
    }

    private void deliver(String json) {
        if (!pageReady) { pendingResult = json; return; }
        runOnUiThread(() -> web.evaluateJavascript(
                "window.onPhoto && window.onPhoto(" + JSONObject.quote(json) + ")", null));
    }

    // ---------- io helpers ----------
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            copy(in, bos);
            return bos.toString("UTF-8");
        }
    }

    private static void writeText(File f, String s) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String err(String msg) {
        try { return new JSONObject().put("error", msg).toString(); }
        catch (JSONException e) { return "{\"error\":\"Unexpected error\"}"; }
    }

    private static class UserError extends RuntimeException {
        UserError(String m) { super(m); }
    }

    private interface Job { Object run() throws Exception; }

    private String safe(Job job) {
        try {
            Object r = job.run();
            return r == null ? "{}" : r.toString();
        } catch (UserError e) {
            return err(e.getMessage());
        } catch (Exception e) {
            return err(hasStorage() ? "Something went wrong: " + e.getMessage()
                    : "Storage access is off. Allow it to save photos.");
        }
    }

    private static final Pattern VAR_ID = Pattern.compile("^[A-Za-z0-9_]+$");
    private static final Pattern CP_ID = Pattern.compile("^[a-z0-9_]+$");

    private static void validateItems(JSONArray arr, String what) throws JSONException {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.getJSONObject(i);
            String id = c.optString("id"), name = c.optString("name").trim();
            if (!CP_ID.matcher(id).matches() || name.isEmpty()) throw new UserError("Bad " + what + " entry: " + name);
            if (!seen.add(id)) throw new UserError("'" + name + "' is listed twice in " + what + ".");
        }
    }

    private static void validateConfig(JSONObject cfg) throws JSONException {
        if (!cfg.has("colors")) cfg.put("colors", new JSONArray());
        validateItems(cfg.getJSONArray("colors"), "colors");
        JSONObject common = cfg.optJSONObject("common");
        if (common == null) throw new UserError("Common checkpoints are missing.");
        for (String s : SECTIONS) validateItems(common.getJSONArray(s), "common " + s);
        JSONArray vars = cfg.getJSONArray("variants");
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < vars.length(); i++) {
            JSONObject v = vars.getJSONObject(i);
            String id = v.optString("id");
            if (!VAR_ID.matcher(id).matches() || v.optString("name").trim().isEmpty()) throw new UserError("Bad variant: " + id);
            if (!ids.add(id.toLowerCase(Locale.US))) throw new UserError("Variant '" + v.optString("name") + "' already exists.");
            for (String s : SECTIONS) validateItems(v.getJSONArray(s), v.optString("name") + " " + s);
        }
    }

    // ---------- vehicle colors ----------
    private File vehiclesFile() { return new File(root(), "vehicles.json"); }

    private JSONObject loadVehicles() {
        try {
            File f = vehiclesFile();
            return f.exists() ? new JSONObject(readText(f)) : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private String vehicleColor(String vid, int vehicle) {
        JSONObject v = loadVehicles().optJSONObject(vid);
        return v == null ? "" : v.optString(String.valueOf(vehicle), "");
    }

    /** vehicles.json for the app, vehicles.csv (variant, vehicle, color) for the laptop. */
    private void saveVehicleColor(String vid, int vehicle, String color) throws IOException, JSONException {
        synchronized (lock) {
            JSONObject all = loadVehicles();
            JSONObject v = all.optJSONObject(vid);
            if (v == null) { v = new JSONObject(); all.put(vid, v); }
            if (color == null || color.isEmpty()) v.remove(String.valueOf(vehicle));
            else v.put(String.valueOf(vehicle), color);
            writeText(vehiclesFile(), all.toString(2));
            StringBuilder csv = new StringBuilder("variant,vehicle,color\n");
            List<String> vids = new ArrayList<>();
            for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) vids.add(it.next());
            Collections.sort(vids);
            for (String id : vids) {
                JSONObject m = all.getJSONObject(id);
                List<Integer> nums = new ArrayList<>();
                for (java.util.Iterator<String> it = m.keys(); it.hasNext(); ) nums.add(Integer.parseInt(it.next()));
                Collections.sort(nums);
                for (int n : nums)
                    csv.append(id).append(',').append(String.format(Locale.US, "V%03d", n)).append(',')
                       .append(m.getString(String.valueOf(n)).replace(",", " ")).append('\n');
            }
            File out = new File(root(), "vehicles.csv");
            writeText(out, csv.toString());
            scan(vehiclesFile(), out);
        }
    }

    // ---------- zip export ----------
    private File zipDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PunchCapture_Zips");
    }

    private List<File> photosBetween(long from, long to) {
        List<File> out = new ArrayList<>();
        File[] variants = root().listFiles();
        if (variants == null) return out;
        for (File v : variants) {
            if (!v.isDirectory()) continue;
            walk(v, f -> {
                String n = f.getName().toLowerCase(Locale.US);
                long t = f.lastModified();
                if ((n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) && t >= from && t <= to) out.add(f);
            });
        }
        Collections.sort(out);
        return out;
    }

    private void makeZip(long from, long to, String label) {
        try {
            List<File> photos = photosBetween(from, to);
            if (photos.isEmpty()) { zipDone(err("No photos were taken in that time range.")); return; }
            File dir = zipDir();
            dir.mkdirs();
            File zip = new File(dir, "PunchCapture_" + label + ".zip");
            int k = 2;
            while (zip.exists()) zip = new File(dir, "PunchCapture_" + label + "_" + (k++) + ".zip");
            File part = new File(zip.getPath() + ".part");
            String base = root().getAbsolutePath();
            long bytes = 0;
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(part))) {
                zos.setLevel(Deflater.NO_COMPRESSION);   // photos are already compressed; this keeps it fast
                int i = 0;
                for (File f : photos) {
                    addToZip(zos, f, "PunchCapture/" + f.getAbsolutePath().substring(base.length() + 1));
                    bytes += f.length();
                    if (++i % 10 == 0) zipProgress(i, photos.size());
                }
                for (String extra : new String[]{"capture_log.csv", "vehicles.csv", "config.json"}) {
                    File f = new File(root(), extra);
                    if (f.isFile()) addToZip(zos, f, "PunchCapture/" + extra);
                }
            }
            if (!part.renameTo(zip)) throw new IOException("could not finish the zip file");
            scan(zip);
            zipDone(new JSONObject().put("ok", true).put("path", zip.getAbsolutePath()).put("name", zip.getName())
                    .put("count", photos.size()).put("bytes", zip.length()).toString());
        } catch (Exception e) {
            zipDone(err("Zip not made: " + e.getMessage()));
        }
    }

    private static void addToZip(ZipOutputStream zos, File f, String name) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(f.lastModified());
        zos.putNextEntry(e);
        try (InputStream in = new FileInputStream(f)) { copy(in, zos); }
        zos.closeEntry();
    }

    private void zipProgress(int done, int total) {
        runOnUiThread(() -> web.evaluateJavascript("window.onZipProgress && window.onZipProgress(" + done + "," + total + ")", null));
    }

    private void zipDone(String json) {
        runOnUiThread(() -> web.evaluateJavascript("window.onZip && window.onZip(" + JSONObject.quote(json) + ")", null));
    }

    private void shareFile(File f) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Send zip"));
    }

    // ---------- bridge for the page ----------
    private class Bridge {

        @JavascriptInterface
        public String ready() {
            pageReady = true;
            String r = pendingResult;
            pendingResult = null;
            return r == null ? "null" : r;
        }

        @JavascriptInterface
        public String storage() {
            return safe(() -> new JSONObject().put("granted", hasStorage())
                    .put("root", root().getAbsolutePath())
                    .put("version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName));
        }

        @JavascriptInterface
        public void requestStorage() { runOnUiThread(MainActivity.this::askStorage); }

        @JavascriptInterface
        public String getConfig() { return safe(MainActivity.this::loadConfig); }

        /** The page edits the whole config (variants, common checkpoints, colors) and saves it here. */
        @JavascriptInterface
        public String putConfig(String json) {
            return safe(() -> {
                synchronized (lock) {
                    JSONObject cfg = new JSONObject(json);
                    validateConfig(cfg);
                    saveConfig(cfg);
                    return cfg;
                }
            });
        }

        @JavascriptInterface
        public String setVehicleColor(String vid, int vehicle, String color) {
            return safe(() -> {
                findVariant(loadConfig(), vid);
                saveVehicleColor(vid, vehicle, color);
                return new JSONObject().put("color", color);
            });
        }

        @JavascriptInterface
        public String zipCount(double from, double to) {
            return safe(() -> {
                List<File> photos = photosBetween((long) from, (long) to);
                long bytes = 0;
                for (File f : photos) bytes += f.length();
                return new JSONObject().put("count", photos.size()).put("bytes", bytes);
            });
        }

        @JavascriptInterface
        public void makeZip(double from, double to, String label) {
            String l = label.replaceAll("[^A-Za-z0-9_-]", "");
            new Thread(() -> MainActivity.this.makeZip((long) from, (long) to, l.isEmpty() ? "export" : l)).start();
        }

        @JavascriptInterface
        public String shareZip(String path) {
            return safe(() -> {
                File f = new File(path);
                if (!f.getCanonicalPath().startsWith(zipDir().getCanonicalPath() + File.separator) || !f.isFile())
                    throw new UserError("Zip not found.");
                runOnUiThread(() -> {
                    try { shareFile(f); } catch (Exception e) { zipDone(err("Could not open sharing: " + e.getMessage())); }
                });
                return new JSONObject().put("ok", true);
            });
        }

        @JavascriptInterface
        public String lastVehicles() {
            return safe(() -> {
                JSONObject cfg = loadConfig(), out = new JSONObject();
                JSONArray arr = cfg.getJSONArray("variants");
                for (int i = 0; i < arr.length(); i++) {
                    String id = arr.getJSONObject(i).getString("id");
                    out.put(id, maxVehicleOnDisk(id));
                }
                return out;
            });
        }

        @JavascriptInterface
        public String newVehicle(String vid) {
            return safe(() -> {
                findVariant(loadConfig(), vid);
                synchronized (lock) {
                    Integer r = reserved.get(vid);
                    int n = Math.max(maxVehicleOnDisk(vid), r == null ? 0 : r) + 1;
                    reserved.put(vid, n);
                    return new JSONObject().put("vehicle", n);
                }
            });
        }

        @JavascriptInterface
        public String progress(String vid, int vehicle) {
            return safe(() -> {
                JSONObject v = findVariant(loadConfig(), vid), out = new JSONObject();
                for (String s : SECTIONS) {
                    JSONObject sec = new JSONObject();
                    JSONArray cps = v.getJSONArray(s);
                    for (int i = 0; i < cps.length(); i++) {
                        String cid = cps.getJSONObject(i).getString("id");
                        JSONArray shots = new JSONArray();
                        for (File f : photosFor(vid, s, cid, vehicle)) shots.put(photoJson(f));
                        sec.put(cid, shots);
                    }
                    out.put(s, sec);
                }
                out.put("color", vehicleColor(vid, vehicle));
                return out;
            });
        }

        @JavascriptInterface
        public String bigPreview(String path) {
            return safe(() -> {
                File f = new File(path);
                if (!f.getCanonicalPath().startsWith(root().getCanonicalPath() + File.separator) || !f.isFile())
                    throw new UserError("Photo not found.");
                return new JSONObject().put("url", fileUrl(preview(f, 1600)));
            });
        }

        @JavascriptInterface
        public String deletePhoto(String path) {
            return safe(() -> {
                File f = new File(path);
                if (!f.getCanonicalPath().startsWith(root().getCanonicalPath() + File.separator) || !f.isFile())
                    throw new UserError("Photo not found.");
                String rel = f.getAbsolutePath().substring(root().getAbsolutePath().length() + 1);
                synchronized (lock) {
                    if (!f.delete()) throw new UserError("Could not delete the photo.");
                }
                scan(f);
                appendLog(now(), "", "", "", "", rel, "deleted");
                return new JSONObject().put("ok", true);
            });
        }

        /** replacePath empty = new shot; otherwise retake that photo. */
        @JavascriptInterface
        public void takePhoto(String vid, String section, String cid, int vehicle, String replacePath) {
            String check = safe(() -> {
                if (!hasStorage()) throw new UserError("Storage access is off. Allow it to save photos.");
                findCheckpoint(findVariant(loadConfig(), vid), section, cid);
                return null;
            });
            if (check.contains("\"error\"")) { deliver(check); return; }
            runOnUiThread(() -> launchCamera(vid, section, cid, vehicle, replacePath));
        }
    }
}
