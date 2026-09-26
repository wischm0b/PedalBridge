package de.smb1display;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

/** Calendar spacing uses displayed local dates; an unverified import timezone is not invented. */
public class ProgressChart extends LinearLayout {
    private final ArrayList<JSONObject> sessions;
    private final TextView caption, selection, range;
    private final Plot plot;
    private final int[] intensityBands;
    final ChartWindow window;
    private int metric;
    static final String[] NAMES={"Trainingsdauer", "Distanz", "Energie pro Minute", "Ø Leistung"};
    static final String[] UNITS={"min", "km", "kcal/min", "W"};
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("dd.MM.yyyy",Locale.GERMANY);

    public ProgressChart(Context context,List<JSONObject> rows) {
        super(context);setOrientation(VERTICAL);setPadding(0,dp(20),0,0);
        sessions=new ArrayList<>(rows);sessions.sort(Comparator.comparingDouble(ProgressChart::day));
        intensityBands=new int[sessions.size()];for(int i=0;i<sessions.size();i++)intensityBands[i]=TrainingInsights.band(sessions.get(i),sessions);
        double earliest=Double.POSITIVE_INFINITY,latest=Double.NEGATIVE_INFINITY;
        for(JSONObject row:sessions){double date=day(row);if(Double.isFinite(date)){earliest=Math.min(earliest,date);latest=Math.max(latest,date);}}
        if(!Double.isFinite(latest))earliest=latest=LocalDate.now().toEpochDay();
        window=new ChartWindow(earliest,latest);
        android.content.SharedPreferences prefs=context.getSharedPreferences("display",0);
        if(prefs.getBoolean("chartCustomRange",false))window.set(Double.longBitsToDouble(prefs.getLong("chartFrom",0)),Double.longBitsToDouble(prefs.getLong("chartTo",0)));
        addView(text("Deine Entwicklung",20));
        LinearLayout card=new LinearLayout(context);card.setOrientation(VERTICAL);card.setPadding(dp(12),dp(12),dp(12),dp(12));card.setBackgroundResource(R.drawable.metric_background);addView(card);
        Spinner chooser=AppDialog.spinner(context,NAMES);chooser.setContentDescription("Messwert für Trainingsdiagramm");card.addView(chooser,new LayoutParams(-1,dp(48)));
        TextView legend=text("Intensität nach kcal/min: Blau niedriger · Petrol mittel · Türkis höher. Relativ zur gleichen Quelle, kalorienbasiert geschätzt. Grau: nicht eingeordnet.",12);legend.setTextColor(context.getColor(R.color.muted));android.text.SpannableString legendColors=new android.text.SpannableString(legend.getText());String[] labels={"Blau niedriger","Petrol mittel","Türkis höher"};for(int i=0;i<3;i++){int pos=legendColors.toString().indexOf(labels[i]);legendColors.setSpan(new android.text.style.ForegroundColorSpan(context.getColor(TrainingInsights.color(i))),pos,pos+labels[i].length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}legend.setText(legendColors);card.addView(legend);
        range=text("",14);card.addView(range);
        LinearLayout controls=new LinearLayout(context);
        control(controls,"3 Monate",()->{window.reset();saveRange(false);changed();});
        control(controls,"Gesamt",()->{window.all();saveRange(true);changed();});
        control(controls,"−",()->{window.transform(.5,.5,.5);saveRange(true);changed();}).setContentDescription("Zeitraum vergrößern");
        control(controls,"+",()->{window.transform(2,.5,.5);saveRange(true);changed();}).setContentDescription("Zeitraum verkleinern");
        card.addView(controls);
        caption=text("",12);caption.setTextColor(context.getColor(R.color.muted));card.addView(caption);
        plot=new Plot(context);card.addView(plot,new LayoutParams(-1,dp(210)));
        selection=text("Punkt antippen für Trainingsdetails",13);card.addView(selection);
        TextView hint=text("Zwei Finger: zoomen und verschieben · seitlich wischen\nPunkte: Trainings · Linie: Mittel der letzten 5 gültigen Trainings",12);hint.setTextColor(context.getColor(R.color.muted));card.addView(hint);
        metric=prefs.getInt("chartMetric",0);if(metric<0 || metric>=NAMES.length)metric=0;
        chooser.setSelection(metric);chooser.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int i,long id){metric=i;prefs.edit().putInt("chartMetric",i).apply();changed();}public void onNothingSelected(AdapterView<?> p){}});changed();
    }
    static double day(JSONObject item){
        String order=item.optString("order");
        try {LocalDateTime dt=LocalDateTime.parse(order);return dt.toLocalDate().toEpochDay()+dt.toLocalTime().toSecondOfDay()/86400.0;}
        catch(Exception ignored){try{return LocalDate.parse(order).toEpochDay();}catch(Exception e){return Double.NaN;}}
    }
    static double value(JSONObject item,int metric) {
        JSONObject row=item.optJSONObject("row");if(row==null)return Double.NaN;
        boolean imported=item.optBoolean("imported");JSONObject stats=imported?row:row.optJSONObject("stats");if(stats==null)return Double.NaN;
        double seconds=stats.optDouble(imported?"duration_seconds":"seconds",Double.NaN);
        double result=metric==0?seconds/60:metric==1?stats.optDouble(imported?"distance_km":"km",Double.NaN):metric==2?stats.optDouble(imported?"calories_kcal":"kcal",Double.NaN)/(seconds/60):stats.optDouble(imported?"average_power_watts":"power",Double.NaN);
        if(!Double.isFinite(result) || result<0 || (metric==2 && !(seconds>0)) || (metric==3 && result==0))return Double.NaN;
        return result;
    }

    private void saveRange(boolean custom){getContext().getSharedPreferences("display",0).edit().putBoolean("chartCustomRange",custom).putLong("chartFrom",Double.doubleToLongBits(window.start)).putLong("chartTo",Double.doubleToLongBits(window.end)).apply();}
    private void changed(){plot.selected=-1;selection.setText("Punkt antippen für Trainingsdetails");updateRange();plot.invalidate();}
    private void updateRange(){
        range.setText(LocalDate.ofEpochDay((long)Math.floor(window.start)).format(DATE)+" – "+LocalDate.ofEpochDay((long)Math.ceil(window.end)-1).format(DATE));
        int visible=0,undated=0;for(JSONObject row:sessions){double date=day(row);if(!Double.isFinite(date)){undated++;continue;}if(date>=window.start && date<window.end && Double.isFinite(value(row,metric)))visible++;}
        caption.setText(visible+" Trainings im Zeitraum · "+UNITS[metric]+(undated>0?"\n"+undated+" ohne Datum nur in der Liste":"")+(metric==2?"\nKalorien sind geräteabhängige Schätzwerte.":metric==3?"\nFehlende Werte und Leistung 0 ausgelassen.":""));
        plot.setContentDescription(NAMES[metric]+", "+range.getText()+", "+visible+" Trainings. Zeitraum mit den Schaltflächen ändern; Einzelwerte in der Trainingsliste.");
    }
    private TextView control(LinearLayout parent,String label,Runnable task){TextView v=text(label,13);v.setTextColor(getContext().getColor(R.color.accent));v.setGravity(Gravity.CENTER);v.setBackgroundResource(android.R.drawable.list_selector_background);v.setClickable(true);v.setFocusable(true);v.setOnClickListener(x->task.run());parent.addView(v,new LayoutParams(0,dp(48),label.length()>1?2:1));return v;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String text,int size){TextView v=new TextView(getContext());v.setText(text);v.setTextSize(size);v.setTextColor(getContext().getColor(R.color.ink));v.setPadding(dp(4),dp(6),dp(4),dp(6));return v;}

    private class Plot extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);int selected=-1;
        float downX,downY,lastX,focus,span;boolean dragging,multi;
        final int slop=ViewConfiguration.get(getContext()).getScaledTouchSlop();
        double maxValue=1;
        Plot(Context c){super(c);setClickable(true);}
        float left(){return dp(42);}float right(){return getWidth()-dp(14);}float width(){return Math.max(1,right()-left());}
        float x(int i){return left()+(float)window.fraction(day(sessions.get(i)))*width();}
        float bottom(){return getHeight()-dp(28);}float y(double value){return bottom()-(float)(value/maxValue)*(bottom()-dp(12));}
        boolean visible(int i){double d=day(sessions.get(i));return Double.isFinite(d) && d>=window.start && d<window.end && Double.isFinite(value(sessions.get(i),metric));}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);maxValue=1;int count=0;
            for(int i=0;i<sessions.size();i++)if(visible(i)){maxValue=Math.max(maxValue,value(sessions.get(i),metric));count++;}
            // Include the rolling mean in the Y range, even when its preceding points are outside the viewport.
            ArrayDeque<Double> recent=new ArrayDeque<>();double[] means=new double[sessions.size()];Arrays.fill(means,Double.NaN);
            for(int i=0;i<sessions.size();i++){double v=value(sessions.get(i),metric);if(!Double.isFinite(day(sessions.get(i))) || !Double.isFinite(v))continue;recent.add(v);if(recent.size()>5)recent.remove();double sum=0;for(double n:recent)sum+=n;means[i]=sum/recent.size();if(visible(i))maxValue=Math.max(maxValue,means[i]);}
            maxValue=Math.ceil(maxValue*1.1);paint.setTextSize(dp(10));paint.setStrokeWidth(dp(1));
            for(int n=0;n<3;n++){float yy=y(maxValue*n/2);paint.setColor(getContext().getColor(R.color.line));c.drawLine(left(),yy,right(),yy,paint);paint.setColor(getContext().getColor(R.color.muted));c.drawText(String.format(Locale.GERMANY,"%.0f",maxValue*n/2),0,yy+dp(3),paint);}
            DateTimeFormatter tick=DateTimeFormatter.ofPattern(window.span()>370?"MM.yyyy":"dd.MM.yy",Locale.GERMANY);
            for(int n=0;n<3;n++){double d=window.start+window.span()*n/2;if(n==2)d-=.000001;String label=window.span()<=1.01?LocalTime.ofSecondOfDay(Math.min(86399,(long)((d-Math.floor(d))*86400))).format(DateTimeFormatter.ofPattern("HH:mm")):LocalDate.ofEpochDay((long)Math.floor(d)).format(tick);float xx=left()+width()*n/2;paint.setTextAlign(n==0?Paint.Align.LEFT:n==2?Paint.Align.RIGHT:Paint.Align.CENTER);c.drawText(label,xx,getHeight()-dp(5),paint);}paint.setTextAlign(Paint.Align.LEFT);
            if(count==0){paint.setTextSize(dp(13));c.drawText("Keine Werte in diesem Zeitraum",left(),getHeight()/2f,paint);return;}
            c.save();c.clipRect(left()-dp(3),0,right()+dp(3),bottom()+dp(3));Path trend=new Path();boolean begun=false;
            for(int i=0;i<sessions.size();i++){
                if(!Double.isFinite(means[i])){begun=false;continue;}float px=x(i);
                if(!begun){trend.moveTo(px,y(means[i]));begun=true;}else trend.lineTo(px,y(means[i]));
                if(visible(i)){paint.setColor(getContext().getColor(TrainingInsights.color(intensityBands[i])));paint.setAlpha(230);c.drawCircle(px,y(value(sessions.get(i),metric)),dp(3),paint);paint.setAlpha(255);}
            }
            paint.setColor(getContext().getColor(R.color.accent));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));c.drawPath(trend,paint);paint.setStyle(Paint.Style.FILL);
            if(selected>=0 && visible(selected))c.drawCircle(x(selected),y(value(sessions.get(selected),metric)),dp(5),paint);c.restore();
        }
        private float focusX(MotionEvent e){return (e.getX(0)+e.getX(1))/2;}
        private float span(MotionEvent e){return (float)Math.hypot(e.getX(0)-e.getX(1),e.getY(0)-e.getY(1));}
        private void moved(){selected=-1;selection.setText("Punkt antippen für Trainingsdetails");updateRange();invalidate();}
        @Override public boolean onTouchEvent(MotionEvent e){
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    downX=lastX=e.getX();downY=e.getY();dragging=false;multi=false;getParent().requestDisallowInterceptTouchEvent(true);return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if(e.getPointerCount()==2){multi=true;dragging=true;focus=focusX(e);span=span(e);}return true;
                case MotionEvent.ACTION_MOVE:
                    if(e.getPointerCount()==2){float nextFocus=focusX(e),nextSpan=span(e);if(span>dp(8) && nextSpan>dp(8))window.transform(nextSpan/span,(focus-left())/width(),(nextFocus-left())/width());focus=nextFocus;span=nextSpan;moved();return true;}
                    if(e.getPointerCount()>2)return true;
                    float dx=e.getX()-downX,dy=e.getY()-downY;
                    if(!dragging && Math.abs(dy)>slop && Math.abs(dy)>Math.abs(dx)){getParent().requestDisallowInterceptTouchEvent(false);return true;}
                    if(!dragging && Math.abs(dx)>slop)dragging=true;
                    if(dragging){window.pan((lastX-e.getX())/width());moved();}lastX=e.getX();return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if(e.getPointerCount()==2){int remaining=e.getActionIndex()==0?1:0;lastX=downX=e.getX(remaining);downY=e.getY(remaining);}return true;
                case MotionEvent.ACTION_UP:
                    if(!dragging && !multi){double best=dp(24);int found=-1;for(int i=0;i<sessions.size();i++)if(visible(i)){double distance=Math.hypot(x(i)-e.getX(),y(value(sessions.get(i),metric))-e.getY());if(distance<best){best=distance;found=i;}}
                        selected=found;if(found>=0){selection.setText(String.format(Locale.GERMANY,"%s · %.1f %s",LocalDate.ofEpochDay((long)Math.floor(day(sessions.get(found)))).format(DATE),value(sessions.get(found),metric),UNITS[metric]));}invalidate();performClick();
                    }else saveRange(true);
                    getParent().requestDisallowInterceptTouchEvent(false);return true;
                case MotionEvent.ACTION_CANCEL:
                    if(dragging)saveRange(true);getParent().requestDisallowInterceptTouchEvent(false);return true;
                default:return true;
            }
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
