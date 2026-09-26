package de.smb1display;

import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.time.LocalDate;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,qualifiers="w412dp-h892dp-port-xxhdpi")
public class ChartInteractionTest {
    private static double d(String value){return LocalDate.parse(value).toEpochDay();}
    @Before public void reset(){RuntimeEnvironment.getApplication().getSharedPreferences("display",0).edit().clear().commit();RuntimeEnvironment.getApplication().getSharedPreferences("display",0).edit().putString("language","de").commit();I18n.init(RuntimeEnvironment.getApplication());}
    @Test public void defaultUsesCalendarMonthsAndRealDateSpacing() throws Exception {
        ChartWindow w=new ChartWindow(d("2025-01-01"),d("2026-09-08"));
        assertEquals(d("2026-06-09"),w.start,0);assertEquals(d("2026-09-09"),w.end,0);
        double gap1=w.fraction(d("2026-07-02"))-w.fraction(d("2026-07-01"));
        double gap10=w.fraction(d("2026-07-11"))-w.fraction(d("2026-07-01"));assertEquals(10*gap1,gap10,1e-10);
        assertEquals(d("2026-09-08")+.5,ProgressChart.day(new JSONObject().put("order","2026-09-08T12:00:00")),1e-10);
        assertTrue(Double.isNaN(ProgressChart.day(new JSONObject().put("order",""))));
    }
    @Test public void pinchKeepsFocusAndPanIsBounded() {
        ChartWindow w=new ChartWindow(d("2025-01-01"),d("2026-09-08"));double span=w.span(),anchor=w.start+span*.25;
        w.transform(2,.25,.25);assertEquals(span/2,w.span(),1e-8);assertEquals(anchor,w.start+w.span()*.25,1e-8);
        w.pan(-100);assertEquals(w.lower,w.start,0);w.pan(100);assertEquals(w.upper,w.end,0);
        w.transform(1e9,.5,.5);assertEquals(1,w.span(),1e-8);w.transform(1e-9,.5,.5);assertEquals(w.upper-w.lower,w.span(),1e-8);
        w.reset();assertEquals(span,w.span(),0);
    }
    private JSONObject row(String date) throws Exception {return new JSONObject().put("order",date).put("imported",true).put("row",new JSONObject().put("duration_seconds",1800));}
    private static void gesture(View v,int action,float... xs){
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[xs.length];MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[xs.length];
        for(int i=0;i<xs.length;i++){properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coords[i]=new MotionEvent.PointerCoords();coords[i].x=xs[i];coords[i].y=220;coords[i].pressure=1;coords[i].size=1;}
        MotionEvent e=MotionEvent.obtain(0,100,action,xs.length,properties,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);v.onTouchEvent(e);e.recycle();
    }
    @Test public void twoFingerZoomPanAndRangeRestoration() throws Exception {
        try(var c=Robolectric.buildActivity(MainActivity.class).setup()){
            List<JSONObject> rows=Arrays.asList(row("2025-01-01T12:00:00"),row("2026-09-08T12:00:00"));
            ProgressChart chart=new ProgressChart(c.get(),rows);View plot=ReflectionHelpers.getField(chart,"plot");plot.layout(0,0,900,630);double initial=chart.window.span();
            gesture(plot,MotionEvent.ACTION_DOWN,350);gesture(plot,MotionEvent.ACTION_POINTER_DOWN | (1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),350,550);
            gesture(plot,MotionEvent.ACTION_MOVE,250,650);assertEquals(initial/2,chart.window.span(),.0001);double before=chart.window.start;
            gesture(plot,MotionEvent.ACTION_MOVE,350,750);assertTrue(chart.window.start<before);
            gesture(plot,MotionEvent.ACTION_POINTER_UP | (1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),350,750);gesture(plot,MotionEvent.ACTION_UP,350);
            ProgressChart restored=new ProgressChart(c.get(),rows);assertEquals(chart.window.start,restored.window.start,1e-8);assertEquals(chart.window.end,restored.window.end,1e-8);
        }
    }
    static class Parent extends LinearLayout {boolean disallowed;Parent(android.content.Context c){super(c);}@Override public void requestDisallowInterceptTouchEvent(boolean disallow){disallowed=disallow;super.requestDisallowInterceptTouchEvent(disallow);}}
    @Test public void verticalSwipeReleasesParentScroll() throws Exception {
        try(var c=Robolectric.buildActivity(MainActivity.class).setup()){
            ProgressChart chart=new ProgressChart(c.get(),Arrays.asList(row("2026-09-08T12:00:00")));Parent parent=new Parent(c.get());parent.addView(chart);View plot=ReflectionHelpers.getField(chart,"plot");plot.layout(0,0,900,630);
            gesture(plot,MotionEvent.ACTION_DOWN,300);assertTrue(parent.disallowed);
            MotionEvent move=MotionEvent.obtain(0,100,MotionEvent.ACTION_MOVE,300,450,0);plot.onTouchEvent(move);move.recycle();assertFalse(parent.disallowed);
        }
    }
}
