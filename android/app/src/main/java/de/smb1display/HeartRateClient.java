package de.smb1display;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.*;
import java.util.*;

/** Independent HR connection. Never touches the bridge's GATT, scanner callback or commands. */
final class HeartRateClient {
    static final UUID SERVICE=UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"),MEASUREMENT=UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb"),CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    interface Listener {void state(int bpm,String message);void devices(List<Device> devices);}
    static final class Device {final BluetoothDevice device;final String name;Device(BluetoothDevice d,String n){device=d;name=n;}@Override public String toString(){return name+"\n"+device.getAddress();}}
    private final Context context;private final Listener listener;private final Handler handler=new Handler(Looper.getMainLooper());
    private BluetoothGatt gatt;private BluetoothLeScanner scanner;private boolean scanning,enabled,closed,subscribed;
    private final ArrayList<Device> devices=new ArrayList<>();private long received=-1;private int bpm=-1;private String name="Pulssensor",message="Noch kein Pulssensor ausgewählt";
    HeartRateClient(Context c,Listener l){context=c.getApplicationContext();listener=l;}
    private boolean permitted(){return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    private BluetoothAdapter adapter(){BluetoothManager m=context.getSystemService(BluetoothManager.class);return m==null?null:m.getAdapter();}
    static int decode(byte[] bytes){
        if(bytes==null || bytes.length<2)return -1;int flags=bytes[0]&255,index=(flags&1)==0?2:3;if(bytes.length<index)return -1;
        if((flags&4)!=0 && (flags&2)==0)return -1;
        if((flags&8)!=0)index+=2;
        if(bytes.length<index || ((flags&16)!=0 && (bytes.length-index<2 || (bytes.length-index)%2!=0)))return -1;
        int value=(bytes[1]&255)+((flags&1)==0?0:(bytes[2]&255)<<8);return value>0 && value<=300?value:-1;
    }
    private void emit(String status){message=status;listener.state(bpm,status);}
    boolean isSearching(){return scanning;}
    void refresh(){listener.state(bpm,message);}
    void restore(){
        if(closed || gatt!=null || !permitted())return;
        var p=context.getSharedPreferences("display",0);enabled=p.getBoolean("hrEnabled",false);String address=p.getString("hrAddress","");name=p.getString("hrName","Pulssensor");
        if(enabled && BluetoothAdapter.checkBluetoothAddress(address)){BluetoothAdapter a=adapter();if(a!=null && a.isEnabled())connect(a.getRemoteDevice(address),name);else emit("Bluetooth für den Pulssensor einschalten");}
    }
    void reconnectSaved(){if(context.getSharedPreferences("display",0).getString("hrAddress","").isEmpty()){emit("Bitte zuerst einen Pulssensor auswählen");return;}context.getSharedPreferences("display",0).edit().putBoolean("hrEnabled",true).apply();release();restore();}
    void search(){
        if(closed)return;if(!permitted()){emit("Bluetooth-Berechtigung fehlt");return;}BluetoothAdapter a=adapter();if(a==null || !a.isEnabled()){emit("Bluetooth einschalten");return;}
        stopSearch();scanner=a.getBluetoothLeScanner();if(scanner==null){emit("Bluetooth-Suche nicht verfügbar");return;}
        devices.clear();listener.devices(new ArrayList<>(devices));scanning=true;
        try{scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build()),new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);emit("Suche · auf der Fenix ‘Herzfrequenz senden’ starten");handler.postDelayed(scanEnd,15000);}catch(SecurityException | IllegalStateException e){scanning=false;emit("Pulssensor-Suche konnte nicht starten");}
    }
    private final Runnable scanEnd=()->{stopSearch();if(gatt==null)emit(devices.isEmpty()?"Kein Pulssensor gefunden · Herzfrequenz senden aktivieren":"Pulssensor in der Liste auswählen");};
    void stopSearch(){handler.removeCallbacks(scanEnd);if(scanning && scanner!=null && permitted())try{scanner.stopScan(scanCallback);}catch(IllegalStateException | SecurityException ignored){}scanning=false;}
    private final ScanCallback scanCallback=new ScanCallback(){
        @Override public void onScanResult(int type,ScanResult result){handler.post(()->{if(!scanning || closed || !permitted())return;String address=result.getDevice().getAddress();for(Device d:devices)if(d.device.getAddress().equals(address))return;String label=result.getScanRecord()==null?null:result.getScanRecord().getDeviceName();if(label==null)try{label=result.getDevice().getName();}catch(SecurityException ignored){}devices.add(new Device(result.getDevice(),label==null?"Pulssensor":label));listener.devices(new ArrayList<>(devices));});}
        @Override public void onScanFailed(int error){handler.post(()->{if(!scanning || closed)return;scanning=false;handler.removeCallbacks(scanEnd);emit("Pulssensor-Suche fehlgeschlagen ("+error+")");});}
    };
    void select(Device device){enabled=true;context.getSharedPreferences("display",0).edit().putBoolean("hrEnabled",true).putString("hrAddress",device.device.getAddress()).putString("hrName",device.name).apply();connect(device.device,device.name);}
    private void connect(BluetoothDevice device,String label){
        if(closed || !permitted())return;stopSearch();release();name=label;bpm=-1;emit(name+" · wird verbunden …");
        try{gatt=device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);handler.postDelayed(timeout,20000);}catch(SecurityException | IllegalArgumentException e){failed("Pulssensor konnte nicht verbunden werden");}
    }
    private final Runnable timeout=()->failed("Pulssensor antwortet nicht · Herzfrequenz senden auf der Fenix aktivieren");
    private final Runnable firstValueTimeout=()->failed("Verbunden, aber kein Puls · Herzfrequenz senden auf der Fenix prüfen");
    private final Runnable reconnect=()->{if(enabled && !closed && gatt==null)restore();};
    private final Runnable freshness=new Runnable(){public void run(){if(closed || gatt==null)return;if(received>=0 && SystemClock.elapsedRealtime()-received>10000 && bpm!=-1){bpm=-1;emit(name+" · keine aktuellen Pulswerte");}handler.postDelayed(this,1000);}};
    private void failed(String reason){release();bpm=-1;if(closed)return;emit(reason);if(enabled)handler.postDelayed(reconnect,30000);}
    private void release(){handler.removeCallbacks(timeout);handler.removeCallbacks(firstValueTimeout);handler.removeCallbacks(freshness);handler.removeCallbacks(reconnect);BluetoothGatt previous=gatt;gatt=null;subscribed=false;received=-1;if(previous!=null){try{if(permitted())previous.disconnect();}catch(SecurityException ignored){}try{previous.close();}catch(SecurityException ignored){}}}
    void disconnect(){enabled=false;context.getSharedPreferences("display",0).edit().putBoolean("hrEnabled",false).apply();stopSearch();release();bpm=-1;emit("Pulssensor getrennt");}
    void close(){closed=true;stopSearch();release();handler.removeCallbacksAndMessages(null);}
    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt source,int status,int state){handler.post(()->{if(closed || source!=gatt)return;if(status!=BluetoothGatt.GATT_SUCCESS || state==BluetoothProfile.STATE_DISCONNECTED){failed("Pulssensor getrennt · GATT "+status+" · erneuter Versuch folgt");return;}if(state==BluetoothProfile.STATE_CONNECTED)try{if(!source.discoverServices())failed("Pulsdienst konnte nicht geladen werden");}catch(SecurityException e){failed("Bluetooth-Berechtigung fehlt");}});}
        @Override public void onServicesDiscovered(BluetoothGatt source,int status){handler.post(()->{
            if(closed || source!=gatt)return;BluetoothGattService service=source.getService(SERVICE);BluetoothGattCharacteristic measurement=service==null?null:service.getCharacteristic(MEASUREMENT);BluetoothGattDescriptor descriptor=measurement==null?null:measurement.getDescriptor(CCCD);
            if(status!=BluetoothGatt.GATT_SUCCESS || descriptor==null){failed("Gerät bietet keinen Bluetooth-Pulsdienst");return;}
            try{if(!source.setCharacteristicNotification(measurement,true)){failed("Pulswerte konnten nicht abonniert werden");return;}
                boolean accepted;if(Build.VERSION.SDK_INT>=33)accepted=source.writeDescriptor(descriptor,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)==BluetoothStatusCodes.SUCCESS;else{descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);accepted=source.writeDescriptor(descriptor);}if(!accepted)failed("Pulssensor ist beschäftigt");
            }catch(SecurityException e){failed("Bluetooth-Berechtigung fehlt");}
        });}
        @Override public void onDescriptorWrite(BluetoothGatt source,BluetoothGattDescriptor descriptor,int status){handler.post(()->{if(closed || source!=gatt || !CCCD.equals(descriptor.getUuid()))return;if(status!=BluetoothGatt.GATT_SUCCESS){failed("Pulsabo fehlgeschlagen ("+status+")");return;}subscribed=true;handler.removeCallbacks(timeout);if(bpm<=0)emit(name+" · verbunden, warte auf Puls");if(received<0)handler.postDelayed(firstValueTimeout,15000);handler.removeCallbacks(freshness);handler.post(freshness);});}
        private void measurement(BluetoothGatt source,byte[] bytes){final byte[] copy=bytes==null?null:bytes.clone();handler.post(()->{if(closed || source!=gatt)return;bpm=decode(copy);if(bpm>0)handler.removeCallbacks(firstValueTimeout);received=SystemClock.elapsedRealtime();emit(bpm>0?name+" · Live-Puls verbunden":name+" · kein gültiger Puls / Hautkontakt prüfen");});}
        @Override public void onCharacteristicChanged(BluetoothGatt source,BluetoothGattCharacteristic c,byte[] value){if(MEASUREMENT.equals(c.getUuid()))measurement(source,value);}
        @Override public void onCharacteristicChanged(BluetoothGatt source,BluetoothGattCharacteristic c){if(Build.VERSION.SDK_INT<33 && MEASUREMENT.equals(c.getUuid()))measurement(source,c.getValue());}
    };
}
