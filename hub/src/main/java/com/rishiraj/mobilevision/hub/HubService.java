package com.rishiraj.mobilevision.hub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import fi.iki.elonen.NanoHTTPD;

/** Keeps the hub server running in the background, with the screen off. */
public class HubService extends Service {
    static final String CHANNEL = "hub";
    static volatile boolean running = false;
    HubServer server, secureServer;
    PowerManager.WakeLock wake;
    WifiManager.WifiLock wifi;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Hub running", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, HubActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("MobileVision Hub is running")
                .setContentText("Zebra and laptop connect through this phone's hotspot")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mobilevision:hub");
        wake.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mobilevision:hub");
            wifi.acquire();
        }
        try {
            server = new HubServer(this, Store.get(this), HubServer.PORT, false);
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            running = true;
        } catch (Exception e) {
            running = false;
        }
        try {
            char[] pass = "mobilevision".toCharArray();
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            try (java.io.InputStream in = getAssets().open("hub.p12")) { ks.load(in, pass); }
            javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pass);
            secureServer = new HubServer(this, Store.get(this), HubServer.HTTPS_PORT, true);
            secureServer.makeSecure(NanoHTTPD.makeSSLSocketFactory(ks, kmf), null);
            secureServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        } catch (Exception e) {
            secureServer = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public void onDestroy() {
        running = false;
        if (server != null) server.stop();
        if (secureServer != null) secureServer.stop();
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
