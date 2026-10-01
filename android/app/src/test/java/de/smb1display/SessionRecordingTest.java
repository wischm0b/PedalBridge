package de.smb1display;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.nio.*;
import java.nio.file.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class SessionRecordingTest {
 static byte[] fixture(int count){ByteBuffer b=ByteBuffer.allocate(16+24*count).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0x31574152).putInt(7).putInt(0).putShort((short)24).putShort((short)1);for(int i=0;i<count;i++)b.putInt(i*1000).putShort((short)(200+80*Math.sin(i*.1))).putShort((short)825).putShort((short)2430).putShort((short)300).putInt(i*7).putInt(i*240).putShort((short)(1|2|4|8|64)).putShort((short)23);return b.array();}
 @Before public void language(){I18n.init(RuntimeEnvironment.getApplication());}
 @Test public void decodeUnitsAndUnsignedCounters(){byte[] raw=fixture(2);ByteBuffer b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);b.putInt(16+24,0x80000000).putInt(16+24+12,0x80000000).putInt(16+24+16,0x80000000);TrainingRecording r=new TrainingRecording(raw,7);assertEquals(82.5,r.values[0][1],0);assertEquals(24.3,r.values[0][2],.0001);assertEquals(23,r.values[0][3],0);assertEquals(2147483.648,r.seconds[1],.0001);assertEquals(2147483.648,r.values[1][4],.0001);assertEquals(2147483.648,r.values[1][5],.0001);}
 @Test public void missingAndStaleSignalsStayMissing(){byte[] bytes=fixture(3);ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);b.putShort(16+20,(short)1);b.putShort(16+24+20,(short)(2|4|8|64));TrainingRecording r=new TrainingRecording(bytes,7);for(int i=0;i<4;i++){assertTrue(Double.isNaN(r.values[0][i]));assertTrue(Double.isNaN(r.values[1][i]));}assertTrue(Double.isFinite(r.values[1][4]));assertTrue(r.available(0));}
 @Test public void rejectCorruptHeadersAndTime(){for(int type=0;type<4;type++){byte[] bytes=fixture(2);ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);if(type==0)b.putInt(0,0);if(type==1)b.putShort(12,(short)12);if(type==2)b.putInt(4,8);if(type==3){b.putInt(16,2000);b.putInt(40,1000);}try{new TrainingRecording(bytes,7);fail("Must reject corrupt recording");}catch(IllegalArgumentException expected){}}try{new TrainingRecording(new byte[17],7);fail();}catch(IllegalArgumentException expected){}}
 @Test public void viewportZoomPanAndReset(){TrainingRecording.Window w=new TrainingRecording.Window(2400);w.zoom(4,.5,.5);assertEquals(600,w.span(),.001);assertEquals(900,w.start,.001);w.pan(100);assertEquals(2400,w.end,.001);w.pan(-100);assertEquals(0,w.start,.001);w.zoom(10000,.5,.5);assertEquals(10,w.span(),.001);w.reset();assertEquals(2400,w.span(),.001);}
 @Test public void archiveLookupSeparatesDevices()throws Exception{try(TrainingDatabase db=new TrainingDatabase(RuntimeEnvironment.getApplication())){byte[] bytes=fixture(3);db.saveRaw("synthetic-a",7,bytes,TrainingDatabase.checksum(bytes));assertArrayEquals(bytes,db.loadRaw("synthetic-a",7));assertNull(db.loadRaw("synthetic-b",7));assertNull(db.loadRaw("synthetic-a",8));}}
 private static void gesture(View v,int action,float... xs){
  MotionEvent.PointerProperties[] pp=new MotionEvent.PointerProperties[xs.length];MotionEvent.PointerCoords[] pc=new MotionEvent.PointerCoords[xs.length];
  for(int i=0;i<xs.length;i++){pp[i]=new MotionEvent.PointerProperties();pp[i].id=i;pp[i].toolType=MotionEvent.TOOL_TYPE_FINGER;pc[i]=new MotionEvent.PointerCoords();pc[i].x=xs[i];pc[i].y=220;pc[i].pressure=1;pc[i].size=1;}
  MotionEvent e=MotionEvent.obtain(0,100,action,xs.length,pp,pc,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);v.onTouchEvent(e);e.recycle();
 }
 @Test public void gesturesZoomPanSelectAndReleaseVerticalScroll(){
  SessionChart chart=new SessionChart(RuntimeEnvironment.getApplication(),new TrainingRecording(fixture(2400),7));ChartInteractionTest.Parent parent=new ChartInteractionTest.Parent(chart.getContext());parent.addView(chart);chart.plot.layout(0,0,900,660);
  gesture(chart.plot,MotionEvent.ACTION_DOWN,350);gesture(chart.plot,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),350,550);gesture(chart.plot,MotionEvent.ACTION_MOVE,250,650);assertEquals(2399/2.0,chart.window.span(),.001);double start=chart.window.start;
  gesture(chart.plot,MotionEvent.ACTION_MOVE,350,750);assertTrue(chart.window.start<start);gesture(chart.plot,MotionEvent.ACTION_CANCEL,350);assertFalse(parent.disallowed);
  gesture(chart.plot,MotionEvent.ACTION_DOWN,450);gesture(chart.plot,MotionEvent.ACTION_UP,450);assertTrue(chart.plot.selection>=0);assertTrue(chart.selected.getText().toString().contains("W"));
  gesture(chart.plot,MotionEvent.ACTION_DOWN,300);MotionEvent move=MotionEvent.obtain(0,100,MotionEvent.ACTION_MOVE,300,450,0);chart.plot.onTouchEvent(move);move.recycle();assertFalse(parent.disallowed);
 }
 @Test public void detailsLoadOfflineAndExplainMissingRecording()throws Exception{
  try(var controller=Robolectric.buildActivity(MainActivity.class).setup().visible()){
   MainActivity a=controller.get();TrainingDatabase db=org.robolectric.util.ReflectionHelpers.getField(a,"archiveDb");byte[] bytes=fixture(3);db.saveRaw("synthetic-offline",7,bytes,TrainingDatabase.checksum(bytes));
   for(long id:new long[]{7,8}){
    org.json.JSONObject row=new org.json.JSONObject().put("id",id).put("stats",new org.json.JSONObject());org.json.JSONObject item=new org.json.JSONObject().put("row",row).put("archive","synthetic-offline");
    AppDialog dialog=TrainingDialogs.details(a,item,java.util.Arrays.asList(item),"Synthetic workout");dialog.show();
    org.robolectric.util.ReflectionHelpers.callInstanceMethod(a,"loadSessionChart",org.robolectric.util.ReflectionHelpers.ClassParameter.from(AppDialog.class,dialog),org.robolectric.util.ReflectionHelpers.ClassParameter.from(org.json.JSONObject.class,item));
    java.util.concurrent.ExecutorService io=org.robolectric.util.ReflectionHelpers.getField(a,"archiveIo");io.submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();LinearLayout group=dialog.body.findViewWithTag("sessionTimeline");
    if(id==7)assertTrue(group.getChildAt(1) instanceof SessionChart);else assertEquals(I18n.t(R.string.session_sync_required),((TextView)group.getChildAt(1)).getText().toString());dialog.dismiss();
   }
  }
 }
 @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) public void chartRendersInBothThemesAndOrientations()throws Exception{
  for(String mode:new String[]{"notnight","night"})for(boolean land:new boolean[]{false,true}){
   RuntimeEnvironment.setQualifiers(land?"w892dp-h412dp-land-"+mode+"-xxhdpi":"w412dp-h892dp-port-"+mode+"-xxhdpi");
   SessionChart chart=new SessionChart(RuntimeEnvironment.getApplication(),new TrainingRecording(fixture(2400),7));int width=land?2200:950,height=1050;
   chart.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));chart.layout(0,0,width,height);
   Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);chart.draw(new Canvas(bitmap));java.nio.file.Path dir=Paths.get("build/session-previews");Files.createDirectories(dir);try(java.io.OutputStream out=Files.newOutputStream(dir.resolve(mode+(land?"-land":"-port")+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}assertTrue(chart.plot.getHeight()>0);assertEquals(2399,chart.window.end,0);
   chart.metric=3;chart.plot.invalidate();chart.draw(new Canvas(bitmap));
  }
 }
}
