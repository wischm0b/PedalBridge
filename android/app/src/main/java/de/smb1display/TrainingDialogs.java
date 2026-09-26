package de.smb1display;
import android.content.Context;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.*;

final class TrainingDialogs {
 private static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
 private static String value(JSONObject s,String key,String unit){if(s==null || !s.has(key) || s.isNull(key))return "—";return String.format(Locale.GERMANY,"%.1f%s",s.optDouble(key),unit.isEmpty()?"":" "+unit);}
 private static String duration(double seconds){if(!Double.isFinite(seconds))return "—";long n=Math.max(0,(long)seconds);return n>=3600?String.format(Locale.GERMANY,"%d:%02d:%02d",n/3600,n/60%60,n%60):String.format(Locale.GERMANY,"%d:%02d",n/60,n%60);}
 static AppDialog details(Context c,JSONObject item,List<JSONObject> all,String date){
  boolean imported=item.optBoolean("imported");JSONObject row=item.optJSONObject("row"),s=imported?row:row.optJSONObject("stats");
  AppDialog dialog=new AppDialog(c,"TRAININGSARCHIV","Dein Training",date,R.drawable.nav_history);
  LinearLayout hero=new LinearLayout(c);hero.setOrientation(LinearLayout.VERTICAL);hero.setPadding(dp(c,18),dp(c,16),dp(c,18),dp(c,16));hero.setBackgroundResource(R.drawable.hero_background);hero.addView(AppDialog.text(c,"TRAININGSDAUER",11,R.color.hero_ink));TextView time=AppDialog.text(c,duration(s.optDouble(imported?"duration_seconds":"seconds",Double.NaN)),40,R.color.hero_ink);time.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));hero.addView(time);if(!imported)hero.addView(AppDialog.text(c,"Davon aktiv · "+duration(s.optDouble("active",Double.NaN)),13,R.color.hero_ink));dialog.body.addView(hero);
  metricPair(c,dialog,"Distanz",value(s,imported?"distance_km":"km","km"),"Energie",value(s,imported?"calories_kcal":"kcal","kcal"));
  metricPair(c,dialog,"Ø Leistung",value(s,imported?"average_power_watts":"power","W"),"Ø Trittfrequenz",value(s,imported?"average_cadence_rpm":"cadence","rpm"));
  metricPair(c,dialog,"Ø Tempo",value(s,imported?"average_speed_kmh":"speed","km/h"),"Ø Stufe",value(s,imported?"average_resistance_level":"resistance",""));
  LinearLayout intensity=dialog.group("Einordnung");int band=TrainingInsights.band(item,all);TextView label=AppDialog.text(c,TrainingInsights.label(band),18,TrainingInsights.color(band));label.setTypeface(null,android.graphics.Typeface.BOLD);intensity.addView(label);double rate=TrainingInsights.intensity(item);if(Double.isFinite(rate))intensity.addView(AppDialog.text(c,String.format(Locale.GERMANY,"%.1f kcal pro Minute",rate),14,R.color.ink));intensity.addView(AppDialog.text(c,"Relativ zu vergleichbaren Trainings derselben Quelle. Die Einordnung ist ab fünf Trainings von mindestens zehn Minuten verfügbar.",13,R.color.muted));
  if(!imported){LinearLayout peaks=dialog.group("Weitere Messwerte");dialog.row(peaks,"Max. Leistung",value(s,"maxPower","W"));dialog.row(peaks,"Max. Trittfrequenz",value(s,"maxCadence","rpm"));dialog.row(peaks,"Max. Tempo",value(s,"maxSpeed","km/h"));dialog.row(peaks,"Mechanische Arbeit",value(s,"workKj","kJ"));dialog.row(peaks,"Umdrehungen",value(s,"revolutions",""));}
  if(imported){LinearLayout extra=dialog.group("Weitere Messwerte");dialog.row(extra,"Ø Herzfrequenz",s.optDouble("heart_rate_bpm",0)>0?value(s,"heart_rate_bpm","bpm"):"Nicht verfügbar");String[][] fields={{"Aktive Zeit","active_seconds"},{"Pausen","pause_seconds"},{"Max. Leistung","max_power_watts"},{"Max. Trittfrequenz","max_cadence_rpm"},{"Max. Tempo","max_speed_kmh"},{"Mechanische Arbeit","mechanical_work_kj"},{"Umdrehungen","crank_revolutions"}};String[] units={"","","W","rpm","km/h","kJ",""};for(int i=0;i<fields.length;i++){String key=fields[i][1];if(s.has(key) && !s.isNull(key))dialog.row(extra,fields[i][0],i<2?duration(s.optDouble(key)):value(s,key,units[i]));}}
  LinearLayout recording=dialog.group("Aufzeichnung");recording.addView(AppDialog.text(c,imported?"Aus MyBodytone übernommen":row.optBoolean("rawSaved")?"Messverlauf auf dem Smartphone gespeichert":"Über deine Bridge aufgezeichnet",15,R.color.ink));
  if(imported){recording.addView(AppDialog.text(c,"Zeitpunkt wie in der Quelle angezeigt. Sekundenwerte sind für importierte Trainings nicht verfügbar.",13,R.color.muted));String flags=row.optString("quality_flags");if(flags.contains("heart_rate_zero"))recording.addView(AppDialog.text(c,"Puls 0 ist vermutlich ein fehlender Messwert.",13,R.color.muted));if(flags.contains("average_power_zero"))recording.addView(AppDialog.text(c,"Leistung 0 wurde unverändert übernommen und ist nicht bestätigt.",13,R.color.muted));JSONObject original=row.optJSONObject("original_display");if(original!=null){Button toggle=new Button(c);toggle.setText("Originalwerte anzeigen");toggle.setAllCaps(false);toggle.setTextColor(c.getColor(R.color.accent));toggle.setBackground(AppDialog.interactive(c,R.color.surface_alt,14,false));recording.addView(toggle);LinearLayout originals=new LinearLayout(c);originals.setOrientation(LinearLayout.VERTICAL);originals.setVisibility(View.GONE);recording.addView(originals);Iterator<String> keys=original.keys();while(keys.hasNext()){String key=keys.next();dialog.row(originals,key.equals("Personalwesen")?"Herzfrequenz (Quelle)":key,original.optString(key));}toggle.setOnClickListener(v->{boolean hidden=originals.getVisibility()!=View.VISIBLE;originals.setVisibility(hidden?View.VISIBLE:View.GONE);toggle.setText(hidden?"Originalwerte ausblenden":"Originalwerte anzeigen");});}}
  else recording.addView(AppDialog.text(c,s.optBoolean("estimated")?"Kalorien aus Leistung geschätzt":"Kalorien aus dem Energiezähler des Bikes",13,R.color.muted));
  return dialog;
 }
 private static void metricPair(Context c,AppDialog d,String a,String av,String b,String bv){LinearLayout row=new LinearLayout(c);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(c,10);d.body.addView(row,lp);metric(c,row,a,av,false);metric(c,row,b,bv,true);}
 private static void metric(Context c,LinearLayout row,String label,String value,boolean right){LinearLayout tile=new LinearLayout(c);tile.setOrientation(LinearLayout.VERTICAL);tile.setPadding(dp(c,14),dp(c,14),dp(c,14),dp(c,14));tile.setBackground(AppDialog.surface(c,R.color.surface_alt,18,false));tile.addView(AppDialog.text(c,label,12,R.color.muted));TextView number=AppDialog.text(c,value,23,R.color.ink);number.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));tile.addView(number);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(right)lp.leftMargin=dp(c,10);row.addView(tile,lp);}
 static AppDialog comparison(Context c,JSONObject reference,List<JSONObject> all){
  AppDialog d=new AppDialog(c,"TRAININGSVERGLEICH","Ähnliche Trainings","Vergleiche Einheiten mit ähnlicher Dauer und Stufe.",R.drawable.nav_history);
  String text=TrainingInsights.comparison(reference,all);
  for(String block:text.split("\\n\\n")){
   if(block.trim().isEmpty())continue;
   String heading=block.startsWith("Stufe ")?"Vergleichsrahmen":block.startsWith("Ø Trittfrequenz")?"Trittfrequenz im Vergleich":block.startsWith("Für einen Trend")?"Noch kein Trend":block.startsWith("Passende Trainings")?"Passende Trainings":"Zur Einordnung";
   LinearLayout group=d.group(heading);
   if(block.startsWith("Passende Trainings")){
    String[] lines=block.split("\\n");for(int i=1;i<lines.length;i++){String[] parts=lines[i].split(" · ",2);TextView date=AppDialog.text(c,parts[0],15,R.color.ink);date.setTypeface(null,android.graphics.Typeface.BOLD);date.setPadding(0,dp(c,8),0,0);group.addView(date);if(parts.length>1)group.addView(AppDialog.text(c,parts[1],13,R.color.muted));}
   }else group.addView(AppDialog.text(c,block.trim(),15,R.color.ink));
  }
  return d;
 }
}
