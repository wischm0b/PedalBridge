package de.smb1display;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;

/** Per-session timeline using the original samples, elapsed time, and visible recording gaps. */
final class SessionChart extends LinearLayout {
    final TrainingRecording data;
    final TrainingRecording.Window window;
    int metric;
    final Plot plot;
    final TextView selected;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private String time(double n){long t=Math.max(0,(long)n);return t>=3600?String.format(I18n.locale(),"%d:%02d:%02d",t/3600,t/60%60,t%60):String.format(I18n.locale(),"%d:%02d",t/60,t%60);}
    SessionChart(Context c,TrainingRecording recording){
        super(c);data=recording;window=new TrainingRecording.Window(data.seconds.length==0?1:data.seconds[data.seconds.length-1]);setOrientation(VERTICAL);setBackgroundColor(c.getColor(R.color.surface_alt));
        String[] names={I18n.t(R.string.session_power),I18n.t(R.string.session_cadence),I18n.t(R.string.session_speed),I18n.t(R.string.session_resistance),I18n.t(R.string.session_calories),I18n.t(R.string.session_distance)};
        Spinner chooser=AppDialog.spinner(c,names);chooser.setContentDescription(I18n.t(R.string.session_metric));addView(chooser,new LayoutParams(-1,dp(48)));
        plot=new Plot(c);addView(plot,new LayoutParams(-1,dp(220)));
        selected=AppDialog.text(c,I18n.t(R.string.session_gestures),13,R.color.muted);selected.setPadding(0,dp(8),0,dp(8));addView(selected);
        Button reset=new Button(c);reset.setText(I18n.t(R.string.session_full));reset.setAllCaps(false);reset.setTextColor(c.getColor(R.color.accent));reset.setBackground(AppDialog.interactive(c,R.color.surface,14,false));addView(reset,new LayoutParams(-1,dp(48)));reset.setOnClickListener(v->{window.reset();plot.selection=-1;plot.invalidate();selected.setText(I18n.t(R.string.session_gestures));});
        chooser.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int i,long id){metric=i;plot.selection=-1;selected.setText(I18n.t(R.string.session_gestures));plot.invalidate();}public void onNothingSelected(AdapterView<?> p){}});
        if(data.flags!=0)addView(AppDialog.text(c,I18n.t(R.string.session_gaps),13,R.color.muted));
    }
    final class Plot extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        int selection=-1;float downX,downY,lastX,focus,span;boolean dragging,multi;
        final int slop=ViewConfiguration.get(getContext()).getScaledTouchSlop();
        Plot(Context c){super(c);setClickable(true);setContentDescription(I18n.t(R.string.session_timeline));}
        float left(){return dp(44);}float right(){return getWidth()-dp(8);}float width(){return Math.max(1,right()-left());}
        float x(double t){return left()+(float)((t-window.start)/window.span())*width();}
        float bottom(){return getHeight()-dp(28);}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);double min=0,max=1;
            for(int i=0;i<data.seconds.length;i++)if(data.seconds[i]>=window.start && data.seconds[i]<=window.end && Double.isFinite(data.values[i][metric])){min=Math.min(min,data.values[i][metric]);max=Math.max(max,data.values[i][metric]);}
            max=Math.ceil(max*1.08);final double lo=min,hi=max;
            paint.setTextSize(dp(10));paint.setStrokeWidth(dp(1));paint.setStyle(Paint.Style.FILL);
            for(int n=0;n<3;n++){float y=dp(16)+(bottom()-dp(16))*n/2;paint.setColor(getContext().getColor(R.color.line));canvas.drawLine(left(),y,right(),y,paint);paint.setColor(getContext().getColor(R.color.muted));canvas.drawText(String.format(I18n.locale(),metric==5?"%.1f":"%.0f",hi-(hi-lo)*n/2),0,y+dp(3),paint);paint.setTextAlign(n==0?Paint.Align.LEFT:n==2?Paint.Align.RIGHT:Paint.Align.CENTER);canvas.drawText(time(window.start+window.span()*n/2),left()+width()*n/2,getHeight()-dp(5),paint);paint.setTextAlign(Paint.Align.LEFT);}
            if(!data.available(metric)){paint.setTextSize(dp(13));canvas.drawText(I18n.t(R.string.session_no_signal),left(),getHeight()/2f,paint);return;}
            canvas.save();canvas.clipRect(left(),dp(8),right(),bottom());Path path=new Path();boolean begun=false;
            for(int i=0;i<data.seconds.length;i++){
                double value=data.values[i][metric];if(!Double.isFinite(value)){begun=false;continue;}
                if(i>0 && data.seconds[i]-data.seconds[i-1]>2.5)begun=false;
                float xx=x(data.seconds[i]),yy=bottom()-(float)((value-lo)/(hi-lo))*(bottom()-dp(16));
                if(!begun){path.moveTo(xx,yy);begun=true;paint.setColor(getContext().getColor(R.color.accent));canvas.drawCircle(xx,yy,dp(2),paint);}else path.lineTo(xx,yy);
            }
            paint.setColor(getContext().getColor(R.color.accent));paint.setStrokeWidth(dp(2));paint.setStyle(Paint.Style.STROKE);canvas.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);
            if(selection>=0){float xx=x(data.seconds[selection]);paint.setAlpha(100);canvas.drawLine(xx,dp(8),xx,bottom(),paint);paint.setAlpha(255);double value=data.values[selection][metric];if(Double.isFinite(value))canvas.drawCircle(xx,bottom()-(float)((value-lo)/(hi-lo))*(bottom()-dp(16)),dp(4),paint);}
            canvas.restore();
        }
        float focus(MotionEvent e){return (e.getX(0)+e.getX(1))/2;}float span(MotionEvent e){return (float)Math.hypot(e.getX(0)-e.getX(1),e.getY(0)-e.getY(1));}
        void moved(){selection=-1;selected.setText(time(window.start)+" – "+time(window.end));invalidate();}
        @Override public boolean onTouchEvent(MotionEvent e){switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:downX=lastX=e.getX();downY=e.getY();dragging=false;multi=false;getParent().requestDisallowInterceptTouchEvent(true);return true;
            case MotionEvent.ACTION_POINTER_DOWN:if(e.getPointerCount()==2){multi=true;dragging=true;focus=focus(e);span=span(e);}return true;
            case MotionEvent.ACTION_MOVE:
                if(e.getPointerCount()==2){float f=focus(e),s=span(e);if(span>dp(8)&&s>dp(8))window.zoom(s/span,(focus-left())/width(),(f-left())/width());focus=f;span=s;moved();return true;}
                if(e.getPointerCount()!=1)return true;
                float dx=e.getX()-downX,dy=e.getY()-downY;if(!dragging&&Math.abs(dy)>slop&&Math.abs(dy)>Math.abs(dx)){getParent().requestDisallowInterceptTouchEvent(false);return true;}
                if(Math.abs(dx)>slop)dragging=true;if(dragging){window.pan((lastX-e.getX())/width());moved();}lastX=e.getX();return true;
            case MotionEvent.ACTION_POINTER_UP:if(e.getPointerCount()==2){int i=e.getActionIndex()==0?1:0;lastX=downX=e.getX(i);downY=e.getY(i);}return true;
            case MotionEvent.ACTION_UP:
                if(!dragging&&!multi){double best=Double.POSITIVE_INFINITY;int found=-1;for(int i=0;i<data.seconds.length;i++)if(data.seconds[i]>=window.start&&data.seconds[i]<=window.end&&Math.abs(x(data.seconds[i])-e.getX())<best){best=Math.abs(x(data.seconds[i])-e.getX());found=i;}selection=found;if(found>=0){double v=data.values[found][metric];selected.setText(time(data.seconds[found])+" · "+(Double.isFinite(v)?String.format(I18n.locale(),"%.1f %s",v,TrainingRecording.UNITS[metric]):I18n.t(R.string.session_no_signal)));}invalidate();performClick();}
                getParent().requestDisallowInterceptTouchEvent(false);return true;
            case MotionEvent.ACTION_CANCEL:getParent().requestDisallowInterceptTouchEvent(false);return true;
            default:return true;
        }}
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
