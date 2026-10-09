package com.rishiraj.mobilevision.hub;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/** Serves the capture web app to the Zebra, the API it uses, and the sync API for the laptop. */
public class HubServer extends NanoHTTPD {
    public static final int PORT = 8000;        // http: hub status page; forwards browsers to https
    public static final int HTTPS_PORT = 8443;  // https: needed so the browser may use the camera
    final Context ctx;
    final Store store;
    final boolean secure;

    public HubServer(Context c, Store s, int port, boolean secure) {
        super(port);
        ctx = c.getApplicationContext();
        store = s;
        this.secure = secure;
    }

    static boolean isLocal(IHTTPSession s) {
        String ip = s.getRemoteIpAddress();
        return ip == null || ip.startsWith("127.") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1");
    }

    static Response json(Object o) {
        Response r = newFixedLengthResponse(Response.Status.OK, "application/json", o.toString());
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    static String err(String m) {
        try { return new JSONObject().put("error", m).toString(); } catch (Exception e) { return "{\"error\":\"error\"}"; }
    }

    static String body(IHTTPSession s) throws Exception {
        Map<String, String> files = new HashMap<>();
        s.parseBody(files);
        String b = files.get("postData");
        return b == null ? "" : b;
    }

    static String param(IHTTPSession s, String k, String def) {
        List<String> v = s.getParameters().get(k);
        return v == null || v.isEmpty() ? def : v.get(0);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method m = session.getMethod();
        // Other devices opening http://<phone>:8000 are sent to the secure address (camera needs https).
        if (!secure && !isLocal(session) && (uri.equals("/") || uri.equals("/index.html"))) {
            String host = session.getHeaders().get("host");
            if (host == null) host = "";
            host = host.replaceAll(":\\d+$", "");
            Response r = newFixedLengthResponse(Response.Status.REDIRECT, "text/html",
                    "<a href=\"https://" + host + ":" + HTTPS_PORT + "/\">Continue</a>");
            r.addHeader("Location", "https://" + host + ":" + HTTPS_PORT + "/");
            return r;
        }
        try {
            if (uri.startsWith("/api/rpc/") && m == Method.POST) return rpc(uri.substring(9), body(session));
            if (uri.equals("/api/upload") && m == Method.POST) return upload(session);
            if (uri.startsWith("/thumb/")) return image(uri.substring(7), 240);
            if (uri.startsWith("/preview/")) return image(uri.substring(9), 1280);
            if (uri.startsWith("/sync/")) return sync(uri.substring(6), session);
            if (uri.startsWith("/download/") && uri.endsWith(".zip")) return download(uri.substring(10, uri.length() - 4));
            if (uri.equals("/hub") || uri.equals("/hub/")) return html(hubPage());
            if (uri.equals("/hub/stop")) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> ctx.stopService(new Intent(ctx, HubService.class)), 300);
                return json(new JSONObject().put("ok", true));
            }
            return asset(uri);
        } catch (Store.UserError e) {
            return json(err(e.getMessage()));
        } catch (Exception e) {
            return json(err("Something went wrong on the phone hub: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------ capture screens
    Response rpc(String name, String body) throws Exception {
        JSONArray a = body.isEmpty() ? new JSONArray() : new JSONObject(body).optJSONArray("args");
        if (a == null) a = new JSONArray();
        switch (name) {
            case "storage": return json(store.storage());
            case "getConfig": return json(store.loadConfig());
            case "putConfig": return json(store.putConfig(a.getString(0)));
            case "lastVehicles": return json(store.lastVehicles());
            case "newVehicle": return json(store.newVehicle(a.getString(0)));
            case "progress": return json(store.progress(a.getString(0), a.getInt(1)));
            case "setVehicleColor": return json(store.setVehicleColor(a.getString(0), a.getInt(1), a.optString(2, "")));
            case "bigPreview": return json(store.bigPreview(a.getString(0)));
            case "deletePhoto": return json(store.deletePhoto(a.getString(0)));
            case "browse": return json(store.browse(a.optString(0, "")));
            case "stats": return json(store.stats());
            case "syncStatus": return json(store.syncStatus());
            case "variants": return json(store.variants());
            case "getSim": return json(store.sim());
            case "putSim": return json(store.putSim(a.getString(0)));
            case "procStats": return json(store.procStats());
            case "variantPhotos": return json(store.variantPhotos(a.getString(0), a.optInt(1, 0), a.optInt(2, 48)));
            case "listZips": return json(new JSONObject().put("zips", new JSONArray()));
            default: return json(err("Zips and folders are on the laptop when using the phone hub."));
        }
    }

    Response upload(IHTTPSession s) throws Exception {
        Map<String, String> files = new HashMap<>();
        s.parseBody(files);
        String tmp = files.get("file");
        JSONObject r = store.upload(param(s, "variant", ""), param(s, "section", ""), param(s, "checkpoint", ""),
                Integer.parseInt(param(s, "vehicle", "0")), param(s, "replace", ""), param(s, "device", ""),
                tmp == null ? null : new File(tmp), param(s, "file", "photo.jpg"));
        return json(r);
    }

    Response image(String rel, int size) throws IOException {
        File p = store.syncFile(rel);
        if (p == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found");
        File t = store.thumbFile(p, size);
        Response r = newFixedLengthResponse(Response.Status.OK, "image/jpeg", new FileInputStream(t), t.length());
        r.addHeader("Cache-Control", "max-age=86400");
        return r;
    }

    // ------------------------------------------------------------ laptop downloads (browser)
    Response download(String name) throws Exception {
        final String vid = "all".equals(name) ? null : name;
        if (vid != null) {
            File d = store.underRoot(vid);
            if (!d.isDirectory() || vid.contains("/")) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found");
        }
        final java.io.PipedInputStream in = new java.io.PipedInputStream(256 * 1024);
        final java.io.PipedOutputStream out = new java.io.PipedOutputStream(in);
        new Thread(() -> {
            try { store.writeZip(vid, out); } catch (Exception ignored) { }
            finally { try { out.close(); } catch (IOException ignored) { } }
        }, "zip").start();
        String file = (vid == null ? "PunchCapture_all" : vid) + "_" +
                new java.text.SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new java.util.Date()) + ".zip";
        Response r = newChunkedResponse(Response.Status.OK, "application/zip", in);
        r.addHeader("Content-Disposition", "attachment; filename=\"" + file + "\"");
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    // ------------------------------------------------------------ laptop sync
    Response sync(String what, IHTTPSession s) throws Exception {
        store.touchLaptop(s.getRemoteIpAddress());
        switch (what) {
            case "hello": return json(store.hello());
            case "events":
                return json(store.events(Long.parseLong(param(s, "after", "0")), Integer.parseInt(param(s, "limit", "200"))));
            case "file": {
                String rel = param(s, "rel", "");
                File p = store.syncFile(rel);
                if (p == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "gone");
                Response r = newFixedLengthResponse(Response.Status.OK, "image/jpeg", new FileInputStream(p), p.length());
                r.addHeader("X-Mtime", String.valueOf(p.lastModified()));
                r.addHeader("X-Slim", store.isSynced(rel) ? "1" : "0");
                return r;
            }
            case "results": return json(store.putResults(body(s)));
            case "ack": return json(store.ack(new JSONObject(body(s)).optLong("seq")));
            case "meta": return json(store.meta());
            default: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found");
        }
    }

    // ------------------------------------------------------------ static files
    Response asset(String uri) {
        String path = uri.equals("/") || uri.isEmpty() ? "/index.html" : uri;
        if (path.contains("..")) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "no");
        try {
            InputStream in = ctx.getAssets().open("web" + path);
            byte[] data = Store.readAll(in);
            in.close();
            Response r = newFixedLengthResponse(Response.Status.OK, mime(path), new java.io.ByteArrayInputStream(data), data.length);
            r.addHeader("Cache-Control", path.endsWith(".html") || path.endsWith(".js") ? "no-cache" : "max-age=86400");
            return r;
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found");
        }
    }

    static String mime(String p) {
        String n = p.toLowerCase(Locale.US);
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    static Response html(String s) {
        Response r = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", s);
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    // ------------------------------------------------------------ hub status page (shown in the hub app)
    /** IPv4 addresses of this phone, hotspot first. */
    static List<String[]> addresses() {
        List<String[]> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress())
                        out.add(new String[]{ni.getName(), a.getHostAddress()});
                }
            }
        } catch (Exception ignored) { }
        Collections.sort(out, (x, y) -> Integer.compare(rank(x[0]), rank(y[0])));
        return out;
    }

    static int rank(String n) {
        n = n.toLowerCase(Locale.US);
        if (n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") || n.contains("wlan1")) return 0;
        if (n.startsWith("wlan")) return 1;
        if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("dummy")) return 3;
        return 2;
    }

    static String label(String n) {
        int r = rank(n);
        return r == 0 ? "Hotspot" : r == 1 ? "Wi-Fi" : r == 3 ? "Mobile data" : n;
    }

    static String qrSvg(String text) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
            int w = m.getWidth(), h = m.getHeight();
            StringBuilder p = new StringBuilder();
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    if (m.get(x, y)) p.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
            return "<svg viewBox=\"0 0 " + w + " " + h + "\" shape-rendering=\"crispEdges\"><rect width=\"" + w + "\" height=\"" + h
                    + "\" fill=\"#fff\"/><path d=\"" + p + "\" fill=\"#0f1b2d\"/></svg>";
        } catch (Exception e) {
            return "";
        }
    }

    String hubPage() {
        StringBuilder addr = new StringBuilder();
        List<String[]> list = addresses();
        boolean first = true;
        for (String[] a : list) {
            if (rank(a[0]) == 3) continue;
            String url = "http://" + a[1] + ":" + PORT;
            addr.append("<div class=\"addr").append(first ? " main" : "").append("\">")
                .append(first ? qrSvg(url) : "")
                .append("<b>").append(url).append("</b><span>").append(label(a[0]))
                .append(first ? " · opens https://" + a[1] + ":" + HTTPS_PORT : "").append("</span></div>");
            first = false;
        }
        if (addr.length() == 0)
            addr.append("<div class=\"warn\">No network yet. Turn on this phone's <b>Hotspot</b>, then tap Refresh.</div>");
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>MobileVision Hub</title><style>"
            + ":root{--bg:#f2f5fa;--card:#fff;--fg:#0f1b2d;--muted:#667385;--line:#e2e8f0;--p:#1769e8;--ok:#1f9d55;--warn:#f29a0e}"
            + "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.45 system-ui,sans-serif}"
            + "main{max-width:520px;margin:0 auto;padding:22px 16px 40px}h1{margin:0;font-size:1.6rem;font-weight:800}h1 em{font-style:normal;color:var(--p)}"
            + ".sub{color:var(--muted);margin:2px 0 16px}.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:14px;margin-bottom:12px}"
            + ".addr{display:flex;flex-direction:column;align-items:center;gap:4px;padding:6px 0}.addr svg{width:210px;height:210px}"
            + ".addr b{font:700 1.1rem ui-monospace,monospace}.addr span{color:var(--muted);font-size:.85rem}.addr:not(.main){flex-direction:row;justify-content:space-between}"
            + ".addr:not(.main) b{font-size:.95rem}.row{display:flex;justify-content:space-between;padding:7px 0;border-top:1px solid var(--line)}"
            + ".row:first-child{border-top:0}.dot{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:6px}"
            + ".btns{display:flex;gap:8px;flex-wrap:wrap}.btn{flex:1;min-width:140px;border:0;border-radius:12px;padding:13px;font-weight:650;font-size:1rem;text-align:center;text-decoration:none}"
            + ".pri{background:var(--p);color:#fff}.soft{background:var(--card);color:var(--fg);border:1px solid var(--line)}.warn{color:#9a5b00}"
            + "ol{margin:6px 0 0;padding-left:20px}li{margin:4px 0}.m{color:var(--muted);font-size:.88rem}</style></head><body><main>"
            + "<h1>MobileVision<em>.Ai</em> Hub</h1><p class=\"sub\">This phone connects the Zebra and the laptop. Keep it on and charging.</p>"
            + "<div class=\"card\"><b>Open on the Zebra and the laptop</b><p class=\"m\" style=\"margin:2px 0 6px\">Connect them to this phone's hotspot, then scan or type in the browser:</p>" + addr + "</div>"
            + "<div class=\"card\" id=\"st\"><div class=\"row\" hidden><span>Laptop</span><b id=\"lap\">…</b></div>"
            + "<div class=\"row\" hidden><span>Waiting to go to the laptop</span><b id=\"wait\">…</b></div>"
            + "<div class=\"row\"><span>Photos on this phone</span><b id=\"ph\">…</b></div>"
            + "<div class=\"row\"><span>Free space</span><b id=\"free\">…</b></div></div>"
            + "<div class=\"btns\"><a class=\"btn pri\" href=\"/\">Open capture screen here</a><a class=\"btn soft\" href=\"/hub\">Refresh</a></div>"
            + "<div class=\"card\" style=\"margin-top:12px\"><b>Setup</b><ol class=\"m\"><li>Turn on this phone's <b>Hotspot</b> (mobile data can stay off).</li>"
            + "<li>Connect the <b>Zebra</b> and the <b>laptop</b> to the hotspot.</li><li>On the Zebra and the laptop, open the address above in the browser. The first time, the browser warns the connection is not private: tap <b>Advanced → Proceed</b>. On the Zebra, allow the <b>camera</b>.</li><li>Choose <b>Zebra</b> to capture, <b>Laptop</b> to download photos.</li>"
            + "<li>Allow this app to run in the background: Settings → Apps → MobileVision Hub → Battery → Unrestricted.</li></ol></div>"
            + "<div class=\"btns\"><button class=\"btn soft\" onclick=\"fetch('/hub/stop').then(()=>document.body.innerHTML='<main><h1>Hub stopped</h1><p>Open the app again to restart.</p></main>')\">Stop hub</button></div>"
            + "</main><script>"
            + "async function tick(){try{const s=await (await fetch('/api/rpc/syncStatus',{method:'POST',body:'{\"args\":[]}'})).json();"
            + "const on=s.laptopSeenMs>=0&&s.laptopSeenMs<20000;"
            + "document.getElementById('lap').innerHTML=`<span class=\"dot\" style=\"background:${on?'var(--ok)':'var(--warn)'}\"></span>`+(on?'Connected':(s.laptopSeenMs<0?'Not seen yet':'Last seen '+Math.round(s.laptopSeenMs/60000)+' min ago'));"
            + "document.getElementById('wait').textContent=s.waiting;document.getElementById('ph').textContent=s.photos;"
            + "document.getElementById('free').textContent=(s.freeMB/1024).toFixed(1)+' GB';}catch(e){}}tick();setInterval(tick,3000);"
            + "</script></body></html>";
    }
}
