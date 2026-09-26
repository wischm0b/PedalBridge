package de.smb1display;

import org.json.JSONObject;
import java.util.*;

/** Descriptive comparisons only. No physiological zones or inferred watts. */
final class TrainingInsights {
    static double field(JSONObject item,String importedKey,String nativeKey){
        JSONObject row=item.optJSONObject("row");if(row==null)return Double.NaN;
        JSONObject stats=item.optBoolean("imported")?row:row.optJSONObject("stats");
        return stats==null?Double.NaN:stats.optDouble(item.optBoolean("imported")?importedKey:nativeKey,Double.NaN);
    }
    static double duration(JSONObject i){return field(i,"duration_seconds","seconds");}
    static double level(JSONObject i){return field(i,"average_resistance_level","resistance");}
    static double cadence(JSONObject i){return field(i,"average_cadence_rpm","cadence");}
    static String source(JSONObject i){
        JSONObject r=i.optJSONObject("row");
        return i.optBoolean("imported")?"import:"+(r==null?"":r.optString("source","mybodytone"))+":"+(r==null?"":r.optString("device_address","unknown")):i.optString("deviceKey","esp:unknown");
    }
    static double intensity(JSONObject i){double seconds=duration(i),kcal=field(i,"calories_kcal","kcal");return seconds>=600 && Double.isFinite(kcal) && kcal>0?kcal*60/seconds:Double.NaN;}
    static int band(JSONObject item,List<JSONObject> all){
        double target=intensity(item);if(!Double.isFinite(target))return -1;
        int less=0,equal=0,count=0;for(JSONObject other:all){double v=intensity(other);if(!source(item).equals(source(other)) || !Double.isFinite(v))continue;count++;if(v<target-1e-6)less++;else if(Math.abs(v-target)<=1e-6)equal++;}
        if(count<5)return -1;double percentile=(less+equal*.5)/count;return percentile<1.0/3?0:percentile>2.0/3?2:1;
    }
    static String label(int band){return band<0?"Intensität nicht eingeordnet":new String[]{"Relativ niedriger","Im mittleren Bereich","Relativ höher"}[band];}
    static int color(int band){return band<0?R.color.muted:new int[]{R.color.intensity_low,R.color.intensity_mid,R.color.intensity_high}[band];}
    static List<JSONObject> similar(JSONObject reference,List<JSONObject> all){
        ArrayList<JSONObject> matches=new ArrayList<>();double level=level(reference),seconds=duration(reference);
        if(!Double.isFinite(level) || seconds<600 || !Double.isFinite(seconds))return matches;
        for(JSONObject other:all){double otherLevel=level(other),otherDuration=duration(other);
            if(source(reference).equals(source(other)) && Double.isFinite(otherLevel) && otherDuration>=600 && Math.abs(level-otherLevel)<=1 && Math.abs(otherDuration-seconds)<=seconds*.2)matches.add(other);
        }
        matches.sort(Comparator.comparing(i->i.optString("order")));return matches;
    }
    static String comparison(JSONObject reference,List<JSONObject> all){
        if(!Double.isFinite(level(reference)))return "Für diese Sitzung fehlt eine durchschnittliche Stufe. Ein Vergleich über Stufe und Dauer ist deshalb noch nicht möglich.";
        if(!(duration(reference)>=600))return "Für den Vergleich werden Trainings ab 10 Minuten verwendet.";
        List<JSONObject> matches=similar(reference,all);
        StringBuilder text=new StringBuilder(String.format(Locale.GERMANY,"Stufe %.1f ± 1 · Dauer %.0f min ± 20 %%\nGleiche Datenquelle / bekannte Bike-Zuordnung\n%d passende Trainings einschließlich Referenz\n\n",level(reference),duration(reference)/60,matches.size()));
        ArrayList<JSONObject> valid=new ArrayList<>();for(JSONObject row:matches)if(Double.isFinite(cadence(row)) && cadence(row)>0 && Double.isFinite(ProgressChart.day(row)))valid.add(row);
        if(valid.size()>=6){int n=Math.min(5,valid.size()/2);double early=0,recent=0;for(int i=0;i<n;i++){early+=cadence(valid.get(i));recent+=cadence(valid.get(valid.size()-n+i));}early/=n;recent/=n;
            text.append(String.format(Locale.GERMANY,"Ø Trittfrequenz\nFrüheste %d: %.1f rpm\nNeueste %d: %.1f rpm\nVeränderung: %+.1f %%\n\n",n,early,n,recent,100*(recent/early-1)));
        }else text.append("Für einen Trend werden mindestens 6 passende, datierte Trainings mit Trittfrequenz benötigt.\n\n");
        text.append("Höhere Trittfrequenz unter ähnlichen Bedingungen ist ein Leistungshinweis, kein Beweis für bessere Fitness. Pausen und Intervalle fehlen in den Import-Durchschnitten.\n");
        if(reference.optBoolean("imported"))text.append("MyBodytone nennt kein sicher zugeordnetes Bike. Der Vergleich setzt dasselbe Bike und unveränderte Stufen voraus.\n");
        text.append("\nPassende Trainings (neueste zuerst):\n");
        for(int i=matches.size()-1;i>=Math.max(0,matches.size()-12);i--){JSONObject m=matches.get(i);String date=m.optString("order");if(date.length()>10)date=date.substring(0,10);text.append(String.format(Locale.GERMANY,"%s · %.0f min · Stufe %.1f · %s rpm\n",date.isEmpty()?"Datum unbekannt":date,duration(m)/60,level(m),Double.isFinite(cadence(m))?String.format(Locale.GERMANY,"%.1f",cadence(m)):"—"));}
        if(matches.size()>12)text.append("Weitere passende Trainings sind im Trend berücksichtigt.");return text.toString();
    }
}
