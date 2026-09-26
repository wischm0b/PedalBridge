package de.smb1display;
import android.app.*;import android.content.*;import android.content.res.Configuration;import android.bluetooth.*;import android.view.*;import android.widget.*;import android.graphics.Bitmap;import android.graphics.Canvas;
import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;import org.robolectric.android.controller.ActivityController;import org.robolectric.util.ReflectionHelpers;
import org.json.*;import java.nio.file.*;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,qualifiers="w412dp-h892dp-port-xxhdpi")
public class AppRegressionTest {
 @Before public void clean(){RuntimeEnvironment.setQualifiers("w412dp-h892dp-port-xxhdpi");Context c=RuntimeEnvironment.getApplication();c.deleteDatabase("training.db");c.getSharedPreferences("display",0).edit().clear().commit();}
 private JSONObject status() throws Exception{return new JSONObject("{\"total\":1441792,\"used\":151552,\"free\":1290240,\"reserve\":262144,\"ledBrightness\":20,\"ledPattern\":0,\"idleMinutes\":5,\"recordSeconds\":true,\"wifi\":false}");}
 private void imported() throws Exception{try(TrainingDatabase db=new TrainingDatabase(RuntimeEnvironment.getApplication())){byte[] bytes=getClass().getClassLoader().getResourceAsStream("mybodytone-synthetic.json").readAllBytes();assertEquals(77,db.importBodytone(bytes));assertEquals(0,db.importBodytone(bytes));assertEquals(77,db.importedSessions().length());}}
 @Test public void repeatedImportIsIdempotentAndKeepsDetails() throws Exception {imported();try(TrainingDatabase db=new TrainingDatabase(RuntimeEnvironment.getApplication())){assertTrue(db.importedSessions().getJSONObject(0).has("original_display"));assertTrue(db.importedSessions().getJSONObject(0).isNull("timezone"));}}
 @Test public void tabAndThemeChangesKeepGattAndLiveValues() throws Exception {
  try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible()){
   MainActivity a=c.get();BluetoothGatt g=BluetoothAdapter.getDefaultAdapter().getRemoteDevice("02:00:00:00:00:01").connectGatt(a,false,new BluetoothGattCallback(){});
   ReflectionHelpers.setField(a,"gatt",g);ReflectionHelpers.setField(a,"connected",true);((TextView)a.findViewById(R.id.power)).setText("182 W");
   a.findViewById(R.id.tabEsp).performClick();assertSame(g,ReflectionHelpers.getField(a,"gatt"));assertEquals(View.VISIBLE,a.findViewById(R.id.espPage).getVisibility());
   Configuration conf=new Configuration(a.getResources().getConfiguration());conf.uiMode=(conf.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)|Configuration.UI_MODE_NIGHT_YES;a.onConfigurationChanged(conf);
   assertSame(g,ReflectionHelpers.getField(a,"gatt"));assertTrue((Boolean)ReflectionHelpers.getField(a,"connected"));assertEquals("182 W",((TextView)a.findViewById(R.id.power)).getText().toString());assertEquals(View.VISIBLE,a.findViewById(R.id.espPage).getVisibility());
  }
 }
 @Test public void importedRidesAppearWithoutSourceSelector() throws Exception {
  imported();try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible()){
   MainActivity a=c.get();a.findViewById(R.id.tabHistory).performClick();LinearLayout cards=ReflectionHelpers.getField(a,"historyCards");assertTrue(cards.getChildCount()>=78);assertTrue(((TextView)cards.getChildAt(0)).getText().toString().contains("77 Trainings"));
   assertFalse(a.findViewById(R.id.tabHistory) instanceof Button);assertFalse(a.findViewById(R.id.livePage) instanceof ScrollView);
  }
 }
 @Test public void nativeAndImportedRidesShareTimelineAndTotals() throws Exception {
  imported();try(TrainingDatabase db=new TrainingDatabase(RuntimeEnvironment.getApplication())){
   JSONObject stats=new JSONObject().put("seconds",600).put("km",5).put("kcal",100).put("power",180).put("cadence",80);
   JSONObject ride=new JSONObject().put("id",1).put("device",0).put("started",java.time.Instant.parse("2026-09-15T07:00:00Z").getEpochSecond()).put("stats",stats);
   JSONObject device=new JSONObject().put("name","Bike").put("address","02:00:00:00:00:02").put("sessions",1).put("stats",stats);
   db.savePage("history",new JSONObject().put("archiveId","test").put("devices",new JSONArray().put(device)).put("sessions",new JSONArray().put(ride)));
  }
  try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible()){
   LinearLayout cards=ReflectionHelpers.getField(c.get(),"historyCards");String total=((TextView)cards.getChildAt(0)).getText().toString();assertTrue(total.contains("78 Trainings"));assertTrue(total.contains("775,0 km"));assertTrue(((TextView)cards.getChildAt(3)).getText().toString().contains("5,0 km"));assertEquals(81,cards.getChildCount());
  }
 }
 @Test public void settingsLoadIndependentlyOfHistory() throws Exception {
  try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible()){
   MainActivity a=c.get();ReflectionHelpers.setField(a,"connected",true);ReflectionHelpers.setField(a,"managementCharacteristic",new BluetoothGattCharacteristic(java.util.UUID.randomUUID(),BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ));
   ReflectionHelpers.callInstanceMethod(a,"updateStorage",ReflectionHelpers.ClassParameter.from(JSONObject.class,status()));
   assertTrue(((Button)ReflectionHelpers.getField(a,"saveSettings")).isEnabled());assertNull(ReflectionHelpers.getField(a,"historyCharacteristic"));assertEquals(20,((SeekBar)ReflectionHelpers.getField(a,"brightnessControl")).getProgress());
  }
 }
 private byte[] livePacket(){return java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.LITTLE_ENDIAN).put((byte)3).put((byte)23).putShort((short)182).putShort((short)860).putShort((short)2840).putInt(1280000).putInt(1632).putShort((short)2860).putShort((short)23).array();}
 private void live(MainActivity a,byte[] packet){ReflectionHelpers.callInstanceMethod(a,"parseLiveData",ReflectionHelpers.ClassParameter.from(byte[].class,packet));}
 @Test public void resistanceRequiresCompleteFreshVersionThreePacket() {
  try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible()){
   MainActivity a=c.get();ReflectionHelpers.setField(a,"connected",true);live(a,livePacket());
   assertEquals("23",((TextView)a.findViewById(R.id.resistance)).getText().toString());
   assertTrue(((TextView)a.findViewById(R.id.garminLink)).getText().toString().contains("Leistung"));
   live(a,java.util.Arrays.copyOf(livePacket(),19));assertEquals("23",((TextView)a.findViewById(R.id.resistance)).getText().toString());
   byte[] old=java.util.Arrays.copyOf(livePacket(),18);old[0]=2;live(a,old);assertEquals("—",((TextView)a.findViewById(R.id.resistance)).getText().toString());
   byte[] stale=livePacket();stale[1]=22;live(a,stale);assertEquals("—",((TextView)a.findViewById(R.id.resistance)).getText().toString());
   assertTrue(((TextView)a.findViewById(R.id.bikeLink)).getText().toString().contains("Keine Daten"));
  }
 }
 @Test public void idleConnectionDoesNotReloadHistoryEveryMinute() {
  try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible()){
   MainActivity a=c.get();ReflectionHelpers.setField(a,"connected",true);ReflectionHelpers.setField(a,"historyCharacteristic",new BluetoothGattCharacteristic(java.util.UUID.randomUUID(),2,1));TextView status=ReflectionHelpers.getField(a,"historyStatus");status.setText("Archiv aktuell");
   org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(3));assertEquals("Archiv aktuell",status.getText().toString());assertFalse((Boolean)ReflectionHelpers.getField(a,"historyLoading"));
  }
 }
 @Test public void duplicateSubscriptionCallbackDoesNotRestartArchive() {
  try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible()){
   MainActivity a=c.get();BluetoothGatt g=BluetoothAdapter.getDefaultAdapter().getRemoteDevice("02:00:00:00:00:01").connectGatt(a,false,new BluetoothGattCallback(){});ReflectionHelpers.setField(a,"gatt",g);ReflectionHelpers.setField(a,"connected",true);ReflectionHelpers.setField(a,"notificationsInitialized",true);
   BluetoothGattCallback callback=ReflectionHelpers.getField(a,"gattCallback");callback.onDescriptorWrite(g,new BluetoothGattDescriptor(java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),1),BluetoothGatt.GATT_SUCCESS);
   assertFalse((Boolean)ReflectionHelpers.getField(a,"initialStatusPending"));assertFalse((Boolean)ReflectionHelpers.getField(a,"servicesRecovering"));
  }
 }
 @Test public void progressMetricsHandleMissingPowerAndZeroDuration() throws Exception {
  JSONObject stats=new JSONObject().put("duration_seconds",1800).put("calories_kcal",300).put("distance_km",12).put("average_power_watts",0);
  JSONObject item=new JSONObject().put("row",stats).put("imported",true);
  assertEquals(30,ProgressChart.value(item,0),.001);assertEquals(10,ProgressChart.value(item,2),.001);assertTrue(Double.isNaN(ProgressChart.value(item,3)));
  stats.put("duration_seconds",0);assertTrue(Double.isNaN(ProgressChart.value(item,2)));
 }
 @Test public void connectedBridgeDoesNotStartAnotherScanAndRecentSyncIsReused() {
  try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible()){
   MainActivity a=c.get();org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.BLUETOOTH_SCAN,android.Manifest.permission.BLUETOOTH_CONNECT);
   ReflectionHelpers.setField(a,"connected",true);ReflectionHelpers.callInstanceMethod(a,"beginConnection");assertFalse((Boolean)ReflectionHelpers.getField(a,"scanning"));
   ReflectionHelpers.setField(a,"lastSuccessfulSyncMs",android.os.SystemClock.elapsedRealtime()+1);ReflectionHelpers.callInstanceMethod(a,"beginArchiveSync");assertFalse((Boolean)ReflectionHelpers.getField(a,"syncAfterClock"));
   ReflectionHelpers.setField(a,"lastSuccessfulSyncMs",0L);ReflectionHelpers.callInstanceMethod(a,"beginArchiveSync");assertTrue((Boolean)ReflectionHelpers.getField(a,"syncAfterClock"));
  }
 }
 @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) public void landscapeKeepsConnectionValuesAndFits() throws Exception {
  for(String mode:new String[]{"notnight","night"}){
   RuntimeEnvironment.setQualifiers("w412dp-h892dp-port-"+mode+"-xxhdpi");
   try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible()){
    MainActivity a=c.get();BluetoothGatt g=BluetoothAdapter.getDefaultAdapter().getRemoteDevice("02:00:00:00:00:01").connectGatt(a,false,new BluetoothGattCallback(){});ReflectionHelpers.setField(a,"gatt",g);ReflectionHelpers.setField(a,"connected",true);live(a,livePacket());HeartRateClient pulseClient=ReflectionHelpers.getField(a,"heartRateClient");ReflectionHelpers.setField(pulseClient,"bpm",142);pulseClient.refresh();a.findViewById(R.id.status).setVisibility(View.GONE);
    RuntimeEnvironment.setQualifiers("w892dp-h412dp-land-"+mode+"-xxhdpi");a.onConfigurationChanged(new Configuration(a.getResources().getConfiguration()));assertSame(g,ReflectionHelpers.getField(a,"gatt"));assertEquals("23",((TextView)a.findViewById(R.id.resistance)).getText().toString());
    View v=a.getWindow().getDecorView();v.measure(View.MeasureSpec.makeMeasureSpec(2676,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1236,View.MeasureSpec.EXACTLY));v.layout(0,0,2676,1236);
    int[] metricPos=new int[2],navPos=new int[2];a.findViewById(R.id.calories).getLocationOnScreen(metricPos);a.findViewById(R.id.tabLive).getLocationOnScreen(navPos);assertTrue(metricPos[1]+a.findViewById(R.id.calories).getHeight()<=navPos[1]);assertTrue(a.findViewById(R.id.calories).getHeight()>60);
    Bitmap bmp=Bitmap.createBitmap(2676,1236,Bitmap.Config.ARGB_8888);v.draw(new Canvas(bmp));Path dir=Path.of("build/reports/screenshots");Files.createDirectories(dir);try(java.io.OutputStream out=Files.newOutputStream(dir.resolve(mode+"-landscape.png"))){bmp.compress(Bitmap.CompressFormat.PNG,100,out);}
   }
  }
 }
 @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) public void renderLightAndDark() throws Exception {
  imported();
  for(String mode:new String[]{"notnight","night"}){
   RuntimeEnvironment.setQualifiers("w412dp-h892dp-port-"+mode+"-xxhdpi");
   try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible()){
    MainActivity a=c.get();((TextView)a.findViewById(R.id.status)).setText("●  Mit Bridge verbunden");((TextView)a.findViewById(R.id.connectButton)).setText("Verbindung trennen");((TextView)a.findViewById(R.id.power)).setText("182 W");((TextView)a.findViewById(R.id.cadence)).setText("86 rpm");((TextView)a.findViewById(R.id.speed)).setText("28,4 km/h");((TextView)a.findViewById(R.id.distance)).setText("12,8 km");((TextView)a.findViewById(R.id.duration)).setText("27:12");((TextView)a.findViewById(R.id.calories)).setText("286 kcal");
    ReflectionHelpers.setField(a,"connected",true);ReflectionHelpers.setField(a,"managementCharacteristic",new BluetoothGattCharacteristic(java.util.UUID.randomUUID(),BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ));ReflectionHelpers.callInstanceMethod(a,"updateStorage",ReflectionHelpers.ClassParameter.from(JSONObject.class,status()));
    live(a,livePacket());HeartRateClient pulseClient=ReflectionHelpers.getField(a,"heartRateClient");ReflectionHelpers.setField(pulseClient,"bpm",142);pulseClient.refresh();a.findViewById(R.id.status).setVisibility(View.GONE);
    for(int id:new int[]{R.id.tabLive,R.id.tabHistory,R.id.tabEsp}){a.findViewById(id).performClick();View v=a.getWindow().getDecorView();v.measure(View.MeasureSpec.makeMeasureSpec(1236,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2676,View.MeasureSpec.EXACTLY));v.layout(0,0,1236,2676);Bitmap bmp=Bitmap.createBitmap(1236,2676,Bitmap.Config.ARGB_8888);v.draw(new Canvas(bmp));Path dir=Path.of("build/reports/screenshots");Files.createDirectories(dir);try(java.io.OutputStream out=Files.newOutputStream(dir.resolve(mode+"-"+(id==R.id.tabLive?"live":id==R.id.tabHistory?"history":"settings")+".png"))){bmp.compress(Bitmap.CompressFormat.PNG,100,out);}}
   }
  }
 }
}
