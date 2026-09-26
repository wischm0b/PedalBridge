package de.smb1display;

import android.Manifest;
import android.app.Activity;
import android.app.UiModeManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Switch;

import android.content.Intent;
import android.widget.AdapterView;
import android.view.View;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.ArrayList;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final int BLUETOOTH_PERMISSION_REQUEST = 42;
    private static final String DEVICE_NAME = "PedalBridge";
    private static final String TRAINER_NAME = "SpinRelay";
    private static final UUID SERVICE_UUID = UUID.fromString("9f6c1000-5a7b-4fd0-9a9f-6c30a7e63110");
    private static final UUID LIVE_UUID = UUID.fromString("9f6c1001-5a7b-4fd0-9a9f-6c30a7e63110");
    private static final UUID CONTROL_UUID = UUID.fromString("9f6c1002-5a7b-4fd0-9a9f-6c30a7e63110");
    private static final UUID HISTORY_UUID = UUID.fromString("9f6c1003-5a7b-4fd0-9a9f-6c30a7e63110");
    private static final UUID MANAGEMENT_UUID = UUID.fromString("9f6c1004-5a7b-4fd0-9a9f-6c30a7e63110");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic controlCharacteristic;
    private boolean scanning;
    private boolean connected;
    private boolean manualDisconnect;
    private int unexpectedDisconnects;

    private TextView status;
    private TextView power,heartRate,heartRateStatus;
    private HeartRateClient heartRateClient;
    private java.util.List<HeartRateClient.Device> heartRateDevices=new java.util.ArrayList<>();
    private AppDialog heartRatePicker;
    private AppDialog activeDialog;
    private LinearLayout heartRateDevicesList;
    private TextView heartRateSearchStatus;
    private android.widget.ProgressBar heartRateSearchProgress;

    private TextView cadence;
    private TextView speed;
    private TextView distance;
    private TextView duration;
    private TextView calories;
    private TextView resistance,bridgeLink,bikeLink,garminLink;
    private boolean bikeLinked,bikeFresh,garminLinked,trainerLinked;
    private TextView detail;
    private TextView themeButton;
    private Button connectButton;
    private Button resetButton;
    private Button wifiButton;
    private BluetoothGattCharacteristic historyCharacteristic;
    private BluetoothGattCharacteristic managementCharacteristic;
    private TrainingDatabase archiveDb;
    private JSONObject espStorage;
    private TextView storageInfo;
    private TextView importInfo;
    private int historyBefore=0,rawIndex=0,lastCommand=0,ackRetries=0;
    private boolean rawSync=false,awaitingAck=false,importSync=false;
    private boolean servicesRecovering=false,cacheRefreshAttempted=false,managementReading=false,initialStatusPending=false;
    private boolean settingsDirty=false,settingUi=false;
    private TextView settingsState,brightnessLabel;
    private SeekBar brightnessControl;
    private Spinner patternControl,idleControl;
    private Switch recordingControl;
    private Button saveSettings,statusRefresh;
    private String settingsError="";
    private long pendingImportChecksum=0; private int pendingImportBytes=0;
    private long rawSessionId=0;
    private JSONArray rawQueue=new JSONArray();
    private String activeArchive="";
    private final java.util.concurrent.ExecutorService archiveIo=java.util.concurrent.Executors.newSingleThreadExecutor();
    private TextView historyStatus;
    private LinearLayout historyCards;
    private Spinner historyDevice;
    private android.widget.ImageButton historyRefresh;
    private JSONObject historyJson;
    private boolean historyLoading, controlBusy;
    private int historyTransfer = (int)(System.nanoTime() & 0xffff);
    private int historyOffset, historyTotal, historyRetries;
    private final ByteArrayOutputStream historyBytes = new ByteArrayOutputStream();
    private String historyCacheKey = "history";
    private final Runnable historyTimeout = () -> historyFailed("Übertragung unterbrochen. Bitte erneut laden.");
    private long lastSuccessfulSyncMs=0;
    private int lastDisconnectStatus=0;
    private boolean syncAfterClock=false,notificationsInitialized=false;
    private final Runnable reconnect=()->{if(!manualDisconnect && !connected && gatt==null)beginConnection();};

    @Override
    protected void onCreate(Bundle state) {
        applySavedTheme();
        super.onCreate(state);
        archiveDb=new TrainingDatabase(this);
        heartRateClient=new HeartRateClient(this,new HeartRateClient.Listener(){
            public void state(int bpm,String message){if(heartRate!=null){heartRate.setText(bpm>0?"♥  "+bpm+" bpm":"♥  — bpm");heartRate.setContentDescription("Live-Puls: "+(bpm>0?bpm+" Schläge pro Minute":message));}if(heartRateStatus!=null)heartRateStatus.setText(message);if(heartRateSearchStatus!=null)heartRateSearchStatus.setText(message);if(heartRateSearchProgress!=null)heartRateSearchProgress.setVisibility(heartRateClient.isSearching()?View.VISIBLE:View.GONE);}
            public void devices(java.util.List<HeartRateClient.Device> devices){heartRateDevices=devices;renderHeartRateDevices();}
        });
        inflateUi();

        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
        if (adapter == null || !adapter.isEnabled()) {
            showState("●  Bluetooth ausgeschaltet", false);
        } else if ((checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) {
            beginConnection();
        } else {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT}, BLUETOOTH_PERMISSION_REQUEST);
        }
    }

    private void inflateUi() {
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        status = findViewById(R.id.status);
        power = findViewById(R.id.power);heartRate=findViewById(R.id.heartRate);
        cadence = findViewById(R.id.cadence);
        speed = findViewById(R.id.speed);
        distance = findViewById(R.id.distance);
        duration = findViewById(R.id.duration);
        calories = findViewById(R.id.calories);
        resistance=findViewById(R.id.resistance);bridgeLink=findViewById(R.id.bridgeLink);bikeLink=findViewById(R.id.bikeLink);garminLink=findViewById(R.id.garminLink);
        updateConnectionIcons();
        detail = findViewById(R.id.detail);
        themeButton = findViewById(R.id.themeButton);
        connectButton = findViewById(R.id.connectButton);
        resetButton = findViewById(R.id.resetButton);
        wifiButton = findViewById(R.id.wifiButton);
        setupHistory();
        setupTabs();

        updateThemeLabel();
        themeButton.setOnClickListener(v -> chooseTheme());
        connectButton.setOnClickListener(v -> {
            if (connected || scanning || gatt != null) disconnectManually();
            else beginConnection();
        });
        resetButton.setOnClickListener(v -> resetSession());
        wifiButton.setOnClickListener(v -> { if(espStorage!=null) sendControl((byte)(espStorage.optBoolean("wifi")?2:0x21),"WLAN wird umgeschaltet"); });
        resetButton.setEnabled(connected);
        wifiButton.setEnabled(connected && espStorage!=null);
        connectButton.setText(connected?"Verbindung trennen":scanning?"Suche abbrechen":"Bridge verbinden");
        if(espStorage!=null)updateStorage(espStorage);
        if(heartRateClient!=null)heartRateClient.refresh();

    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        CharSequence[] values={status.getText(),power.getText(),cadence.getText(),speed.getText(),distance.getText(),duration.getText(),calories.getText(),detail.getText(),historyStatus.getText(),resistance.getText()};
        int statusVisibility=status.getVisibility();
        boolean dirty=settingsDirty;int brightness=brightnessControl.getProgress(),pattern=patternControl.getSelectedItemPosition(),idle=idleControl.getSelectedItemPosition();boolean recording=recordingControl.isChecked();
        getTheme().applyStyle(R.style.AppTheme,true);
        inflateUi();
        TextView[] views={status,power,cadence,speed,distance,duration,calories,detail,historyStatus,resistance};for(int i=0;i<views.length;i++)views[i].setText(values[i]);
        status.setTextColor(getColor(connected?R.color.good:R.color.muted));status.setVisibility(statusVisibility);
        if(dirty){settingUi=true;brightnessControl.setProgress(brightness);patternControl.setSelection(pattern);idleControl.setSelection(idle);recordingControl.setChecked(recording);settingUi=false;settingsDirty=true;}
        historyRefresh.setEnabled(!historyLoading);updateSettingsAvailability();
        boolean dark=(config.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        getWindow().getDecorView().getWindowInsetsController().setSystemBarsAppearance(dark?0:android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode != BLUETOOTH_PERMISSION_REQUEST) return;
        boolean granted = true;
        for (int result : grants) granted &= result == PackageManager.PERMISSION_GRANTED;
        if (granted) {beginConnection();heartRateClient.restore();}
        else showState("●  Bluetooth-Berechtigung fehlt", false);
    }

    private boolean hasBluetoothPermissions() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void beginConnection() {
        if (!(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) || scanner == null || scanning || connected || gatt!=null) return;
        handler.removeCallbacks(reconnect);
        manualDisconnect = false; cacheRefreshAttempted=false;servicesRecovering=false;
        scanning = true;
        showState("●  Bridge wird gesucht …", false);
        connectButton.setText("Suche abbrechen");
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(null, settings, scanCallback);
        handler.postDelayed(scanTimeout, 15_000);
    }

    private final Runnable scanTimeout = () -> {
        if (!scanning) return;
        stopScan();
        showState("●  PedalBridge nicht gefunden", false);
        detail.setText("Bridge einschalten und in Reichweite bringen.");
        connectButton.setText("Erneut suchen");
        if(!manualDisconnect && unexpectedDisconnects>0){handler.removeCallbacks(reconnect);handler.postDelayed(reconnect,30000);}
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
            if (!scanning || gatt != null) return;
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            if (name == null && (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) name = result.getDevice().getName();
            if (!DEVICE_NAME.equals(name) && !TRAINER_NAME.equals(name) && !"SMB1 Bridge".equals(name) && !"SMB1 Trainer".equals(name) && !"SpinRelay Bridge".equals(name)) return;
            stopScan();
            showState("●  Bridge wird verbunden …", false);
            gatt = result.getDevice().connectGatt(MainActivity.this, false, gattCallback,
                    android.bluetooth.BluetoothDevice.TRANSPORT_LE);
        }

        @Override public void onScanFailed(int errorCode) {
            runOnUiThread(() -> {
                scanning = false;
                showState("●  Bluetooth-Suche fehlgeschlagen", false);
                detail.setText("Android BLE-Fehler " + errorCode);
                connectButton.setText("Erneut suchen");
            });
        }
    };

    private void stopScan() {
        handler.removeCallbacks(scanTimeout);
        if (scanning && scanner != null && (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) scanner.stopScan(scanCallback);
        scanning = false;
    }

    private void discoverSafely(BluetoothGatt source){
        if(source!=gatt || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return;
        try{if(!source.discoverServices()){servicesRecovering=false;historyStatus.setText("Dienste konnten nicht geladen werden. Erneut synchronisieren.");}}catch(SecurityException e){servicesRecovering=false;historyStatus.setText("Bluetooth-Berechtigung fehlt.");}
    }
    private BluetoothGattCharacteristic findCharacteristic(BluetoothGatt source,UUID uuid) {
        for(BluetoothGattService service:source.getServices()){BluetoothGattCharacteristic value=service.getCharacteristic(uuid);if(value!=null)return value;}return null;
    }
    private void recoverServices() {
        if(gatt==null || !hasBluetoothPermissions() || servicesRecovering)return;
        notificationsInitialized=false;servicesRecovering=true;historyLoading=false;managementReading=false;initialStatusPending=false;
        historyStatus.setText("Verbindung steht · Bluetooth-Dienste werden neu geladen …");updateSettingsAvailability();
        BluetoothGatt source=gatt;
        handler.postDelayed(()->{if(source==gatt && servicesRecovering)refreshCachedServices(source);},5000);
        // Ask the firmware to send the standard GATT Service Changed indication.
        // Recover this phone only; a global Service Changed indication also disrupts Garmin.
        handler.postDelayed(()->refreshCachedServices(source),250);
    }
    private void refreshCachedServices(BluetoothGatt source) {
        if(source!=gatt || !hasBluetoothPermissions() || (historyCharacteristic!=null && managementCharacteristic!=null))return;
        if(cacheRefreshAttempted){servicesRecovering=false;historyStatus.setText("Archivdienst nicht gefunden. Bluetooth-Gerät in Android entfernen und neu verbinden.");updateSettingsAvailability();return;}
        notificationsInitialized=false;cacheRefreshAttempted=true;controlBusy=false;handler.removeCallbacks(historyTimeout);
        // Android offers no public cache invalidation API. This optional fallback
        // is bounded to one attempt; unavailable implementations fail visibly.
        try{source.getClass().getMethod("refresh").invoke(source);}catch(Exception ignored){}
        handler.postDelayed(()->{if(source==gatt){servicesRecovering=false;discoverSafely(source);}},700);
    }
    private void beginArchiveSync(){
        if(!connected || controlBusy || historyLoading || servicesRecovering)return;
        syncAfterClock=lastSuccessfulSyncMs==0 || android.os.SystemClock.elapsedRealtime()-lastSuccessfulSyncMs>600_000;
        byte[] clock=ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put((byte)3).putInt((int)(System.currentTimeMillis()/1000)).array();writeCommand(clock);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt source, int statusCode, int newState) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
            if(source!=gatt)return;
            if (newState == BluetoothProfile.STATE_CONNECTED && statusCode == BluetoothGatt.GATT_SUCCESS) {
                if ((checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) {
                    source.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
                    if(!source.requestMtu(247)) discoverSafely(source);
                }
                runOnUiThread(() -> showState("●  Dienste werden geladen …", false));
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                source.close();if(gatt!=source)return;
                if (gatt == source) gatt = null;
                controlCharacteristic = null;
                connected = false;notificationsInitialized=false;
                lastDisconnectStatus=statusCode;
                if (!manualDisconnect) unexpectedDisconnects++;
                runOnUiThread(() -> {
                    historyCharacteristic = null;managementCharacteristic=null;espStorage=null;managementReading=false;initialStatusPending=false;servicesRecovering=false;
                    historyFailed("Offline · zuletzt geladene Historie");
                    showState("●  Verbindung getrennt", false);
                    detail.setText("Bluetooth-Abbruch " + unexpectedDisconnects
                            + " · GATT-Status " + statusCode + " · neuer Versuch läuft");
                    connectButton.setText("Bridge verbinden");
                    resetButton.setEnabled(false);
                    wifiButton.setEnabled(false);
                    handler.removeCallbacks(managementTimeout);handler.removeCallbacks(reconnect);
                    if (!manualDisconnect) handler.postDelayed(reconnect,Math.min(30000,2500L*Math.max(1,unexpectedDisconnects)));
                });
            }
        }

        @Override public void onServiceChanged(BluetoothGatt source) {
            runOnUiThread(()->{
                if(source!=gatt || !hasBluetoothPermissions())return;
                historyFailed("Bluetooth-Dienste werden aktualisiert …");notificationsInitialized=false;servicesRecovering=true;managementReading=false;initialStatusPending=false;
                historyCharacteristic=null;managementCharacteristic=null;espStorage=null;
                handler.postDelayed(()->{if(source==gatt && servicesRecovering)refreshCachedServices(source);},5000);
                handler.postDelayed(()->{if(source==gatt)discoverSafely(source);},250);
            });
        }

        @Override public void onMtuChanged(BluetoothGatt source,int mtu,int status) {
            if(source==gatt && hasBluetoothPermissions()) discoverSafely(source);
        }

        @Override public void onServicesDiscovered(BluetoothGatt source, int result) {
            if(source!=gatt)return;
            BluetoothGattCharacteristic live=findCharacteristic(source,LIVE_UUID);
            controlCharacteristic=findCharacteristic(source,CONTROL_UUID);
            historyCharacteristic=findCharacteristic(source,HISTORY_UUID);
            managementCharacteristic=findCharacteristic(source,MANAGEMENT_UUID);
            if (result != BluetoothGatt.GATT_SUCCESS || live == null) {
                runOnUiThread(() -> {
                    showState("Bluetooth-Dienste nicht vollständig geladen",false);
                    detail.setText("Bitte in den Einstellungen die Verbindung erneut herstellen.");
                });
                return;
            }
            if (!(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) return;
            source.setCharacteristicNotification(live, true);
            BluetoothGattDescriptor cccd = live.getDescriptor(CCCD_UUID);
            if (cccd == null) return;
            if (Build.VERSION.SDK_INT >= 33) {
                source.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            } else {
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                source.writeDescriptor(cccd);
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt source, BluetoothGattDescriptor descriptor, int result) {
            if (source!=gatt || !CCCD_UUID.equals(descriptor.getUuid()) || result != BluetoothGatt.GATT_SUCCESS) return;
            if(notificationsInitialized)return;notificationsInitialized=true;
            connected = true;
            runOnUiThread(() -> {
                showState("●  Live verbunden", true);
                connectButton.setText("Verbindung trennen");
                resetButton.setEnabled(true);
                wifiButton.setEnabled(true);
                historyCacheKey = "history_" + source.getDevice().getAddress();
                getSharedPreferences("display", MODE_PRIVATE).edit().putString("lastHistory", historyCacheKey).apply();
                loadHistoryCache();
                if(historyCharacteristic==null || managementCharacteristic==null){
                    if(!cacheRefreshAttempted && !servicesRecovering)recoverServices();
                    else if(!servicesRecovering){historyStatus.setText("Live verbunden, aber Archivdienst fehlt. In Einstellungen die Dienste erneut laden.");updateSettingsAvailability();}
                }else{
                    servicesRecovering=false;initialStatusPending=true;readManagement();
                }
            });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt source,
                                                        BluetoothGattCharacteristic characteristic,
                                                        byte[] value) {
            if (source==gatt && LIVE_UUID.equals(characteristic.getUuid())) parseLiveData(value);
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt source,
                                                        BluetoothGattCharacteristic characteristic) {
            if (source==gatt && Build.VERSION.SDK_INT<33 && LIVE_UUID.equals(characteristic.getUuid())) parseLiveData(characteristic.getValue());
        }

        @Override public void onCharacteristicWrite(BluetoothGatt source,
                BluetoothGattCharacteristic characteristic, int result) {
            if (!CONTROL_UUID.equals(characteristic.getUuid())) return;
            runOnUiThread(() -> {
                if (source != gatt) return;
                controlBusy = false;
                handler.removeCallbacks(historyTimeout);
                if (result != BluetoothGatt.GATT_SUCCESS) {
                    historyFailed("Bluetooth-Schreiben fehlgeschlagen: " + result); return;
                }
                updateSettingsAvailability();
                if(lastCommand==0x16){handler.postDelayed(()->refreshCachedServices(source),1800);return;}
                if(lastCommand==0x20 || lastCommand==0x21 || lastCommand==2){if(lastCommand==0x20)settingsDirty=false;handler.postDelayed(MainActivity.this::readManagement,250);return;}
                if(awaitingAck) handler.postDelayed(MainActivity.this::readManagement,120);
                else if (historyLoading) handler.postDelayed(MainActivity.this::readHistoryPage, 30);
                else if(lastCommand==1 || (lastCommand==3 && syncAfterClock)){syncAfterClock=false;handler.postDelayed(()->{if(source==gatt)refreshHistory();},150);}
            });
        }

        @Override public void onCharacteristicRead(BluetoothGatt source,
                BluetoothGattCharacteristic characteristic, byte[] value, int result) {
            if(MANAGEMENT_UUID.equals(characteristic.getUuid()))
                runOnUiThread(()->{if(source==gatt) receiveManagement(value,result);});
            if (HISTORY_UUID.equals(characteristic.getUuid()))
                runOnUiThread(() -> { if (source == gatt) receiveHistory(value, result); });
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicRead(BluetoothGatt source,
                BluetoothGattCharacteristic characteristic, int result) {
            if(Build.VERSION.SDK_INT<33 && MANAGEMENT_UUID.equals(characteristic.getUuid())) {
                byte[] value=characteristic.getValue();
                runOnUiThread(()->{if(source==gatt) receiveManagement(value,result);});
            }
            if (Build.VERSION.SDK_INT < 33 && HISTORY_UUID.equals(characteristic.getUuid())) {
                byte[] value = characteristic.getValue();
                runOnUiThread(() -> { if (source == gatt) receiveHistory(value, result); });
            }
        }
    };

    private void parseLiveData(byte[] bytes) {
        if (bytes == null || bytes.length < 16 || bytes[0] < 1 || bytes[0] > 3 || (bytes[0]==3 && bytes.length<20)) return;
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        data.get();
        int flags = Byte.toUnsignedInt(data.get());
        int watts = data.getShort();
        double rpm = Short.toUnsignedInt(data.getShort()) / 10.0;
        double kmh = Short.toUnsignedInt(data.getShort()) / 100.0;
        double km = Integer.toUnsignedLong(data.getInt()) / 100000.0;
        long seconds = Integer.toUnsignedLong(data.getInt());
        double kcal = bytes.length >= 18 ? Short.toUnsignedInt(data.getShort()) / 10.0 : 0.0;
        int level=bytes[0]>=3 ? data.getShort():0;
        boolean hasLevel=bytes[0]>=3 && (flags & 16)!=0;
        boolean fresh = (flags & 1) != 0;
        boolean bike = (flags & 2) != 0;
        boolean garmin = (flags & 4) != 0;
        boolean garminTrainer = (flags & 8) != 0;
        runOnUiThread(() -> {
            setMetric(power,String.format(Locale.GERMANY,"%d",watts),"W");
            setMetric(cadence,String.format(Locale.GERMANY,"%.0f",rpm),"rpm");
            setMetric(speed,String.format(Locale.GERMANY,"%.1f",kmh),"km/h");
            setMetric(distance,String.format(Locale.GERMANY,"%.2f",km),"km");
            duration.setText(formatDuration(seconds));
            setMetric(calories,String.format(Locale.GERMANY,"%.0f",kcal),"kcal");
            resistance.setText(hasLevel && fresh && bike ? Integer.toString(level):"—");
            bikeLinked=bike;bikeFresh=fresh;garminLinked=garmin;trainerLinked=garminTrainer;updateConnectionIcons();
            String garminState = garminTrainer ? "Garmin Trainer ✓"
                    : garmin ? "Garmin Power ✓" : "Garmin –";
            detail.setText("Bike " + (bike ? "✓" : "–") + "   ·   "
                    + garminState + (fresh ? "" : "   ·   keine aktuellen Daten"));
        });
    }

    private void setMetric(TextView view,String number,String unit) {
        android.text.SpannableString text=new android.text.SpannableString(number+" "+unit);
        text.setSpan(new android.text.style.RelativeSizeSpan(.45f),number.length()+1,text.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(text);
    }
    private void linkState(TextView view,String title,String state,int color) {
        view.setText(title+"\n"+state);view.setTextColor(getColor(color));
        view.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(getColor(color)));
        view.setContentDescription(title+": "+state);
    }
    private void updateConnectionIcons() {
        if(bridgeLink==null)return;
        linkState(bridgeLink,"Bridge",connected?"Verbunden":scanning || gatt!=null?"Verbinden …":"Getrennt",connected?R.color.good:scanning || gatt!=null?R.color.waiting:R.color.muted);
        linkState(bikeLink,"Bike",!connected?"Unbekannt":bikeLinked?bikeFresh?"Verbunden":"Keine Daten":"Getrennt",connected && bikeLinked?bikeFresh?R.color.good:R.color.waiting:R.color.muted);
        linkState(garminLink,"Garmin",!connected?"Unbekannt":trainerLinked?"Trainer":garminLinked?"Leistung":"Getrennt",connected && (garminLinked || trainerLinked)?R.color.good:R.color.muted);
    }

    private static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        return hours > 0
                ? String.format(Locale.GERMANY, "%d:%02d:%02d", hours, minutes, rest)
                : String.format(Locale.GERMANY, "%02d:%02d", minutes, rest);
    }

    private void resetSession() {
        sendControl((byte) 1, "Sitzung wird auf dem ESP gespeichert");
    }

    private void sendControl(byte commandValue, String confirmation) {
        if (historyLoading || controlBusy) {
            AppDialog.notice(this,"Bitte die Bluetooth-Übertragung abwarten");
            return;
        }
        if (writeCommand(new byte[]{commandValue}))
            AppDialog.notice(this,confirmation);
    }

    private boolean writeCommand(byte[] command) {
        if (gatt == null || controlCharacteristic == null || !(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) || controlBusy) return false;
        boolean accepted;
        lastCommand=command[0]&255;
        if (Build.VERSION.SDK_INT >= 33) {
            accepted = gatt.writeCharacteristic(controlCharacteristic, command,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS;
        } else {
            controlCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            controlCharacteristic.setValue(command);
            accepted = gatt.writeCharacteristic(controlCharacteristic);
        }
        controlBusy = accepted;updateSettingsAvailability();
        if (accepted) armHistoryTimeout();
        else historyFailed("Bluetooth ist beschäftigt. Bitte erneut laden.");
        return accepted;
    }

    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private Button action(LinearLayout parent,String label,Runnable task) {
        Button button=new Button(this);button.setText(label);button.setAllCaps(false);button.setTextSize(14);button.setTextColor(getColor(R.color.ink));button.setBackgroundResource(android.R.drawable.list_selector_background);button.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);button.setPadding(dp(12),0,dp(12),0);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(52));lp.topMargin=dp(8);parent.addView(button,lp);button.setOnClickListener(v->task.run());return button;
    }
    private void moveSettingRow(TextView row,LinearLayout parent){
        ((android.view.ViewGroup)row.getParent()).removeView(row);row.setTextSize(14);row.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);row.setPadding(dp(12),0,dp(12),0);row.setBackgroundResource(android.R.drawable.list_selector_background);parent.addView(row,new LinearLayout.LayoutParams(-1,dp(52)));
    }
    private LinearLayout settingsGroup(LinearLayout parent,String title) {
        TextView label=historyText(title,13);label.setTextColor(getColor(R.color.muted));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(16);parent.addView(label,lp);
        LinearLayout group=new LinearLayout(this);group.setOrientation(LinearLayout.VERTICAL);group.setPadding(dp(8),dp(8),dp(8),dp(8));group.setBackgroundResource(R.drawable.metric_background);parent.addView(group);return group;
    }
    private void setupHistory() {
        LinearLayout section=findViewById(R.id.historySection),settings=findViewById(R.id.espSection);
        LinearLayout heading=new LinearLayout(this);heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView headingText=historyText("Deine Aktivitäten",26);headingText.setPadding(0,dp(8),0,dp(8));heading.addView(headingText,new LinearLayout.LayoutParams(0,-2,1));
        historyRefresh=new android.widget.ImageButton(this);historyRefresh.setImageResource(R.drawable.nav_sync);historyRefresh.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));historyRefresh.setContentDescription("Historie synchronisieren");historyRefresh.setTooltipText("Synchronisieren");historyRefresh.setBackgroundResource(android.R.drawable.list_selector_background);heading.addView(historyRefresh,new LinearLayout.LayoutParams(dp(48),dp(48)));historyRefresh.setOnClickListener(v->refreshHistory());section.addView(heading);
        LinearLayout appearance=settingsGroup(settings,"Darstellung");moveSettingRow(themeButton,appearance);
        LinearLayout links=settingsGroup(settings,"Verbindung");moveSettingRow(connectButton,links);moveSettingRow(wifiButton,links);
        historyStatus=historyText("Gespeicherte Trainings",13);historyStatus.setTextColor(getColor(R.color.muted));section.addView(historyStatus);
        historyDevice=new Spinner(this);historyDevice.setVisibility(View.GONE);section.addView(historyDevice);
        historyCards=new LinearLayout(this);historyCards.setOrientation(LinearLayout.VERTICAL);section.addView(historyCards);
        historyCacheKey=getSharedPreferences("display",MODE_PRIVATE).getString("lastHistory","history");loadHistoryCache();
        LinearLayout connection=settingsGroup(settings,"Bridge-Status");
        settingsState=historyText("Bridge verbinden, um Geräteeinstellungen zu laden.",13);connection.addView(settingsState);
        statusRefresh=action(connection,"Status aktualisieren",()->{if(connected && !historyLoading && !controlBusy){if(managementCharacteristic==null){cacheRefreshAttempted=false;recoverServices();}else readManagement();}});
        LinearLayout pulse=settingsGroup(settings,"Live-Puls · Fenix / Bluetooth-Sensor");
        heartRateStatus=historyText("Noch kein Pulssensor ausgewählt",13);pulse.addView(heartRateStatus);
        pulse.addView(historyText("Auf der Fenix: Einstellungen → Gesundheit und Wellness → Herzfrequenz am Handgelenk → Herzfrequenz senden. Während Aktivitäten auch automatisch möglich.",12));
        action(pulse,"Pulssensor suchen",this::chooseHeartRateSensor);
        action(pulse,"Puls erneut verbinden",()->heartRateClient.reconnectSaved());
        action(pulse,"Pulsverbindung trennen",()->heartRateClient.disconnect());
        pulse.addView(historyText("Direkt zur App · Puls nur live, noch nicht im Trainingsarchiv. Die Bridge zeichnet auch ohne Handy weiter auf.",12));
        LinearLayout recording=settingsGroup(settings,"Aufzeichnung");
        recordingControl=new Switch(this);recordingControl.setText("Sekundenwerte speichern");recordingControl.setTextColor(getColor(R.color.ink));recordingControl.setPadding(dp(12),dp(10),dp(12),dp(10));recording.addView(recordingControl);
        recording.addView(historyText("Training automatisch abschließen nach",13));
        String[] minutes=new String[30];for(int i=0;i<30;i++)minutes[i]=(i+1)+" Minuten ohne Bewegung";
        idleControl=AppDialog.spinner(this,minutes);recording.addView(idleControl);
        LinearLayout led=settingsGroup(settings,"Statuslicht");
        brightnessLabel=historyText("Helligkeit",14);led.addView(brightnessLabel);
        brightnessControl=new SeekBar(this);brightnessControl.setMax(100);led.addView(brightnessControl);
        patternControl=AppDialog.spinner(this,new String[]{"Verbindungsstatus","Kurzer Impuls","Doppelimpuls","Aus"});led.addView(patternControl);
        led.addView(historyText("Einfarbige LED · die separate Stromanzeige bleibt unverändert.",12));
        saveSettings=action(settings,"Änderungen speichern",this::saveEspSettings);saveSettings.setBackgroundResource(R.drawable.primary_button);saveSettings.setTextColor(getColor(R.color.accent_ink));saveSettings.setGravity(android.view.Gravity.CENTER);
        recordingControl.setOnCheckedChangeListener((v,checked)->{if(!settingUi){settingsDirty=true;updateSettingsAvailability();}});
        brightnessControl.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int value,boolean user){brightnessLabel.setText("Helligkeit · "+value+" %");if(user){settingsDirty=true;updateSettingsAvailability();}}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        AdapterView.OnItemSelectedListener changed=new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int i,long id){if(!settingUi && espStorage!=null){settingsDirty= settingsDirty || patternControl.getSelectedItemPosition()!=espStorage.optInt("ledPattern") || idleControl.getSelectedItemPosition()!=espStorage.optInt("idleMinutes",5)-1;}}public void onNothingSelected(AdapterView<?> p){}};
        patternControl.setOnItemSelectedListener(changed);idleControl.setOnItemSelectedListener(changed);
        LinearLayout memory=settingsGroup(settings,"Speicher & Sicherung");storageInfo=historyText("Speicher wird nach dem Verbinden geladen.",13);memory.addView(storageInfo);importInfo=historyText("",12);memory.addView(importInfo);
        action(memory,"Archiv als ZIP sichern",()->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"PedalBridge-Archiv.zip"),81));
        action(memory,"Trainingsdatei importieren",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),82));
        settings.addView(historyText("PedalBridge Display · Version 0.9.2",12));
        updateSettingsAvailability();
    }
    private void setupTabs() {
        int[] tabs={R.id.tabLive,R.id.tabHistory,R.id.tabEsp};for(int i=0;i<3;i++){final int index=i;findViewById(tabs[i]).setOnClickListener(v->selectTab(index));}
        selectTab(getSharedPreferences("display",MODE_PRIVATE).getInt("tab",0));
    }
    private int selectedTab;
    private float swipeX,swipeY;
    private boolean swipeEligible,swipeCaptured;

    private void selectTab(int index) {
        index=Math.max(0,Math.min(2,index));selectedTab=index;
        int[] pages={R.id.livePage,R.id.historyPage,R.id.espPage},tabs={R.id.tabLive,R.id.tabHistory,R.id.tabEsp};
        for(int i=0;i<3;i++){findViewById(pages[i]).setVisibility(i==index?View.VISIBLE:View.GONE);TextView label=findViewById(tabs[i]);int color=getColor(i==index?R.color.accent:R.color.muted);label.setTextColor(color);label.setTypeface(null,i==index?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);label.setSelected(i==index);for(android.graphics.drawable.Drawable d:label.getCompoundDrawables())if(d!=null)d.mutate().setTint(color);}
        getSharedPreferences("display",MODE_PRIVATE).edit().putInt("tab",index).apply();
    }
    private boolean ownsHorizontalGesture(View view,float x,float y) {
        if(view.getVisibility()!=View.VISIBLE)return false;
        android.graphics.Rect bounds=new android.graphics.Rect();
        if(!view.getGlobalVisibleRect(bounds) || !bounds.contains((int)x,(int)y))return false;
        if(view instanceof ProgressChart || view instanceof SeekBar)return true;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(ownsHorizontalGesture(group.getChildAt(i),x,y))return true;}
        return false;
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        int action=event.getActionMasked();
        if(action==android.view.MotionEvent.ACTION_DOWN){
            swipeX=event.getRawX();swipeY=event.getRawY();swipeCaptured=false;
            int page=selectedTab==0?R.id.livePage:selectedTab==1?R.id.historyPage:R.id.espPage;
            View content=findViewById(page);android.graphics.Rect bounds=new android.graphics.Rect();
            swipeEligible=content!=null && content.getGlobalVisibleRect(bounds) && bounds.contains((int)swipeX,(int)swipeY) && !ownsHorizontalGesture(content,swipeX,swipeY);
        }
        if(event.getPointerCount()>1){swipeEligible=false;if(swipeCaptured){swipeCaptured=false;return true;}}
        float dx=event.getRawX()-swipeX,dy=event.getRawY()-swipeY;
        if(action==android.view.MotionEvent.ACTION_MOVE && swipeEligible && !swipeCaptured){
            if(Math.abs(dy)>dp(24) && Math.abs(dy)>Math.abs(dx))swipeEligible=false;
            int target=selectedTab+(dx<0?1:-1);
            if(Math.abs(dx)>dp(64) && Math.abs(dx)>Math.abs(dy)*1.5 && target>=0 && target<3){
                android.view.MotionEvent cancel=android.view.MotionEvent.obtain(event);cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);super.dispatchTouchEvent(cancel);cancel.recycle();swipeCaptured=true;
            }
        }
        if(swipeCaptured){
            if(action==android.view.MotionEvent.ACTION_UP){if(swipeEligible && Math.abs(dx)>dp(64) && Math.abs(dx)>Math.abs(dy)*1.5)selectTab(selectedTab+(dx<0?1:-1));swipeCaptured=false;swipeEligible=false;}
            if(action==android.view.MotionEvent.ACTION_CANCEL){swipeCaptured=false;swipeEligible=false;}
            return true;
        }
        return super.dispatchTouchEvent(event);
    }
    private void updateSettingsAvailability() {
        if(saveSettings==null)return;
        boolean available=connected && managementCharacteristic!=null && espStorage!=null;
        if(statusRefresh!=null){statusRefresh.setEnabled(connected && !historyLoading && !controlBusy && !managementReading && !servicesRecovering);statusRefresh.setAlpha(statusRefresh.isEnabled()?1f:.45f);}
        saveSettings.setEnabled(available && !historyLoading && !controlBusy && !managementReading);
        boolean editable=available && !controlBusy && !managementReading;brightnessControl.setEnabled(editable);patternControl.setEnabled(editable);idleControl.setEnabled(editable);recordingControl.setEnabled(editable);
        wifiButton.setEnabled(available && !historyLoading && !controlBusy && !managementReading);
        saveSettings.setAlpha(saveSettings.isEnabled()?1f:.45f);wifiButton.setAlpha(wifiButton.isEnabled()?1f:.45f);
        if(!connected)settingsState.setText("Offline · Darstellung und Archiv sind weiterhin verfügbar."+(unexpectedDisconnects==0?"":"\n"+unexpectedDisconnects+" Verbindungsabbrüche · letzter GATT-Status "+lastDisconnectStatus));
        else if(!settingsError.isEmpty())settingsState.setText(settingsError);
        else if(servicesRecovering)settingsState.setText("Bluetooth-Dienste werden neu geladen …");
        else if(!available)settingsState.setText("Verbunden · Geräteeinstellungen werden geladen …");
        else settingsState.setText((unexpectedDisconnects==0?"":unexpectedDisconnects+" Verbindungsabbrüche · letzter GATT-Status "+lastDisconnectStatus+"\n")+(historyLoading?"Verbunden · Archiv wird synchronisiert. Speichern ist danach möglich.":settingsDirty?"Ungespeicherte Änderungen":"Verbunden · Geräteeinstellungen aktuell"));
    }
    private void saveEspSettings() {
        if(!connected || espStorage==null || historyLoading || controlBusy || managementReading){updateSettingsAvailability();return;}
        writeCommand(new byte[]{0x20,(byte)brightnessControl.getProgress(),(byte)patternControl.getSelectedItemPosition(),(byte)(idleControl.getSelectedItemPosition()+1),(byte)(recordingControl.isChecked()?1:0)});
    }

    private TextView historyText(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(getColor(R.color.ink));
        int pad = (int)(12 * getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad, pad, pad);
        return view;
    }

    private void loadHistoryCache() {
        try {
            JSONObject saved=archiveDb.load(historyCacheKey);
            if(saved!=null) {showHistory(saved); historyStatus.setText("Lokales Archiv · "+cacheDate());return;}
        } catch(Exception e) { historyStatus.setText("Lokales Archiv nicht lesbar: "+e.getMessage()); }
        String cached = getSharedPreferences("display", MODE_PRIVATE).getString(historyCacheKey, null);
        if (cached == null) {
            historyJson = null; historyCards.removeAllViews();
            historyDevice.setAdapter(null);
            historyStatus.setText("Noch kein ESP-Archiv geladen"); renderHistory(0); return;
        }
        try { JSONObject old=new JSONObject(cached); archiveDb.savePage(historyCacheKey,old);
            showHistory(old); historyStatus.setText("Gespeicherter Stand · " + cacheDate()); }
        catch (JSONException e) { historyStatus.setText("Gespeicherte Historie nicht lesbar. Bitte neu laden."); }
    }

    private String cacheDate() {
        long saved = getSharedPreferences("display", MODE_PRIVATE).getLong(historyCacheKey + "_time", 0);
        return saved == 0 ? "Datum unbekannt" : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(saved));
    }

    private void armHistoryTimeout() {
        handler.removeCallbacks(historyTimeout); handler.postDelayed(historyTimeout, 12_000);
    }

    private void historyFailed(String message) {
        handler.removeCallbacks(historyTimeout);
        historyLoading = false; controlBusy = false; rawSync=false; awaitingAck=false; importSync=false;
        if (historyStatus != null) historyStatus.setText(message);
        if (historyRefresh != null) historyRefresh.setEnabled(true);
        updateSettingsAvailability();
    }

    private void refreshHistory() {
        if (historyLoading || controlBusy || managementReading || servicesRecovering) return;
        if (!connected || historyCharacteristic == null) {
            if(connected){cacheRefreshAttempted=false;recoverServices();}else historyStatus.setText("Offline · " + cacheDate()); return;
        }
        historyLoading = true; historyRefresh.setEnabled(false);updateSettingsAvailability();
        historyBefore=0; rawSync=false; importSync=false; pendingImportBytes=0; rawQueue=new JSONArray();
        historyBytes.reset(); historyOffset = 0; historyTotal = 0; historyRetries = 0;
        historyTransfer = (historyTransfer + 1) & 0xffff;
        if (historyTransfer == 0) historyTransfer = 1;
        historyStatus.setText("Historie wird über Bluetooth geladen …");
        requestHistoryPage();
    }

    private void requestHistoryPage() {
        if (!historyLoading) return;
        ByteBuffer requestData=ByteBuffer.allocate(rawSync || importSync || historyBefore>0?11:7).order(ByteOrder.LITTLE_ENDIAN);
        requestData.put((byte)(importSync?0x15:rawSync?0x11:historyBefore>0?0x14:0x10)).putShort((short)historyTransfer).putInt(historyOffset);
        if(rawSync || importSync || historyBefore>0) requestData.putInt(importSync?0:rawSync?(int)rawSessionId:historyBefore);
        byte[] request=requestData.array();
        if (!writeCommand(request)) historyFailed("Historie konnte nicht angefordert werden");
    }

    private void readHistoryPage() {
        if (!historyLoading || gatt == null || historyCharacteristic == null || !(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) return;
        armHistoryTimeout();
        if (!gatt.readCharacteristic(historyCharacteristic)) historyFailed("Bluetooth-Lesen konnte nicht gestartet werden");
    }

    private void receiveHistory(byte[] bytes, int result) {
        if (!historyLoading) return;
        handler.removeCallbacks(historyTimeout);
        if (result != BluetoothGatt.GATT_SUCCESS || bytes == null || bytes.length < 10) {
            historyFailed("Historie konnte nicht gelesen werden: " + result); return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int transfer = Short.toUnsignedInt(buffer.getShort());
        int offset = buffer.getInt(), total = buffer.getInt();
        if (transfer != historyTransfer || offset != historyOffset) {
            if (++historyRetries > 100) { historyFailed("ESP antwortet noch nicht. Bitte erneut versuchen."); return; }
            handler.postDelayed(this::readHistoryPage, 200); return;
        }
        if(rawSync && total==0) { rawIndex++; nextRawFile(); return; }
        if (total <= 0 || total > ((rawSync||importSync)?2_000_000:160_000) || (historyOffset > 0 && total != historyTotal)
                || bytes.length == 10 || offset + bytes.length - 10 > total) {
            historyFailed("Unvollständige Historien-Daten"); return;
        }
        historyRetries = 0; historyTotal = total;
        historyBytes.write(bytes, 10, bytes.length - 10);
        historyOffset += bytes.length - 10;
        historyStatus.setText((importSync?"MyBodytone-Import":rawSync?"Messverlauf "+(rawIndex+1)+"/"+rawQueue.length():"Historie laden")+" · " + (historyOffset*100/total) + " %");
        if (historyOffset < total) { requestHistoryPage(); return; }
        if(importSync) { storeTransferredImport(); return; }
        if(rawSync) { storeRawAndAcknowledge(); return; }
        try {
            String json = new String(historyBytes.toByteArray(), StandardCharsets.UTF_8);
            JSONObject parsed = new JSONObject(json);
            if (parsed.getInt("version") != 1 && parsed.getInt("version") != 2) throw new JSONException("Version");
            activeArchive=parsed.optString("archiveId",historyCacheKey);
            archiveDb.savePage(historyCacheKey,parsed);
            if(historyBefore==0) {
                pendingImportBytes=parsed.optInt("importBytes",0);pendingImportChecksum=parsed.optLong("importChecksum",0);
                importInfo.setText(pendingImportBytes>0?String.format(Locale.GERMANY,"Importdatei auf der Bridge: %.1f KiB · Trainings erscheinen im gemeinsamen Verlauf",pendingImportBytes/1024.0):"Keine zusätzliche Importdatei auf der Bridge");
            }
            if(historyBefore==0) rawQueue=parsed.optJSONArray("rawFiles")==null?new JSONArray():parsed.getJSONArray("rawFiles");
            if(parsed.has("storage")) updateStorage(parsed.getJSONObject("storage"));
            if(parsed.optBoolean("hasMore")) {
                int next=parsed.getInt("nextBefore");
                if(next<=0 || (historyBefore>0 && next>=historyBefore)) throw new JSONException("Seitenreihenfolge");
                historyBefore=next; resetTransfer(); requestHistoryPage(); return;
            }
            JSONObject complete=archiveDb.load(historyCacheKey);
            showHistory(complete);
            getSharedPreferences("display", MODE_PRIVATE).edit().putString(historyCacheKey, json)
                    .putLong(historyCacheKey + "_time", System.currentTimeMillis()).apply();
            historyStatus.setText(parsed.optString("error").isEmpty()
                    ? "Aktualisiert · " + cacheDate() : "ESP: " + parsed.optString("error"));
            if(!parsed.optString("error").isEmpty()) {historyFailed("ESP: "+parsed.optString("error")); return;}
            rawIndex=0; nextRawFile();
        } catch (Exception e) { historyFailed("Historie konnte nicht archiviert werden: "+e.getMessage()); }
    }

    private void resetTransfer() {
        historyOffset=0; historyTotal=0; historyRetries=0; historyBytes.reset();
        historyTransfer=(historyTransfer+1)&0xffff; if(historyTransfer==0) historyTransfer=1;
    }

    private void nextRawFile() {
        if(!connected || gatt==null) {historyFailed("Offline · gespeicherte Daten bleiben erhalten");return;}
        if(rawIndex>=rawQueue.length()) {
            if(pendingImportBytes>0 && !archiveDb.hasImport(pendingImportChecksum)) {
                importSync=true;rawSync=false;historyLoading=true;resetTransfer();requestHistoryPage();return;
            }
            lastSuccessfulSyncMs=android.os.SystemClock.elapsedRealtime();
            rawSync=false; historyLoading=false; historyRefresh.setEnabled(true);updateSettingsAvailability();
            historyStatus.setText("Archiv synchronisiert · "+cacheDate());
            try {JSONObject latest=archiveDb.load(historyCacheKey); if(latest!=null) showHistory(latest);}catch(Exception ignored) { }
            readManagement(); return;
        }
        JSONObject item=rawQueue.optJSONObject(rawIndex);
        if(item==null) {historyFailed("Ungültige Messdateiliste");return;}
        rawSessionId=item.optLong("id",0);
        if(rawSessionId<=0) {historyFailed("Ungültige Sitzungskennung");return;}
        rawSync=true; historyLoading=true; resetTransfer(); requestHistoryPage();
    }

    private void storeTransferredImport() {
        byte[] bytes=historyBytes.toByteArray();
        if(bytes.length!=pendingImportBytes || TrainingDatabase.checksum(bytes)!=pendingImportChecksum) {historyFailed("Import-Prüfsumme stimmt nicht; bitte erneut synchronisieren");return;}
        final BluetoothGatt source=gatt;
        archiveIo.execute(()->{
            try {
                archiveDb.importBodytone(bytes);
                handler.post(()->{if(source!=gatt || !historyLoading)return;importSync=false;pendingImportBytes=0;nextRawFile();renderHistory(0);});
            }catch(Exception e){handler.post(()->historyFailed("Import fehlgeschlagen: "+e.getMessage()));}
        });
    }
    private void storeRawAndAcknowledge() {
        byte[] bytes=historyBytes.toByteArray();
        if(bytes.length<16 || (bytes.length-16)%24!=0) {historyFailed("Messdatei ist unvollständig; bleibt auf dem ESP");return;}
        ByteBuffer h=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if(h.getInt()!=0x31574152 || Integer.toUnsignedLong(h.getInt())!=rawSessionId
                || Short.toUnsignedInt(h.getShort(12))!=24) {historyFailed("Unbekanntes Messformat; bleibt auf dem ESP");return;}
        final BluetoothGatt source=gatt; final long id=rawSessionId;
        final String archive=activeArchive; final long checksum=TrainingDatabase.checksum(bytes);
        historyStatus.setText("Messverlauf wird dauerhaft gespeichert …");
        archiveIo.execute(()->{
            try {
                archiveDb.saveRaw(archive,id,bytes,checksum);
                handler.post(()->{
                    if(gatt!=source || !connected || !historyLoading) return;
                    awaitingAck=true; ackRetries=0;
                    byte[] ack=ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
                            .put((byte)0x12).putInt((int)id).putInt(bytes.length).putInt((int)checksum).array();
                    if(!writeCommand(ack)) historyFailed("Verlauf gespeichert; Freigabe auf ESP wird beim nächsten Sync wiederholt");
                });
            } catch(Exception e) {handler.post(()->historyFailed("Speichern fehlgeschlagen; ESP behält Verlauf: "+e.getMessage()));}
        });
    }

    private final Runnable managementTimeout=()->{
        managementReading=false;settingsError="Gerätestatus konnte nicht geladen werden. Bitte Status aktualisieren.";updateSettingsAvailability();
        if(awaitingAck)historyFailed("Verlauf gespeichert; Bestätigung fehlt");
        if(initialStatusPending){initialStatusPending=false;beginArchiveSync();}
    };
    private void readManagement() {
        if(gatt==null || managementCharacteristic==null || controlBusy || managementReading || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return;
        settingsError="";managementReading=true;handler.removeCallbacks(managementTimeout);handler.postDelayed(managementTimeout,8000);
        if(!gatt.readCharacteristic(managementCharacteristic)){managementReading=false;handler.removeCallbacks(managementTimeout);settingsState.setText("Bluetooth ist beschäftigt. Status erneut laden.");if(awaitingAck)historyFailed("Verlauf gespeichert; Bestätigung konnte nicht gelesen werden");}
        updateSettingsAvailability();
    }

    private void receiveManagement(byte[] bytes,int result) {
        managementReading=false;handler.removeCallbacks(managementTimeout);
        if(awaitingAck) handler.removeCallbacks(historyTimeout);
        try {
            if(result!=BluetoothGatt.GATT_SUCCESS) throw new JSONException("Bluetooth "+result);
            JSONObject info=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            updateStorage(info);
            if(awaitingAck) {
                if(info.optLong("lastAck")!=rawSessionId) {
                    if(++ackRetries>100) {historyFailed("Verlauf gesichert; ESP-Freigabe nicht bestätigt");return;}
                    handler.postDelayed(this::readManagement,200);return;
                }
                if(!info.optBoolean("lastAckOk")) {historyFailed("Verlauf gesichert; ESP hat Löschung nicht bestätigt");return;}
                awaitingAck=false; rawIndex++; nextRawFile();
            }
        } catch(Exception e) {settingsError="Gerätestatus nicht lesbar. Verbindung erneut prüfen.";if(awaitingAck)historyFailed("ESP-Status nicht lesbar; lokale Sicherung bleibt erhalten");}
        updateSettingsAvailability();
        if(initialStatusPending){initialStatusPending=false;beginArchiveSync();}
    }

    private void updateStorage(JSONObject info) {
        settingsError="";espStorage=info;
        if(brightnessControl!=null && !settingsDirty){settingUi=true;brightnessControl.setProgress(info.optInt("ledBrightness",20));patternControl.setSelection(info.optInt("ledPattern",0));idleControl.setSelection(Math.max(0,info.optInt("idleMinutes",5)-1));recordingControl.setChecked(info.optBoolean("recordSeconds",true));settingUi=false;}
        wifiButton.setText(info.optBoolean("wifi")?"Konfigurations-WLAN ausschalten":"Konfigurations-WLAN einschalten");
        updateSettingsAvailability();
        if(storageInfo==null) return;
        double total=info.optDouble("total"),used=info.optDouble("used"),free=info.optDouble("free");
        double hours=Math.max(0,free-info.optDouble("reserve"))/(24*3600);
        storageInfo.setText(String.format(Locale.GERMANY,
                "%.0f von %.0f KiB belegt · %.0f KiB frei\n%d Trainings · %d Messverläufe auf der Bridge\nNoch etwa %.1f Stunden Sekundenwerte\n%d ältere Verläufe automatisch freigegeben%s",
                used/1024,total/1024,free/1024,info.optInt("summaries"),info.optInt("rawSessions"),hours,
                info.optInt("evictedRaw"),info.optBoolean("rawPaused")?"\nSekundenaufzeichnung pausiert: Speicher voll":""));
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==82 && result==RESULT_OK && data!=null && data.getData()!=null) {
            android.net.Uri importUri=data.getData();
            archiveIo.execute(()->{
                try(java.io.InputStream input=getContentResolver().openInputStream(importUri)) {
                    if(input==null)throw new java.io.IOException("Datei nicht geöffnet");
                    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;
                    while((n=input.read(block))!=-1){out.write(block,0,n);if(out.size()>2_000_000)throw new java.io.IOException("Importdatei zu groß (max. 2 MB)");}
                    int added=archiveDb.importBodytone(out.toByteArray());
                    handler.post(()->{selectTab(1);renderHistory(0);AppDialog.notice(this,added+" Trainings neu importiert");});
                }catch(Exception e){handler.post(()->AppDialog.notice(this,"Import fehlgeschlagen: "+e.getMessage()));}
            });return;
        }
        if(request!=81 || result!=RESULT_OK || data==null || data.getData()==null) return;
        android.net.Uri uri=data.getData();
        archiveIo.execute(()->{
            try(java.io.OutputStream output=getContentResolver().openOutputStream(uri)) {
                if(output==null) throw new java.io.IOException("Datei nicht geöffnet");
                archiveDb.exportZip(output);
                handler.post(()->AppDialog.notice(this,"Archiv einschließlich Messverläufen gesichert"));
            } catch(Exception e) {handler.post(()->AppDialog.notice(this,"Sicherung fehlgeschlagen: "+e.getMessage()));}
        });
    }

    private void showHistory(JSONObject data) throws JSONException {
        JSONArray devices = data.getJSONArray("devices");
        int selected = Math.max(0, historyDevice.getSelectedItemPosition());
        historyJson = data;
        ArrayList<String> names = new ArrayList<>();
        for (int i=0;i<devices.length();i++) {
            JSONObject device = devices.getJSONObject(i);
            names.add(device.getString("name") + " · " + device.getString("address"));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        historyDevice.setAdapter(adapter);
        selected = Math.min(selected, Math.max(0, names.size()-1));
        historyDevice.setSelection(selected); renderHistory(selected);
    }

    private static String metric(JSONObject stats, String key, String unit) {
        return stats.isNull(key) || !stats.has(key) ? "—" : String.format(Locale.GERMANY, "%.1f %s", stats.optDouble(key), unit);
    }

    private String statsText(JSONObject stats) {
        return "Dauer " + formatDuration((long)stats.optDouble("seconds"))
                + " · aktiv " + formatDuration((long)stats.optDouble("active"))
                + "\n" + metric(stats,"km","km") + " · " + metric(stats,"kcal","kcal")
                + (stats.optBoolean("estimated") ? " (teilweise geschätzt)" : "")
                + "\nLeistung Ø " + metric(stats,"power","W") + " · max " + metric(stats,"maxPower","W")
                + "\nKadenz Ø " + metric(stats,"cadence","rpm") + " · max " + metric(stats,"maxCadence","rpm")
                + "\nTempo Ø " + metric(stats,"speed","km/h") + " · max " + metric(stats,"maxSpeed","km/h")
                + "\nArbeit " + metric(stats,"workKj","kJ") + " · " + metric(stats,"revolutions","Kurbelumdrehungen");
    }

    private void historyCard(String title, JSONObject stats) {
        TextView card = historyText(title + "\n\n" + statsText(stats), 15);
        card.setBackgroundResource(R.drawable.metric_background);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2);
        lp.topMargin = (int)(10*getResources().getDisplayMetrics().density);
        historyCards.addView(card,lp);
    }

    private void renderHistory(int deviceIndex) {
        historyCards.removeAllViews();
        try {
            JSONArray imports=archiveDb.importedSessions();
            JSONArray nativeRows=historyJson==null?new JSONArray():historyJson.optJSONArray("sessions");if(nativeRows==null)nativeRows=new JSONArray();
            double km=0,kcal=0;long seconds=0,count=imports.length();
            JSONArray devices=historyJson==null?null:historyJson.optJSONArray("devices");
            if(devices!=null)for(int i=0;i<devices.length();i++){JSONObject d=devices.getJSONObject(i),t=d.getJSONObject("stats");km+=t.optDouble("km",0);kcal+=t.optDouble("kcal",0);seconds+=(long)t.optDouble("seconds",0);count+=d.optLong("sessions",0);}
            java.util.ArrayList<JSONObject> all=new java.util.ArrayList<>();
            for(int i=0;i<nativeRows.length();i++){JSONObject row=nativeRows.getJSONObject(i);long epoch=row.optLong("started");String order=epoch==0?"":java.time.Instant.ofEpochSecond(epoch).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString();all.add(new JSONObject().put("row",row).put("imported",false).put("order",order).put("deviceKey",historyJson.optString("archiveId",historyCacheKey)+":"+row.optInt("device",-1)));}
            for(int i=0;i<imports.length();i++){JSONObject row=imports.getJSONObject(i);km+=row.optDouble("distance_km",0);kcal+=row.optDouble("calories_kcal",0);seconds+=row.optLong("duration_seconds",0);all.add(new JSONObject().put("row",row).put("imported",true).put("order",row.optString("datetime_local")));}
            all.sort((x,y)->y.optString("order").compareTo(x.optString("order")));
            TextView totals=historyText(String.format(Locale.GERMANY,"%d Trainings\n\n%,.1f km     ·     %s\n%,.0f kcal insgesamt",count,km,formatDuration(seconds),kcal),20);totals.setBackgroundResource(R.drawable.hero_background);totals.setTextColor(getColor(R.color.hero_ink));totals.setPadding(dp(20),dp(20),dp(20),dp(20));LinearLayout.LayoutParams totalLp=new LinearLayout.LayoutParams(-1,-2);totalLp.topMargin=dp(14);historyCards.addView(totals,totalLp);
            historyCards.addView(new ProgressChart(this,all),new LinearLayout.LayoutParams(-1,-2));
            historyCards.addView(historyText("Alle Trainings · antippen zum Vergleichen",18));
            JSONObject current=historyJson==null?null:historyJson.optJSONObject("current");if(current!=null)historyCard("Aktuell · Training läuft",current.getJSONObject("stats"));
            if(all.isEmpty())historyCards.addView(historyText("Dein erstes Training erscheint hier nach der Synchronisierung. Vorhandene Trainings kannst du in den Einstellungen importieren.",14));
            for(JSONObject item:all){
                JSONObject row=item.getJSONObject("row");boolean imported=item.getBoolean("imported");JSONObject stats=imported?row:row.getJSONObject("stats");
                String date=imported?row.optString("displayed_datetime",row.optString("datetime_local")):row.optLong("started")==0?"Datum unbekannt":DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(row.getLong("started")*1000));
                long duration=(long)stats.optDouble(imported?"duration_seconds":"seconds",0);
                String text=date+"\n\n"+formatDuration(duration)+"   ·   "+metric(stats,imported?"distance_km":"km","km")+"   ·   "+metric(stats,imported?"calories_kcal":"kcal","kcal")+"\nØ "+metric(stats,imported?"average_power_watts":"power","W")+"   ·   "+metric(stats,imported?"average_cadence_rpm":"cadence","rpm");
                int intensityBand=TrainingInsights.band(item,all);double rate=TrainingInsights.intensity(item);
                String intensityLabel=TrainingInsights.label(intensityBand)+(Double.isFinite(rate)?String.format(Locale.GERMANY," · %.1f kcal/min",rate):"");
                TextView card=historyText(text+"\n"+intensityLabel,14);
                android.text.SpannableString colored=new android.text.SpannableString(card.getText());colored.setSpan(new android.text.style.ForegroundColorSpan(getColor(TrainingInsights.color(intensityBand))),text.length()+1,colored.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);card.setText(colored);
                android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();background.setColor(getColor(R.color.surface));background.setCornerRadius(dp(24));background.setStroke(dp(1),getColor(TrainingInsights.color(intensityBand)));card.setBackground(background);card.setLineSpacing(dp(3),1);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(10);historyCards.addView(card,lp);
                card.setOnClickListener(v->{
                    AppDialog dialog=TrainingDialogs.details(this,item,all,date);
                    dialog.action("Schließen",false,dialog::dismiss);
                    dialog.action("Vergleichen",true,()->{dialog.dismiss();showSimilarTrainings(item,all);});
                    showDialog(dialog);
                });
            }
        }catch(Exception e){historyStatus.setText("Verlauf konnte nicht geladen werden: "+e.getMessage());}
    }

    private void showDialog(AppDialog dialog){if(activeDialog!=null && activeDialog.isShowing())activeDialog.dismiss();activeDialog=dialog;dialog.show();}
    private void showSimilarTrainings(JSONObject reference,java.util.List<JSONObject> all){
        AppDialog dialog=TrainingDialogs.comparison(this,reference,all);dialog.action("Schließen",true,dialog::dismiss);showDialog(dialog);
    }
    private void renderHeartRateDevices(){
        if(heartRateDevicesList==null)return;heartRateDevicesList.removeAllViews();
        if(heartRateDevices.isEmpty()){TextView empty=AppDialog.text(this,"Gefundene Pulssensoren erscheinen hier. Aktiviere auf der Fenix zuerst Herzfrequenz senden.",15,R.color.muted);empty.setPadding(dp(4),dp(16),dp(4),dp(16));heartRateDevicesList.addView(empty);return;}
        for(HeartRateClient.Device device:heartRateDevices){
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(16),dp(14),dp(16),dp(14));row.setBackground(AppDialog.interactive(this,R.color.surface_alt,18,true));TextView name=AppDialog.text(this,device.name+"  ›",18,R.color.ink);name.setTypeface(null,android.graphics.Typeface.BOLD);row.addView(name);row.addView(AppDialog.text(this,device.device.getAddress(),12,R.color.muted));row.setContentDescription(device.name+" verbinden");row.setFocusable(true);row.setClickable(true);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(10);heartRateDevicesList.addView(row,lp);row.setOnClickListener(v->{heartRateClient.select(device);if(heartRatePicker!=null)heartRatePicker.dismiss();});
        }
    }
    private void chooseHeartRateSensor(){
        if(!hasBluetoothPermissions()){requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},BLUETOOTH_PERMISSION_REQUEST);return;}
        if(heartRatePicker!=null)heartRatePicker.dismiss();heartRateDevices=new java.util.ArrayList<>();
        heartRatePicker=new AppDialog(this,"LIVE-PULS","Pulssensor verbinden","Auf der Fenix Herzfrequenz senden aktivieren, dann deine Uhr auswählen.",R.drawable.status_watch);
        LinearLayout statusRow=heartRatePicker.group("");statusRow.setOrientation(LinearLayout.HORIZONTAL);statusRow.setGravity(android.view.Gravity.CENTER_VERTICAL);heartRateSearchProgress=new android.widget.ProgressBar(this);heartRateSearchProgress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));statusRow.addView(heartRateSearchProgress,new LinearLayout.LayoutParams(dp(24),dp(24)));heartRateSearchStatus=AppDialog.text(this,"Suche läuft …",15,R.color.ink);heartRateSearchStatus.setPadding(dp(12),0,0,0);statusRow.addView(heartRateSearchStatus,new LinearLayout.LayoutParams(0,-2,1));
        heartRateDevicesList=new LinearLayout(this);heartRateDevicesList.setOrientation(LinearLayout.VERTICAL);heartRatePicker.body.addView(heartRateDevicesList);renderHeartRateDevices();
        heartRatePicker.action("Schließen",false,()->{if(heartRatePicker!=null)heartRatePicker.dismiss();});heartRatePicker.action("Erneut suchen",true,()->heartRateClient.search());
        heartRatePicker.setOnDismissListener(dialog->{heartRateClient.stopSearch();heartRateDevicesList=null;heartRateSearchStatus=null;heartRateSearchProgress=null;heartRatePicker=null;});showDialog(heartRatePicker);heartRateClient.search();
    }
    @Override protected void onResume(){super.onResume();if(heartRateClient!=null)heartRateClient.restore();}

    private void disconnectManually() {
        manualDisconnect = true;
        handler.removeCallbacks(reconnect);
        stopScan();
        if (gatt != null && (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) gatt.disconnect();
        else {
            connected = false;
            connectButton.setText("Bridge verbinden");
            showState("●  Getrennt", false);
        }
    }

    private void showState(String message, boolean good) {
        status.setText(message);
        status.setVisibility(good?View.GONE:View.VISIBLE);updateConnectionIcons();
        status.setTextColor(getColor(good ? R.color.good : R.color.muted));
    }

    private void applySavedTheme() {
        int mode = getSharedPreferences("display", MODE_PRIVATE).getInt("theme", 0);
        UiModeManager manager = getSystemService(UiModeManager.class);
        if (manager != null) manager.setApplicationNightMode(mode == 1
                ? UiModeManager.MODE_NIGHT_NO : mode == 2
                ? UiModeManager.MODE_NIGHT_YES : UiModeManager.MODE_NIGHT_AUTO);
    }

    private void chooseTheme() {
        int current=getSharedPreferences("display",MODE_PRIVATE).getInt("theme",0);
        AppDialog dialog=new AppDialog(this,"DARSTELLUNG","Dein Farbschema","Wähle einen Modus oder folge deinem Smartphone.",R.drawable.nav_settings);
        String[] names={"Wie das System","Hell","Dunkel"},descriptions={"Wechselt mit den Systemeinstellungen","Helle Flächen und klare Kontraste","Angenehm bei wenig Licht"};
        for(int i=0;i<3;i++){final int choice=i;LinearLayout row=dialog.group("");row.setBackground(AppDialog.interactive(this,current==i?R.color.surface_alt:R.color.surface,18,true));TextView title=AppDialog.text(this,(current==i?"✓  ":"")+names[i],18,current==i?R.color.accent:R.color.ink);title.setTypeface(null,android.graphics.Typeface.BOLD);row.addView(title);row.addView(AppDialog.text(this,descriptions[i],13,R.color.muted));row.setFocusable(true);row.setContentDescription(names[i]+(current==i?", ausgewählt":""));row.setOnClickListener(v->{getSharedPreferences("display",MODE_PRIVATE).edit().putInt("theme",choice).apply();dialog.dismiss();getSystemService(UiModeManager.class).setApplicationNightMode(choice==1?UiModeManager.MODE_NIGHT_NO:choice==2?UiModeManager.MODE_NIGHT_YES:UiModeManager.MODE_NIGHT_AUTO);updateThemeLabel();});}
        dialog.action("Schließen",false,dialog::dismiss);showDialog(dialog);
    }
    private void updateThemeLabel(){int mode=getSharedPreferences("display",MODE_PRIVATE).getInt("theme",0);themeButton.setText("Farbschema · "+(mode==1?"Hell":mode==2?"Dunkel":"System"));}

    @Override protected void onDestroy() {
        manualDisconnect = true;
        if(activeDialog!=null)activeDialog.dismiss();
        if(heartRateClient!=null)heartRateClient.close();
        handler.removeCallbacksAndMessages(null);
        stopScan();
        if (gatt != null) {
            if ((checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) gatt.disconnect();
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) gatt.close();
            gatt = null;
        }
        super.onDestroy();
    }
}
