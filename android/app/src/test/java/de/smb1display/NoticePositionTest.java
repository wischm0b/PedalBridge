package de.smb1display;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class NoticePositionTest {
 @Test public void noticesKeepWorkoutButtonClearInBothOrientations(){
  for(boolean landscape:new boolean[]{false,true}){
   RuntimeEnvironment.setQualifiers(landscape?"w892dp-h412dp-land-xxhdpi":"w412dp-h892dp-port-xxhdpi");
   try(var controller=Robolectric.buildActivity(MainActivity.class).setup().visible()){
    MainActivity a=controller.get();AppDialog.notice(a,I18n.t(R.string.ui_saving_session_on_the_esp_126));
    FrameLayout root=a.findViewById(android.R.id.content);int w=landscape?2676:1236,h=landscape?1236:2676;
    root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);
    View notice=root.findViewWithTag("appNotice"),button=a.findViewById(R.id.resetButton);
    Rect banner=new Rect(0,0,notice.getWidth(),notice.getHeight()),action=new Rect(0,0,button.getWidth(),button.getHeight());root.offsetDescendantRectToMyCoords(notice,banner);root.offsetDescendantRectToMyCoords(button,action);
    assertTrue(button.isShown());assertTrue(action.height()>0);assertTrue(banner.height()>0);assertFalse("Notice must not overlap Finish workout",Rect.intersects(banner,action));assertTrue(banner.top>=0);assertTrue(banner.bottom<=root.getHeight());
   }
  }
 }
}
