package de.smb1display;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,qualifiers="w412dp-h892dp-port-xxhdpi")
public class TabSwipeTest {
 @Before public void reset(){RuntimeEnvironment.getApplication().getSharedPreferences("display",0).edit().clear().commit();RuntimeEnvironment.getApplication().getSharedPreferences("display",0).edit().putString("language","de").commit();I18n.init(RuntimeEnvironment.getApplication());}
 private void tab(MainActivity a,int i){ReflectionHelpers.callInstanceMethod(a,"selectTab",ReflectionHelpers.ClassParameter.from(int.class,i));layout(a);}
 private void layout(MainActivity a){View root=a.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(1236,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2676,View.MeasureSpec.EXACTLY));root.layout(0,0,1236,2676);}
 private void send(MainActivity a,int action,float x,float y,long time){MotionEvent e=MotionEvent.obtain(1,time,action,x,y,0);a.dispatchTouchEvent(e);e.recycle();}
 private void swipe(MainActivity a,View page,boolean left,boolean vertical){Rect r=new Rect();assertTrue(page.getGlobalVisibleRect(r));float x=r.left+r.width()*(left?.8f:.2f),y=r.top+r.height()*.12f;float end=x+(left?-r.width()*.6f:r.width()*.6f);send(a,0,x,y,1);send(a,2,vertical?x+10:end,vertical?y-500:y+8,100);send(a,1,vertical?x+10:end,vertical?y-500:y+8,150);}
 @Test public void swipesNavigateBothWaysAndStopAtEnds(){try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible()){MainActivity a=c.get();layout(a);swipe(a,a.findViewById(R.id.livePage),true,false);assertTrue("Expected History after left swipe",a.findViewById(R.id.tabHistory).isSelected());layout(a);swipe(a,a.findViewById(R.id.historyPage),true,false);assertTrue("Expected Settings after left swipe",a.findViewById(R.id.tabEsp).isSelected());layout(a);swipe(a,a.findViewById(R.id.espPage),true,false);assertTrue(a.findViewById(R.id.tabEsp).isSelected());layout(a);swipe(a,a.findViewById(R.id.espPage),false,false);assertTrue(a.findViewById(R.id.tabHistory).isSelected());}}
 @Test public void verticalScrollDoesNotSwitchTab(){try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible()){MainActivity a=c.get();tab(a,1);swipe(a,a.findViewById(R.id.historyPage),true,true);assertTrue(a.findViewById(R.id.tabHistory).isSelected());}}
 @Test public void chartKeepsItsHorizontalGestures(){try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible()){MainActivity a=c.get();tab(a,1);LinearLayout section=a.findViewById(R.id.historySection);section.removeAllViews();ProgressChart chart=new ProgressChart(a,java.util.Collections.emptyList());section.addView(chart,new LinearLayout.LayoutParams(-1,1000));layout(a);swipe(a,chart,true,false);assertTrue(a.findViewById(R.id.tabHistory).isSelected());}}
}
