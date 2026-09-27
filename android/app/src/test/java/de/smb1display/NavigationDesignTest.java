package de.smb1display;

import android.animation.AnimatorSet;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,qualifiers="w412dp-h892dp-port-xxhdpi")
public class NavigationDesignTest {
 @Before public void reset(){RuntimeEnvironment.setQualifiers("w412dp-h892dp-port-xxhdpi");RuntimeEnvironment.getApplication().getSharedPreferences("display",0).edit().clear().commit();I18n.init(RuntimeEnvironment.getApplication());}
 private void settle(){Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(350));}
 private void layout(MainActivity a,int w,int h){View v=a.getWindow().getDecorView();v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);}
 private void select(MainActivity a,int i){ReflectionHelpers.callInstanceMethod(a,"selectTab",ReflectionHelpers.ClassParameter.from(int.class,i));}
 private boolean inside(View child,View ancestor){for(View v=child;v!=null;v=v.getParent() instanceof View?(View)v.getParent():null)if(v==ancestor)return true;return false;}
 private void snapshot(View v,String name)throws Exception{Bitmap image=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(image));Path dir=Path.of("build/reports/screenshots/navigation");Files.createDirectories(dir);try(var out=Files.newOutputStream(dir.resolve(name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
 @Test public void pagesSlideInBothDirectionsAndSettleCleanly(){try(var c=Robolectric.buildActivity(MainActivity.class).setup().visible()){
  MainActivity a=c.get();layout(a,1236,2676);select(a,1);AnimatorSet animation=ReflectionHelpers.getField(a,"tabTransition");assertNotNull(animation);assertEquals(View.VISIBLE,a.findViewById(R.id.livePage).getVisibility());assertTrue(a.findViewById(R.id.historyPage).getTranslationX()>0);
  animation.setCurrentPlayTime(120);assertTrue(a.findViewById(R.id.livePage).getTranslationX()<0);assertTrue(a.findViewById(R.id.historyPage).getTranslationX()>0);settle();assertEquals(View.GONE,a.findViewById(R.id.livePage).getVisibility());assertEquals(0f,a.findViewById(R.id.historyPage).getTranslationX(),.1f);
  select(a,0);assertTrue(a.findViewById(R.id.livePage).getTranslationX()<0);settle();assertNull(ReflectionHelpers.getField(a,"tabTransition"));assertEquals(View.GONE,a.findViewById(R.id.historyPage).getVisibility());assertEquals(0f,a.findViewById(R.id.livePage).getTranslationX(),.1f);
 }}
 @Test public void rapidNavigationAndRotationKeepClientsAndPendingSettings(){try(var c=Robolectric.buildActivity(MainActivity.class).setup().visible()){
  MainActivity a=c.get();layout(a,1236,2676);Object pulse=ReflectionHelpers.getField(a,"heartRateClient");ReflectionHelpers.setField(a,"settingsDirty",true);SeekBar brightness=ReflectionHelpers.getField(a,"brightnessControl");brightness.setProgress(37);
  select(a,2);select(a,1);select(a,0);settle();assertSame(pulse,ReflectionHelpers.getField(a,"heartRateClient"));assertTrue(a.findViewById(R.id.tabLive).isSelected());for(int id:new int[]{R.id.historyPage,R.id.espPage}){assertEquals(View.GONE,a.findViewById(id).getVisibility());assertEquals(0f,a.findViewById(id).getTranslationX(),.1f);}
  select(a,2);RuntimeEnvironment.setQualifiers("w892dp-h412dp-land-xxhdpi");a.onConfigurationChanged(new android.content.res.Configuration(a.getResources().getConfiguration()));layout(a,2676,1236);settle();assertSame(pulse,ReflectionHelpers.getField(a,"heartRateClient"));assertEquals(37,((SeekBar)ReflectionHelpers.getField(a,"brightnessControl")).getProgress());assertTrue((Boolean)ReflectionHelpers.getField(a,"settingsDirty"));assertEquals(0f,a.findViewById(R.id.espPage).getTranslationX(),.1f);
 }}
 @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) public void titlesAlignInBothOrientationsLanguagesAndThemes()throws Exception{
  for(String language:new String[]{"en","de"})for(String mode:new String[]{"night","notnight"})for(boolean land:new boolean[]{false,true}){
   RuntimeEnvironment.setQualifiers((land?"w892dp-h412dp-land-":"w412dp-h892dp-port-")+mode+"-xxhdpi");RuntimeEnvironment.getApplication().getSharedPreferences("display",0).edit().clear().putString("language",language).commit();
   try(var c=Robolectric.buildActivity(MainActivity.class).setup().visible()){
    MainActivity a=c.get();int w=land?2676:1236,h=land?1236:2676;layout(a,w,h);int[] expected=null;float size=0;int baseline=0;
    int[] ids={R.id.workoutTitle,R.id.activitiesTitle,R.id.settingsTitle};
    for(int i=0;i<3;i++){select(a,i);settle();layout(a,w,h);TextView title=a.findViewById(ids[i]);int[] pos=new int[2];title.getLocationOnScreen(pos);if(expected==null){expected=pos;size=title.getTextSize();baseline=title.getBaseline();}else{assertArrayEquals("Heading origins must align",expected,pos);assertEquals(size,title.getTextSize(),.1f);assertEquals(baseline,title.getBaseline());}assertTrue(title.isAccessibilityHeading());}
    if(!land){LinearLayout settings=a.findViewById(R.id.espSection);snapshot(a.getWindow().getDecorView(),language+"-"+mode+"-settings");for(int id:new int[]{R.string.settings_app,R.string.settings_garmin,R.string.settings_bridge})snapshot(settings.findViewWithTag(id),language+"-"+mode+"-"+a.getResources().getResourceEntryName(id));}
   }
  }
 }
 @Test public void controlsBelongToTheCorrectSettingsCategory(){try(var c=Robolectric.buildActivity(MainActivity.class).setup().visible()){
  MainActivity a=c.get();View settings=a.findViewById(R.id.espSection),app=settings.findViewWithTag(R.string.settings_app),watch=settings.findViewWithTag(R.string.settings_garmin),bridge=settings.findViewWithTag(R.string.settings_bridge);
  assertTrue(inside(a.findViewById(R.id.themeButton),app));assertTrue(inside(a.findViewById(R.id.connectButton),bridge));assertTrue(inside(a.findViewById(R.id.wifiButton),bridge));assertTrue(inside(ReflectionHelpers.getField(a,"saveSettings"),bridge));assertTrue(inside(ReflectionHelpers.getField(a,"storageInfo"),bridge));assertTrue(inside(ReflectionHelpers.getField(a,"heartRateStatus"),watch));
 }}
 @Test public void brightnessSliderKeepsItsGesture(){try(var c=Robolectric.buildActivity(MainActivity.class).setup().visible()){
  MainActivity a=c.get();layout(a,1236,2676);select(a,2);settle();layout(a,1236,2676);ScrollView scroll=a.findViewById(R.id.espPage);SeekBar slider=ReflectionHelpers.getField(a,"brightnessControl");Rect rect=new Rect();slider.getDrawingRect(rect);scroll.offsetDescendantRectToMyCoords(slider,rect);scroll.scrollTo(0,rect.top-200);layout(a,1236,2676);assertTrue(slider.getGlobalVisibleRect(rect));float x=rect.left+rect.width()*.8f,y=rect.centerY();
  for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}){MotionEvent e=MotionEvent.obtain(1,action==0?1:100,action,action==0?x:x-rect.width()*.6f,y,0);a.dispatchTouchEvent(e);e.recycle();}settle();assertTrue(a.findViewById(R.id.tabEsp).isSelected());
 }}
}
