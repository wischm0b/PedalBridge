package de.smb1display;

import android.bluetooth.*;
import android.content.res.Configuration;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,qualifiers="w412dp-h892dp-port-xxhdpi")
public class LocalizationTest {
    private void capture(android.view.View view,String name,int width,int height) throws Exception {
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);
        var image=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(image));
        var dir=java.nio.file.Path.of("build/reports/screenshots/localization");java.nio.file.Files.createDirectories(dir);
        try(var out=java.nio.file.Files.newOutputStream(dir.resolve(name+".png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
    }
    @Before public void reset(){RuntimeEnvironment.getApplication().getSharedPreferences("display",0).edit().clear().commit();I18n.init(RuntimeEnvironment.getApplication());}
    @Test public void firstLaunchUsesEnglishAndCompleteEnglishChart(){
        try(var c=Robolectric.buildActivity(MainActivity.class).setup().visible()){
            MainActivity a=c.get();assertEquals("Workout",((TextView)a.findViewById(R.id.tabLive)).getText().toString());
            assertEquals("Settings",((TextView)a.findViewById(R.id.tabEsp)).getText().toString());
            assertEquals("Your activities",I18n.t(R.string.ui_your_activities_129));
            ProgressChart chart=new ProgressChart(a,List.of());assertEquals("Avg. power",chart.NAMES[3]);
        }
    }
    @Test public void languageSwitchPreservesGattHeartRateSyncAndUnsavedSettings(){
        try(var c=Robolectric.buildActivity(MainActivity.class).setup().visible()){
            MainActivity a=c.get();BluetoothGatt g=BluetoothAdapter.getDefaultAdapter().getRemoteDevice("02:00:00:00:00:01").connectGatt(a,false,new BluetoothGattCallback(){});
            ReflectionHelpers.setField(a,"gatt",g);ReflectionHelpers.setField(a,"connected",true);ReflectionHelpers.setField(a,"historyLoading",true);
            ReflectionHelpers.setField(a,"settingsDirty",true);((SeekBar)ReflectionHelpers.getField(a,"brightnessControl")).setProgress(37);
            HeartRateClient pulse=ReflectionHelpers.getField(a,"heartRateClient");ReflectionHelpers.setField(pulse,"bpm",142);pulse.refresh();
            ReflectionHelpers.callInstanceMethod(a,"setLanguage",ReflectionHelpers.ClassParameter.from(String.class,"de"));
            assertSame(g,ReflectionHelpers.getField(a,"gatt"));assertSame(pulse,ReflectionHelpers.getField(a,"heartRateClient"));
            assertTrue((Boolean)ReflectionHelpers.getField(a,"historyLoading"));assertTrue((Boolean)ReflectionHelpers.getField(a,"settingsDirty"));
            assertEquals(37,((SeekBar)ReflectionHelpers.getField(a,"brightnessControl")).getProgress());assertTrue(((TextView)a.findViewById(R.id.heartRate)).getText().toString().contains("142"));
            assertEquals("Einstellungen",((TextView)a.findViewById(R.id.tabEsp)).getText().toString());
            ReflectionHelpers.callInstanceMethod(a,"setLanguage",ReflectionHelpers.ClassParameter.from(String.class,"en"));
            assertEquals("Settings",((TextView)a.findViewById(R.id.tabEsp)).getText().toString());assertSame(g,ReflectionHelpers.getField(a,"gatt"));
        }
    }
    @Test public void selectedLanguageSurvivesRelaunchAndThemeChange(){
        RuntimeEnvironment.getApplication().getSharedPreferences("display",0).edit().putString("language","de").commit();
        try(var c=Robolectric.buildActivity(MainActivity.class).setup().visible()){
            MainActivity a=c.get();a.onConfigurationChanged(new Configuration(a.getResources().getConfiguration()));
            assertEquals("Verlauf",((TextView)a.findViewById(R.id.tabHistory)).getText().toString());assertEquals("de",I18n.locale().getLanguage());
        }
        try(var c=Robolectric.buildActivity(MainActivity.class).setup().visible()){assertEquals("Verlauf",((TextView)c.get().findViewById(R.id.tabHistory)).getText().toString());}
    }
    @Test public void everyStringHasDistinctEnglishAndGermanResources(){
        var context=RuntimeEnvironment.getApplication();I18n.init(context);String[] english=new String[I18nIds.ALL.length];
        for(int i=0;i<english.length;i++)english[i]=I18n.t(I18nIds.ALL[i]);
        context.getSharedPreferences("display",0).edit().putString("language","de").commit();I18n.init(context);
        for(int i=0;i<english.length;i++){assertFalse(english[i].isEmpty());assertFalse(I18n.t(I18nIds.ALL[i]).isEmpty());}
        assertEquals("Helligkeit",I18n.t(R.string.ui_brightness_149));assertEquals("Brightness",english[Arrays.stream(I18nIds.ALL).boxed().toList().indexOf(R.string.ui_brightness_149)]);
    }
    @Test public void transientHeartRateStatusChangesLanguageWithoutReconnect(){
        var context=RuntimeEnvironment.getApplication();context.getSharedPreferences("display",0).edit().putString("language","de").commit();I18n.init(context);
        final String[] message={""};HeartRateClient client=new HeartRateClient(context,new HeartRateClient.Listener(){public void state(int bpm,String text){message[0]=text;}public void devices(List<HeartRateClient.Device> d){}});
        var previous=I18n.resources();context.getSharedPreferences("display",0).edit().putString("language","en").commit();I18n.init(context);client.translateState(previous);
        assertEquals("No heart rate sensor selected yet",message[0]);client.close();
    }
    @Test public void datesAndComparisonsUseTheSelectedLanguage() throws Exception {
        JSONObject row=new JSONObject().put("datetime_local","2025-01-02T06:30:00");assertTrue(I18n.importDate(row).contains("Jan"));
        JSONObject item=new JSONObject().put("imported",true).put("row",new JSONObject().put("duration_seconds",1800));
        assertTrue(TrainingInsights.comparison(item,List.of()).contains("no average resistance"));
        var context=RuntimeEnvironment.getApplication();context.getSharedPreferences("display",0).edit().putString("language","de").commit();I18n.init(context);
        assertTrue(I18n.importDate(row).contains("02.01.2025"));assertTrue(TrainingInsights.comparison(item,List.of()).contains("fehlt"));
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) public void englishScreensAndLanguageDialogFitBothThemes() throws Exception {
        for(String mode:new String[]{"night","notnight"}) {
            RuntimeEnvironment.setQualifiers("w412dp-h892dp-port-"+mode+"-xxhdpi");
            try(var c=Robolectric.buildActivity(MainActivity.class).setup().visible()) {
                MainActivity a=c.get();a.findViewById(R.id.tabEsp).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(320));capture(a.getWindow().getDecorView(),"english-settings-"+mode,1236,2676);
                ReflectionHelpers.callInstanceMethod(a,"chooseLanguage");AppDialog dialog=ReflectionHelpers.getField(a,"activeDialog");assertTrue(dialog.isShowing());dialog.dismiss();
                a.findViewById(R.id.tabLive).performClick();RuntimeEnvironment.setQualifiers("w892dp-h412dp-land-"+mode+"-xxhdpi");a.onConfigurationChanged(new Configuration(a.getResources().getConfiguration()));
                capture(a.getWindow().getDecorView(),"english-training-landscape-"+mode,2676,1236);
                int[] metric=new int[2],nav=new int[2];a.findViewById(R.id.calories).getLocationOnScreen(metric);a.findViewById(R.id.tabLive).getLocationOnScreen(nav);
                assertTrue(metric[1]+a.findViewById(R.id.calories).getHeight()<=nav[1]);
            }
        }
    }
}
