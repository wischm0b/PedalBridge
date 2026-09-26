package de.smb1display;
import android.bluetooth.*;
import android.graphics.Bitmap;import android.graphics.Canvas;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,qualifiers="w412dp-h892dp-port-xxhdpi")
public class DialogDesignTest {
 @Before public void reset(){RuntimeEnvironment.setQualifiers("w412dp-h892dp-port-xxhdpi");RuntimeEnvironment.getApplication().getSharedPreferences("display",0).edit().clear().commit();RuntimeEnvironment.getApplication().getSharedPreferences("display",0).edit().putString("language","de").commit();I18n.init(RuntimeEnvironment.getApplication());}
 private JSONObject nativeItem() throws Exception{return new JSONObject().put("row",new JSONObject().put("started",1790224139).put("rawSaved",true).put("stats",new JSONObject().put("seconds",2400).put("active",2350).put("km",18.6).put("kcal",843).put("power",343).put("cadence",78).put("speed",27.9).put("maxPower",480).put("maxCadence",98).put("maxSpeed",32.4).put("workKj",823).put("revolutions",3080))).put("imported",false).put("order","2026-09-26T07:30:00");}
 private JSONObject importedItem() throws Exception{return new JSONObject().put("imported",true).put("order","2026-09-21T07:30:00").put("row",new JSONObject().put("duration_seconds",2400).put("distance_km",18.6).put("calories_kcal",843).put("average_power_watts",343).put("average_cadence_rpm",78).put("average_speed_kmh",27.9).put("average_resistance_level",23).put("original_display",new JSONObject().put("Personalwesen","0 bpm").put("Leistung","343 W")).put("quality_flags","heart_rate_zero"));}
 private List<String> texts(View v){List<String> a=new ArrayList<>();if(v instanceof TextView)a.add(((TextView)v).getText().toString());if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)a.addAll(texts(((ViewGroup)v).getChildAt(i)));return a;}
 private void snapshot(AppDialog d,String name,int width,int height)throws Exception{d.show();View v=d.getWindow().getDecorView();int w=Math.min(width-96,1680);v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height-72,View.MeasureSpec.AT_MOST));v.layout(0,0,w,v.getMeasuredHeight());assertTrue("Dialog must fit screen",v.getMeasuredHeight()<=height-72);Bitmap b=Bitmap.createBitmap(w,v.getMeasuredHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));Path dir=Path.of("build/reports/screenshots/dialogs");Files.createDirectories(dir);try(var out=Files.newOutputStream(dir.resolve(name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}}
 @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) public void allDialogsMatchBothThemes()throws Exception{
  for(String mode:new String[]{"notnight","night"}){RuntimeEnvironment.setQualifiers("w412dp-h892dp-port-"+mode+"-xxhdpi");try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible()){MainActivity a=c.get();
   ReflectionHelpers.callInstanceMethod(a,"chooseTheme");AppDialog theme=ReflectionHelpers.getField(a,"activeDialog");snapshot(theme,mode+"-theme",1236,2676);assertTrue(texts(theme.body).stream().anyMatch(t->t.contains("Wie das System")));theme.dismiss();
   Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.BLUETOOTH_SCAN,android.Manifest.permission.BLUETOOTH_CONNECT);ReflectionHelpers.callInstanceMethod(a,"chooseHeartRateSensor");AppDialog pulse=ReflectionHelpers.getField(a,"heartRatePicker");HeartRateClient hr=ReflectionHelpers.getField(a,"heartRateClient");HeartRateClient.Listener listener=ReflectionHelpers.getField(hr,"listener");ReflectionHelpers.setField(hr,"scanning",true);listener.state(-1,"Suche läuft …");snapshot(pulse,mode+"-pulse-empty",1236,2676);
   listener.devices(Arrays.asList(new HeartRateClient.Device(BluetoothAdapter.getDefaultAdapter().getRemoteDevice("02:00:00:00:00:01"),"fēnix 8")));snapshot(pulse,mode+"-pulse-found",1236,2676);assertTrue(texts(pulse.body).contains("fēnix 8  ›"));pulse.dismiss();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertFalse(hr.isSearching());
   AppDialog detail=TrainingDialogs.details(a,nativeItem(),Arrays.asList(nativeItem()),"26. September 2026 · 07:30");detail.action("Schließen",false,detail::dismiss);detail.action("Vergleichen",true,()->{});snapshot(detail,mode+"-training",1236,2676);assertTrue(texts(detail.body).contains("343,0 W"));assertTrue(texts(detail.body).contains("40:00"));detail.dismiss();
   AppDialog imported=TrainingDialogs.details(a,importedItem(),Arrays.asList(importedItem()),"21. September 2026 · 07:30");imported.action("Schließen",true,imported::dismiss);snapshot(imported,mode+"-import",1236,2676);assertTrue(texts(imported.body).contains("Herzfrequenz (Quelle)"));assertTrue(texts(imported.body).contains("Puls 0 ist vermutlich ein fehlender Messwert."));imported.dismiss();
   List<JSONObject> related=new ArrayList<>();for(int i=0;i<8;i++){JSONObject r=importedItem();r.put("order","2026-09-"+(10+i)+"T07:30:00");r.getJSONObject("row").put("average_cadence_rpm",70+i*2);related.add(r);}AppDialog comparison=TrainingDialogs.comparison(a,related.get(0),related);comparison.action("Schließen",true,comparison::dismiss);snapshot(comparison,mode+"-comparison",1236,2676);comparison.dismiss();
  }}
 }
 @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) public void trainingDialogFitsLandscape()throws Exception{RuntimeEnvironment.setQualifiers("w892dp-h412dp-land-night-xxhdpi");try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible()){AppDialog d=TrainingDialogs.details(c.get(),nativeItem(),Arrays.asList(nativeItem()),"26. September 2026 · 07:30");d.action("Schließen",true,d::dismiss);snapshot(d,"night-training-landscape",2676,1236);d.dismiss();}}
 @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) public void largeSystemFontKeepsActionsVisible()throws Exception{
  android.content.res.Configuration configuration=new android.content.res.Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());configuration.fontScale=1.5f;RuntimeEnvironment.getApplication().getResources().updateConfiguration(configuration,RuntimeEnvironment.getApplication().getResources().getDisplayMetrics());
  try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible()){
   AppDialog d=new AppDialog(c.get(),"LIVE-PULS","Pulssensor verbinden","Auf der Fenix Herzfrequenz senden aktivieren, dann deine Uhr auswählen.",R.drawable.status_watch);for(int i=0;i<10;i++)d.paragraph("Gefundener Sensor · fēnix 8");Button close=d.action("Schließen",false,d::dismiss);d.action("Erneut suchen",true,()->{});snapshot(d,"large-font-pulse",1236,2676);android.graphics.Rect visible=new android.graphics.Rect(0,0,close.getWidth(),close.getHeight());android.view.ViewGroup decor=(android.view.ViewGroup)d.getWindow().getDecorView();decor.offsetDescendantRectToMyCoords(close,visible);assertTrue(visible.top>=0 && visible.bottom<=decor.getHeight());assertTrue(visible.left>=0 && visible.right<=decor.getWidth());d.dismiss();
  }
 }
 @Test public void customNoticesReplaceEachOtherAndDisappear(){try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible()){MainActivity a=c.get();AppDialog.notice(a,"Erste Meldung");AppDialog.notice(a,"Neue Meldung");View root=a.findViewById(android.R.id.content);View notice=root.findViewWithTag("appNotice");assertTrue(texts(notice).contains("Neue Meldung"));assertFalse(texts(notice).contains("Erste Meldung"));Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(7));assertNull(root.findViewWithTag("appNotice"));}}
}
