package com.sergkit.co2meter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static android.content.Context.MODE_PRIVATE;

public class FirebaseReadWorker extends Worker {

    static final String TAG = "CO2_WORKMNG";
    private static final String DEVICEID = "deviceId";
    private static final String PACKAGEID= "com.sergkit.co2meter";
    // Идентификатор уведомления

    private static final int NOTIFY_CO2_ID = 101;
    private static final int NOTIFY_T_ID = 102;
    private static final int NOTIFY_H_ID = 103;
    // Идентификатор канала
    private static String CHANNEL_ID = "co2_channel";

    private float maxCO2;
    private float minH;
    private float minT;
    private float fCO2;
    private float fT;
    private float fH;

    private CountDownLatch latch;

    DataTransfer dataTransfer;
    NotificationManagerCompat notificationManager;
    private NotificationManager notifManager;

    public FirebaseReadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        dataTransfer= new DataTransfer();
        notificationManager = NotificationManagerCompat.from(getApplicationContext());
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            if (hasConnection(getApplicationContext()) ){

                Log.d(TAG, "doWork: start");
                SharedPreferences sPref = getApplicationContext().getSharedPreferences(PACKAGEID + "_preferences", MODE_PRIVATE);
                String devId = sPref.getString(DEVICEID, "");
                String s;
                s = sPref.getString("CO2", "0.0");
                maxCO2 = Float.valueOf(s);
                s = sPref.getString("H", "0.0");
                minH = Float.valueOf(s);
                s = sPref.getString("T", "0.0");
                minT = Float.valueOf(s);

                Log.d(TAG, "doWork: dev: " + devId + " " + s);
                if (devId != "") {
                    latch = new CountDownLatch(1);
                    FirebaseDatabase database = FirebaseDatabase.getInstance();
                    database.getReference("devices-telemetry/" + devId + "/last")
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override
                                public void onDataChange(DataSnapshot dataSnapshot) {
                                    Float s;
                                    fCO2 = dataSnapshot.child("co2").getValue(Float.class);
                                    Log.w(TAG, "worker read value." + String.valueOf(fCO2));
                                    //fCO2 = Float.valueOf(s);
                                    fT = dataSnapshot.child("t").getValue(Float.class);
                                    Log.w(TAG, "worker read value." +  String.valueOf(fT));
                                    //fT = Float.valueOf(s);
                                    fH =  dataSnapshot.child("h").getValue(Float.class);
                                    Log.w(TAG, "worker read value." +  String.valueOf(fH));
                                    //fH = Float.valueOf(s);
                                    latch.countDown();
                                }

                                @Override
                                public void onCancelled(DatabaseError error) {
                                    Log.w(TAG, "Failed to read value.", error.toException());
                                    latch.countDown();
                                }
                            });
                    latch.await(3, TimeUnit.SECONDS);
                    if (fCO2 > maxCO2) {
                        showNotification(NOTIFY_CO2_ID, dataTransfer.setCo2(fCO2));
                    } else {
                        notificationManager.cancel(NOTIFY_CO2_ID);
                    }
                    if (fT < minT) {
                        showNotification(NOTIFY_T_ID, dataTransfer.setT(fT));
                    } else {
                        notificationManager.cancel(NOTIFY_T_ID);
                    }
                    if (fH < minH) {
                        showNotification(NOTIFY_H_ID, dataTransfer.setT(fH));
                    } else {
                        notificationManager.cancel(NOTIFY_H_ID);
                    }

                } else {
                    Log.w(TAG, "doWork: no devId ");
                }
            }else{
                Log.w(TAG, "doWork: no wifi ");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        Log.d(TAG, "doWork: end");

        return Result.success();
    }

    public void showNotification(int messageId, String val) {
        final int NOTIFY_ID = 0; // ID of notification
        Context context=getApplicationContext();
        String id = CHANNEL_ID; // default_channel_id
        String title = "Состояние воздуха"; // Default Channel
        Intent intent;
        PendingIntent pendingIntent;
        NotificationCompat.Builder builder;
        if (notifManager == null) {
            notifManager = (NotificationManager)context
                    .getSystemService(Context.NOTIFICATION_SERVICE);
        }
        String s="";
        switch (messageId){
            case NOTIFY_CO2_ID: s="Уровень CO2 выше нормы: ";
                break;
            case NOTIFY_T_ID: s="Температура ниже нормы: ";
                break;
            case NOTIFY_H_ID: s="Влажность ниже нормы: ";
                break;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int importance = NotificationManager.IMPORTANCE_HIGH;
            assert notifManager != null;
            NotificationChannel mChannel = notifManager.getNotificationChannel(id);
            if (mChannel == null) {
                mChannel = new NotificationChannel(id, title, importance);
                mChannel.enableVibration(true);
                mChannel.setVibrationPattern(new long[]{100, 200, 300, 400, 500, 400, 300, 200, 400});
                notifManager.createNotificationChannel(mChannel);
            }
            builder = new NotificationCompat.Builder(context, id);
            intent = new Intent(context, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            pendingIntent = PendingIntent.getActivity(context, 0, intent, 0);
            builder.setContentTitle(s + val)                            // required
                    .setSmallIcon(R.drawable.ic_stat_co2)   // required
                    .setContentText("Нажмите для открытия приложения") // required
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setTicker(s + val)
                    .setVibrate(new long[]{100, 200, 300, 400, 500, 400, 300, 200, 400});
        }
        else {
            builder = new NotificationCompat.Builder(context, id);
            intent = new Intent(context, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            pendingIntent = PendingIntent.getActivity(context, 0, intent, 0);
            builder.setContentTitle(s + val)                            // required
                    .setSmallIcon(R.drawable.ic_stat_co2)   // required
                    .setContentText("Нажмите для открытия приложения") // required
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setTicker(s + val)
                    .setVibrate(new long[]{100, 200, 300, 400, 500, 400, 300, 200, 400})
                    .setPriority(Notification.PRIORITY_HIGH);
        }
        Notification notification = builder.build();
        notifManager.notify(NOTIFY_ID, notification);
        Log.d(TAG, "Message "  + s + val );
    }

    public static boolean hasConnection(final Context context)
    {
        ConnectivityManager cm = (ConnectivityManager)context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        assert cm != null;
        final Network network = cm.getActiveNetwork();
        final NetworkCapabilities capabilities = cm
                .getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);

    }
}
/*
No Channel found for pkg=com.sergkit.co2meter, channelId=co2_channel, id=101, tag=null, opPkg=com.sergkit.co2meter, callingUid=10133, userId=0, incomingUserId=0, notificationUid=10133, notification=Notification(channel=co2_channel pri=0 contentView=null vibrate=null sound=null defaults=0x0 flags=0x1 color=0x00000000 vis=PRIVATE)
 */
