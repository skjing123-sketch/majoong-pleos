package ai.nearby.reunion.nmeaprobe;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/** Independent observer: no Pleos SDK, network, route, vehicle, or arrival APIs. */
public final class NmeaProbeService extends Service {
    public static final String ACTION_START = "ai.nearby.reunion.nmeaprobe.START";
    private static final String ACTION_STOP = "ai.nearby.reunion.nmeaprobe.STOP";
    private static final String TAG = "MajoongNmeaProbe";
    private static final String CHANNEL = "bounded_nmea_diagnostic";
    private static final int NOTIFICATION = 4201;
    private static final long MAX_MS = 5 * 60 * 1000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationManager manager;
    private boolean active;

    private final OnNmeaMessageListener nmeaListener = (message, timestamp) -> {
        // Timestamp and body are deliberately not logged or retained.
        if (active) Log.i(TAG, NmeaSummary.of(message).toLogMessage());
    };
    private final GnssStatus.Callback statusCallback = new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus status) {
            if (!active) return;
            int used = 0;
            for (int i = 0; i < status.getSatelliteCount(); i++) if (status.usedInFix(i)) used++;
            Log.i(TAG, "usedInFix=" + used);
        }
    };
    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location location) {
            // Keep GPS observation active; never retain or log coordinates.
        }
        @Override public void onProviderDisabled(String provider) { if (LocationManager.GPS_PROVIDER.equals(provider)) stopProbe(); }
        @Override public void onProviderEnabled(String provider) { }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "lifecycle=startRequested");
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            stopProbe();
            return START_NOT_STICKY;
        }
        if (active) return START_NOT_STICKY;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            Log.w(TAG, "lifecycle=permissionMissing");
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            NotificationManager notifications = getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "NMEA 진단", NotificationManager.IMPORTANCE_LOW));
            Notification notification = notification();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(NOTIFICATION, notification);
            Log.i(TAG, "lifecycle=foregroundStarted");
            manager = getSystemService(LocationManager.class);
            boolean providerEnabled = manager != null && manager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            Log.i(TAG, "providerEnabled=" + providerEnabled);
            if (!providerEnabled) {
                stopProbe();
                return START_NOT_STICKY;
            }
            active = true;
            boolean nmeaRegistered = manager.addNmeaListener(nmeaListener, handler);
            boolean statusRegistered = manager.registerGnssStatusCallback(statusCallback, handler);
            Log.i(TAG, "nmeaRegistered=" + nmeaRegistered + " statusRegistered=" + statusRegistered);
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0.0f, locationListener, Looper.getMainLooper());
            if (!nmeaRegistered || !statusRegistered) {
                stopProbe();
                return START_NOT_STICKY;
            }
            Log.i(TAG, "lifecycle=active maxMs=" + MAX_MS);
            handler.postDelayed(this::stopProbe, MAX_MS);
        } catch (RuntimeException ignored) {
            // Never log exception messages: vendor code might include coordinates.
            Log.w(TAG, "lifecycle=error errorClass=" + ignored.getClass().getSimpleName());
            stopProbe();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
            new Intent(this, NmeaProbeService.class).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("마중 NMEA 진단 중")
            .setContentText("좌표를 저장하지 않습니다 · 최대 5분 후 중지")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "진단 중지", stop).build())
            .build();
    }

    private void closeSubscriptions() {
        active = false;
        handler.removeCallbacksAndMessages(null);
        if (manager != null) {
            try { manager.removeNmeaListener(nmeaListener); } catch (RuntimeException ignored) { }
            try { manager.unregisterGnssStatusCallback(statusCallback); } catch (RuntimeException ignored) { }
            try { manager.removeUpdates(locationListener); } catch (RuntimeException ignored) { }
        }
        manager = null;
    }

    private void stopProbe() {
        Log.i(TAG, "lifecycle=stopRequested active=" + active);
        closeSubscriptions();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onTaskRemoved(Intent rootIntent) { Log.i(TAG, "lifecycle=taskRemoved"); stopProbe(); }
    @Override public void onDestroy() { closeSubscriptions(); Log.i(TAG, "lifecycle=destroyed"); super.onDestroy(); }
}
