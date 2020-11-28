package com.sergkit.co2meter;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.ViewTreeObserver;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;
import com.google.firebase.functions.HttpsCallableResult;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings;
import com.google.gson.Gson;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.LegendRenderer;
import com.jjoe64.graphview.helper.DateAsXAxisLabelFormatter;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static androidx.work.NetworkType.NOT_ROAMING;

public class MainActivity extends AppCompatActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener{
    private static final String LOG_TAG = "CO2Logs";
    // Remote Config keys
    private static final String DEVICEID = "deviceId";
    private static final String PACKAGEID= "com.sergkit.co2meter";


    private DatabaseReference myD;
    private FirebaseAuth mAuth;
    private FirebaseRemoteConfig mFirebaseRemoteConfig;
    private SharedPreferences sPref;

    private TextView t_textView;
    private TextView h_textView;
    private TextView co2_textView;
    private TextView uidView;
    private TextView dtView;
    private RelativeLayout graphLayout;
    private ProgressBar progressBar;

    private String devId;
    private String uid;

    private TaskCompletionSource<String> remConfig = new TaskCompletionSource<>();
    private Task configTask = remConfig.getTask();

    private TaskCompletionSource<String> AuthProc = new TaskCompletionSource<>();
    private Task AuthTask = AuthProc.getTask();

    private TaskCompletionSource<Boolean> getGraph = new TaskCompletionSource<>();
    private Task GraphTask = getGraph.getTask();

    private Task<Void> allTask;

    private float maxCO2=750.0f;
    private float minH=30.0f;
    private float minT=20.0f;
    private int workerInterval=15;
    private  boolean workerOn=false;

    private boolean serviceOn=false;

    DataTransfer dataTransfer = new DataTransfer();
    private LineGraphSeries<DataPoint> mSeriesT, mSeriesH,mSeriesCo2;
    private FirebaseFunctions mFunctions;

    private int graphWidth;

    private  Chip chipY, chipM, chipW, chipD;
    private ChipGroup chipGroup;
    private int mode=3;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        checkPower();

        t_textView = (TextView) findViewById(R.id.tw_t);
        h_textView = (TextView) findViewById(R.id.tw_h);
        co2_textView = (TextView) findViewById(R.id.tw_co2);
        uidView = (TextView) findViewById(R.id.uidView);
        dtView = (TextView) findViewById(R.id.dt);
        graphLayout=(RelativeLayout) findViewById(R.id.graphLayout);
        chipY=(Chip) findViewById(R.id.chYear);
        chipM=(Chip) findViewById(R.id.chMonth);
        chipW=(Chip) findViewById(R.id.chWeek);
        chipD=(Chip) findViewById(R.id.chDay);
        chipGroup=(ChipGroup) findViewById(R.id.chipGroup);
        progressBar=(ProgressBar) findViewById(R.id.progressBar);

        if (hasConnection(MainActivity.this)) {
            allTask = Tasks.whenAll(configTask, AuthTask, GraphTask);
            getUid();
            getRemoteConfig();
            getWidth();



            allTask.addOnSuccessListener(new OnSuccessListener<Void>() {
                @Override
                public void onSuccess(Void aVoid) {
                    devId = (String) configTask.getResult();
                    Log.d(LOG_TAG, "DeviceId " + devId);
                    uid = (String) AuthTask.getResult();
                    Log.d(LOG_TAG, "uid " + uid);
                    runOther();
                }
            });
            allTask.addOnFailureListener(new OnFailureListener() {
                @Override
                public void onFailure(@NonNull Exception e) {
                    Log.w(LOG_TAG, "error ");
                }
            });
        }else{
            uidView.setText("Нет подключения к сети");
        }

    }


    public void runOther() {
        loadPref();
        uidView.setText(uid);
        runWorker();
        startServ();

        showGraph();


    }
    private void runWorker(){
        //WorkManager.getInstance().cancelAllWorkByTag(PACKAGEID+".FirebaseReadWorker");
        //Log.d(LOG_TAG, "worker stop");
        if (!devId.equals("") && workerOn) {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NOT_ROAMING)
                    .build();

            PeriodicWorkRequest myWorkRequest =
                    new PeriodicWorkRequest.Builder(FirebaseReadWorker.class,
                            workerInterval, TimeUnit.MINUTES,
                            workerInterval-5, TimeUnit.MINUTES)
                            .setConstraints(constraints)
                            .build();

//           OneTimeWorkRequest myWorkRequest = new OneTimeWorkRequest.Builder(FirebaseReadWorker.class).build();
            WorkManager.getInstance().enqueueUniquePeriodicWork("FirebaseReadWork",
                    ExistingPeriodicWorkPolicy.KEEP, myWorkRequest);
            Log.d(LOG_TAG, "worker run");
        }

    }

    private void addPoint(int i, JSONObject obj,LineGraphSeries<DataPoint> mSeries, int len) throws JSONException {
        long time= obj.getLong("x");
        Date date = new Date(time);
        mSeries.appendData(new DataPoint(date,
                        obj.getDouble("y")),
                false, len);
    }

    private void showGraph(){
        GraphView graph = (GraphView) findViewById(R.id.graph);
        mSeriesT = new LineGraphSeries<>();
        mSeriesH = new LineGraphSeries<>();
        mSeriesCo2 = new LineGraphSeries<>();
        mSeriesH.setTitle("H");
        mSeriesT.setTitle("T");
        mSeriesCo2.setTitle("CO2");
        mSeriesT.setThickness(1);
        mSeriesH.setThickness(1);
        mSeriesCo2.setThickness(1);
        chipGroup.setOnCheckedChangeListener(new ChipGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(ChipGroup group, @IdRes int checkedId) {
                switch (checkedId) {
                    case R.id.chYear : mode=0;
                        break;
                    case R.id.chMonth : mode=1;
                        break;
                    case R.id.chWeek : mode=2;
                        break;
                    case R.id.chDay: mode=3;
                        break;
                    default: mode=3;
                }
                redrawGraf();
            }
        });

        graph.getSecondScale().addSeries(mSeriesT);
        mSeriesT.setColor(Color.RED);
        graph.getSecondScale().addSeries(mSeriesH);
        mSeriesH.setColor(Color.GREEN);
        graph.getSecondScale().setMinY(0);
        graph.getSecondScale().setMaxY(100);
        graph.getGridLabelRenderer().setVerticalLabelsSecondScaleColor(Color.RED);
        graph.getLegendRenderer().setVisible(true);
        graph.getLegendRenderer().setAlign(LegendRenderer.LegendAlign.TOP);
        graph.getLegendRenderer().setBackgroundColor(Color.WHITE);
        graph.setTitle("Показания датчиков");
        graph.setTitleTextSize(18f);

        graph.addSeries(mSeriesCo2);
        graph.getGridLabelRenderer().setLabelFormatter(new DateAsXAxisLabelFormatter(this));
        graph.getGridLabelRenderer().setNumHorizontalLabels(5); // only 4 because of the space
        graph.getGridLabelRenderer().setVerticalAxisTitle("CO2");
       // graph.getGridLabelRenderer().setHumanRounding(false);
        mFunctions = FirebaseFunctions.getInstance();
        redrawGraf();

    }

    private  void redrawGraf(){
        Map<String, Object> data = new HashMap<>();
        data.put("dev", devId);
        data.put("width", graphWidth);
        data.put("mode", mode);
        progressBar.setVisibility(ProgressBar.VISIBLE);
        mFunctions
                .getHttpsCallable("get_data") //Whatever your endpoint is called
                .call(data)
                .addOnSuccessListener(this, new OnSuccessListener<HttpsCallableResult>() {
                    @Override
                    public void onSuccess(HttpsCallableResult httpsCallableResult) {
                        try{
                            Gson g = new Gson();
                            String json = g.toJson(httpsCallableResult.getData());
                            JSONObject Res=new JSONObject(json) ;
                            Log.d("Graph", "Prepare T");
                            mSeriesT.resetData(drawLine(Res.getJSONArray("t")));
                            Log.d("Graph", "Prepare H");
                            mSeriesH.resetData(drawLine(Res.getJSONArray("h")));
                            Log.d("Graph", "Prepare CO2");
                            mSeriesCo2.resetData(drawLine(Res.getJSONArray("co2")));
                        } catch (Exception e){
                            Log.d("GraphError",e.toString());
                        } finally {
                            progressBar.setVisibility(ProgressBar.INVISIBLE);
                        }
                    }
                });
    }

    private DataPoint[] drawLine(JSONArray T) throws JSONException {
        int len= T.length();
        DataPoint[] values = new DataPoint[len];
        long prev=0;
        long cur;
        int j=0;
        for (int i = 0; i < len; i++) {
            cur=T.getJSONObject(i).getLong("x");
            DataPoint v;
            if (prev<cur) {
               v = new DataPoint(cur, T.getJSONObject(i).getDouble("y"));
                prev = cur;
            }else {
                prev++;
                v = new DataPoint(prev, T.getJSONObject(i).getDouble("y"));
            }
            values[i] = v;
        }
        return values;
    }

    private void checkPower(){
        //проверка отключения режима экономии
        if (Build.VERSION.SDK_INT >= 23) {

            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            boolean inWhiteList = powerManager
                    .isIgnoringBatteryOptimizations(PACKAGEID);


            if (!inWhiteList) {
                Log.d(LOG_TAG, "CisIgnoringBatteryOptimizations  problem");
                Intent intent1 = new
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent1);
            } else {
                Log.d(LOG_TAG, "CisIgnoringBatteryOptimizations  OK");
            }
        }
    }

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Float co2 = intent.getFloatExtra("co2", 0);
            Float t = intent.getFloatExtra("t", 0);
            Float h = intent.getFloatExtra("h", 0);
            String dt = intent.getStringExtra("dt");
            Log.d(LOG_TAG, "Data from service");
            dtView.setText(dataTransfer.setDt(dt));
            t_textView.setText(dataTransfer.setT(t));
            h_textView.setText(dataTransfer.setH(h));
            co2_textView.setText(dataTransfer.setCo2(co2));
        }
    };

    private BroadcastReceiver mNetworkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

        }
    };

    @Override
    protected void onStop() {

        super.onStop();
        stopServ();

    }

    @Override
    protected void onPause() {
        super.onPause();
        stopServ();
    }

    private void stopServ() {
        if (serviceOn) {
            stopService(new Intent(MainActivity.this,
                    FirebaseReadService.class));
            LocalBroadcastManager.getInstance(this).
                    unregisterReceiver(mMessageReceiver);
            serviceOn = false;
        }
    }


    @Override
    protected void onResume() {
        super.onResume();
        startServ();
    }

    private void startServ() {
        if(!serviceOn && hasConnection(MainActivity.this)) {
            startService(new Intent(MainActivity.this,
                    FirebaseReadService.class)
                    .putExtra("devId", devId));
            LocalBroadcastManager.getInstance(this).registerReceiver(
                    mMessageReceiver, new IntentFilter(PACKAGEID + ".co2_data"));
            serviceOn = true;
        }
    }


    public void getUid() {
        Log.w(LOG_TAG, "start user");
        mAuth = FirebaseAuth.getInstance();
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            Log.w(LOG_TAG, "old user");
            AuthProc.setResult(saveUid());
        } else {
            Log.w(LOG_TAG, "new user");
            signInAnonymously();
        }

    }

    private String saveUid() {
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        DatabaseReference myU = database.getReference("u/" + uid);
        Date date = new Date();
        SimpleDateFormat formatter = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
        myU.setValue(formatter.format(date));
        return uid;
    }

    private void signInAnonymously() {
        mAuth.signInAnonymously()
                .addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
                    @Override
                    public void onComplete(@NonNull Task<AuthResult> task) {
                        String uid;
                        if (task.isSuccessful()) {
                            // Sign in success, update UI with the signed-in user's information
                            Log.d(LOG_TAG, "signInAnonymously:success");
                            FirebaseUser user = mAuth.getCurrentUser();
                            assert user != null;
                            uid = user.getUid();
                            AuthProc.setResult(saveUid());
                        } else {
                            // If sign in fails, display a message to the user.
                            Log.w(LOG_TAG, "signInAnonymously:failure", task.getException());
                            uid = "-----";
                            AuthProc.setResult(uid);
                        }
                    }
                });

    }


    public void getRemoteConfig() {
        Log.d(LOG_TAG, "start Config params updated: " );
        sPref = this.getSharedPreferences(PACKAGEID + "_preferences", MODE_PRIVATE); //
        devId = sPref.getString(DEVICEID, "");
        if (devId == "") {
            Log.d(LOG_TAG, "firebase Config params updated: " );
            mFirebaseRemoteConfig = FirebaseRemoteConfig.getInstance();
            FirebaseRemoteConfigSettings configSettings = new FirebaseRemoteConfigSettings.Builder()
                    .setMinimumFetchIntervalInSeconds(3600)
                    .build();
            mFirebaseRemoteConfig.setConfigSettingsAsync(configSettings);
            mFirebaseRemoteConfig.setDefaultsAsync(R.xml.remote_config_defaults);
            mFirebaseRemoteConfig.fetchAndActivate()
                    .addOnCompleteListener(this, new OnCompleteListener<Boolean>() {
                        @Override
                        public void onComplete(@NonNull Task<Boolean> task) {
                            if (task.isSuccessful()) {
                                boolean updated = task.getResult();
                                Log.d(LOG_TAG, "Config params updated: " + updated);
                                String devId = mFirebaseRemoteConfig.getString(DEVICEID);
                                remConfig.setResult(devId);
                                SharedPreferences.Editor ed = sPref.edit();
                                ed.putString("deviceId", devId);
                                ed.apply();

                            } else {
                                Log.w(LOG_TAG, "Config update error: ");
                                remConfig.setResult("");
                            }
                        }
                    });

        } else {
            Log.d(LOG_TAG, "finish Config params updated: " );
            remConfig.setResult(devId);
        }
    }
public void getWidth(){
    ViewTreeObserver vto = graphLayout.getViewTreeObserver();
    vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            graphLayout.getViewTreeObserver().removeOnPreDrawListener(this); // удаляем листенер, иначе уйдём в бесконечный цикл
            graphWidth=graphLayout.getWidth();
            getGraph.setResult(true);
            return true;
        }
    });
}


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater menuInflater = getMenuInflater();
        menuInflater.inflate(R.menu.settings_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadPref() {
        sPref = PreferenceManager.getDefaultSharedPreferences(this);
        sPref.registerOnSharedPreferenceChangeListener(this);
        loadPref_();

    }

    private void loadPref_(){
        String s;
        s=sPref.getString("CO2", "0.0");
        maxCO2=Float.valueOf(s);
        s=sPref.getString("H", "0.0");
        minH=Float.valueOf(s);
        s=sPref.getString("T", "0.0");
        minT=Float.valueOf(s);
        workerOn=sPref.getBoolean("worker_on", true);
        s=sPref.getString("worker_interval", "15");
        workerInterval=Integer.valueOf(s);
        workerInterval=(workerInterval>=15)?workerInterval:15;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals("worker_on") || key.equals("worker_interval")){
            workerOn=sPref.getBoolean("worker_on", true);
            String s=sPref.getString("worker_interval", "15");
            workerInterval=Integer.parseInt(s);
            runWorker();
        }
        loadPref_();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopServ();
        SharedPreferences mSettings = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        mSettings.unregisterOnSharedPreferenceChangeListener(this);

    }

    public static boolean hasConnection(final Context context)
    {
        ConnectivityManager cm = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        assert cm != null;
        NetworkInfo wifiInfo = cm.getActiveNetworkInfo();
        //return wifiInfo.getType() == ConnectivityManager.TYPE_WIFI;
        return true;
    }
}
