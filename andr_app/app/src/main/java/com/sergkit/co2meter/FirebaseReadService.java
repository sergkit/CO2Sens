package com.sergkit.co2meter;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Messenger;

import android.util.Log;


import androidx.annotation.RequiresApi;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.Objects;


public class FirebaseReadService extends Service {
    private static final String LOG_TAG = "CO2_FB_Serv";
    public static final int NEW_DATA = 1;
    ValueEventListener valueEventListener;
    private String devId;

    public FirebaseReadService() {

    }


    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(LOG_TAG, "Service Started");

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(LOG_TAG, "Service Stopped");
        if(valueEventListener!=null){
            FirebaseDatabase.getInstance().getReference("devices-telemetry/"+devId)
                    .removeEventListener(valueEventListener);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        devId=intent.getStringExtra("devId");
        valueEventListener=new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                DataSnapshot dsRes;
                dsRes = dataSnapshot.child("last");
                Log.w(LOG_TAG, "Service read value.");
                Intent intent = new Intent("com.sergkit.co2meter.co2_data");
                intent.putExtra("co2", dsRes.child("co2").getValue(Float.class));
                intent.putExtra("t", dsRes.child("t").getValue(Float.class));
                intent.putExtra("h", dsRes.child("h").getValue(Float.class));
                intent.putExtra("dt", dsRes.child("tm").getValue(String.class));
                LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.w(LOG_TAG, "Failed to read value.", error.toException());
            }
        };
        try {
            FirebaseDatabase database = FirebaseDatabase.getInstance();
            database.getReference("devices-telemetry/"+devId)
                .addValueEventListener(valueEventListener);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

}
