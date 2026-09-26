package de.smb1display;

import android.bluetooth.*;
import android.os.*;
import android.widget.TextView;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,qualifiers="w412dp-h892dp-port-xxhdpi")
public class HeartRateInsightsTest {
    @Before public void reset(){RuntimeEnvironment.getApplication().getSharedPreferences("display",0).edit().clear().commit();RuntimeEnvironment.getApplication().getSharedPreferences("display",0).edit().putString("language","de").commit();I18n.init(RuntimeEnvironment.getApplication());}
    @Test public void heartRateFormatsContactAndTruncatedPackets(){
        assertEquals(142,HeartRateClient.decode(new byte[]{0,(byte)142}));
        assertEquals(260,HeartRateClient.decode(new byte[]{1,4,1}));
        assertEquals(142,HeartRateClient.decode(new byte[]{6,(byte)142}));
        assertEquals(-1,HeartRateClient.decode(new byte[]{4,(byte)142}));
        assertEquals(-1,HeartRateClient.decode(new byte[]{1,4}));
        assertEquals(-1,HeartRateClient.decode(new byte[]{8,80}));
        assertEquals(-1,HeartRateClient.decode(new byte[]{16,80,1}));
        assertEquals(80,HeartRateClient.decode(new byte[]{16,80,0,4}));
        assertEquals(-1,HeartRateClient.decode(new byte[]{0,0}));
    }
    @Test public void firstPulseBeforeSubscriptionAckIsDisplayed() {
        var context=RuntimeEnvironment.getApplication();final int[] last={-1};
        HeartRateClient client=new HeartRateClient(context,new HeartRateClient.Listener(){public void state(int bpm,String message){last[0]=bpm;}public void devices(List<HeartRateClient.Device> d){}});
        BluetoothGatt g=BluetoothAdapter.getDefaultAdapter().getRemoteDevice("02:00:00:00:00:01").connectGatt(context,false,new BluetoothGattCallback(){});
        ReflectionHelpers.setField(client,"gatt",g);BluetoothGattCallback callback=ReflectionHelpers.getField(client,"callback");
        callback.onCharacteristicChanged(g,new BluetoothGattCharacteristic(HeartRateClient.MEASUREMENT,16,1),new byte[]{0,(byte)142});Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(142,last[0]);
        callback.onDescriptorWrite(g,new BluetoothGattDescriptor(HeartRateClient.CCCD,1),BluetoothGatt.GATT_SUCCESS);Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(142,last[0]);client.close();
    }
    @Test public void subscriptionWithoutMeasurementsShowsBroadcastHint() {
        var context=RuntimeEnvironment.getApplication();final String[] status={""};
        HeartRateClient client=new HeartRateClient(context,new HeartRateClient.Listener(){public void state(int bpm,String message){status[0]=message;}public void devices(List<HeartRateClient.Device> d){}});
        BluetoothGatt g=BluetoothAdapter.getDefaultAdapter().getRemoteDevice("02:00:00:00:00:01").connectGatt(context,false,new BluetoothGattCallback(){});
        ReflectionHelpers.setField(client,"gatt",g);BluetoothGattCallback callback=ReflectionHelpers.getField(client,"callback");
        callback.onDescriptorWrite(g,new BluetoothGattDescriptor(HeartRateClient.CCCD,1),BluetoothGatt.GATT_SUCCESS);Shadows.shadowOf(Looper.getMainLooper()).idle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(16));
        assertTrue(status[0].contains("Herzfrequenz senden"));assertNull(ReflectionHelpers.getField(client,"gatt"));client.close();
    }
    @Test public void stalePulseClearsAndOldGattCannotUpdateIt(){
        var context=RuntimeEnvironment.getApplication();final int[] last={0};
        HeartRateClient client=new HeartRateClient(context,new HeartRateClient.Listener(){public void state(int bpm,String message){last[0]=bpm;}public void devices(List<HeartRateClient.Device> d){}});
        BluetoothGatt g=BluetoothAdapter.getDefaultAdapter().getRemoteDevice("02:00:00:00:00:01").connectGatt(context,false,new BluetoothGattCallback(){});
        ReflectionHelpers.setField(client,"gatt",g);BluetoothGattCallback callback=ReflectionHelpers.getField(client,"callback");
        BluetoothGattCharacteristic measurement=new BluetoothGattCharacteristic(HeartRateClient.MEASUREMENT,16,1);
        callback.onDescriptorWrite(g,new BluetoothGattDescriptor(HeartRateClient.CCCD,1),BluetoothGatt.GATT_SUCCESS);Shadows.shadowOf(Looper.getMainLooper()).idle();
        callback.onCharacteristicChanged(g,measurement,new byte[]{0,(byte)142});Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(142,last[0]);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(12));assertEquals(-1,last[0]);
        client.disconnect();callback.onCharacteristicChanged(g,measurement,new byte[]{0,(byte)150});Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(-1,last[0]);client.close();
    }
    private JSONObject row(int minutes,int kcal,double level,double cadence,String date) throws Exception {
        return new JSONObject().put("imported",true).put("order",date).put("row",new JSONObject().put("source","mybodytone").put("duration_seconds",minutes*60).put("calories_kcal",kcal).put("average_resistance_level",level).put("average_cadence_rpm",cadence));
    }
    @Test public void intensityUsesSameSourceAndTiesStayNeutral() throws Exception {
        List<JSONObject> all=new ArrayList<>();for(int i=0;i<9;i++)all.add(row(30,100+i*50,22,75,"2026-09-01"));
        assertEquals(0,TrainingInsights.band(all.get(0),all));assertEquals(1,TrainingInsights.band(all.get(4),all));assertEquals(2,TrainingInsights.band(all.get(8),all));
        JSONObject shortRide=row(1,100,22,75,"");assertEquals(-1,TrainingInsights.band(shortRide,all));
        JSONObject otherSource=row(30,500,22,75,"");otherSource.getJSONObject("row").put("source","other");assertEquals(-1,TrainingInsights.band(otherSource,all));
        List<JSONObject> same=new ArrayList<>();for(int i=0;i<6;i++)same.add(row(30,300,22,75,""));assertEquals(1,TrainingInsights.band(same.get(0),same));
    }
    @Test public void comparisonFiltersLevelDurationAndMissingData() throws Exception {
        List<JSONObject> all=new ArrayList<>();for(int i=0;i<8;i++)all.add(row(30,300,22,70+i,"2026-09-0"+(i+1)));
        all.add(row(60,300,22,75,"2026-09-09"));all.add(row(30,300,25,75,"2026-09-10"));
        assertEquals(8,TrainingInsights.similar(all.get(0),all).size());assertTrue(TrainingInsights.comparison(all.get(0),all).contains("Veränderung"));
        JSONObject missing=row(30,300,22,75,"");missing.getJSONObject("row").remove("average_resistance_level");assertTrue(TrainingInsights.comparison(missing,all).contains("fehlt"));
    }
    @Test public void themeRotationKeepsHeartRateReceiver(){
        try(var c=Robolectric.buildActivity(MainActivity.class).setup().visible()){
            MainActivity a=c.get();HeartRateClient client=ReflectionHelpers.getField(a,"heartRateClient");ReflectionHelpers.setField(client,"bpm",142);client.refresh();
            a.onConfigurationChanged(new android.content.res.Configuration(a.getResources().getConfiguration()));assertSame(client,ReflectionHelpers.getField(a,"heartRateClient"));assertTrue(((TextView)a.findViewById(R.id.heartRate)).getText().toString().contains("142"));
        }
    }
}
