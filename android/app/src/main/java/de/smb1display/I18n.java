package de.smb1display;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import org.json.JSONObject;

/** App-scoped language resources. Changing language never recreates BLE clients. */
final class I18n {
    private static Resources strings;
    private static Locale current=Locale.ENGLISH;
    static Context localize(Context base) {
        String language=base.getSharedPreferences("display",0).getString("language","en");
        Configuration config=new Configuration(base.getResources().getConfiguration());
        config.setLocale("de".equals(language)?Locale.GERMAN:Locale.ENGLISH);
        return base.createConfigurationContext(config);
    }
    static void init(Context context) {
        strings=localize(context).getResources();
        current=strings.getConfiguration().getLocales().get(0);
    }
    static String t(int id) { return strings.getString(id); }
    static String t(int id,Object... args) { return strings.getString(id,args); }
    static Locale locale() { return current; }
    static Resources resources() { return strings; }
    static DateTimeFormatter dateFormatter() {
        return DateTimeFormatter.ofPattern("de".equals(current.getLanguage())?"dd.MM.yyyy":"MMM d, yyyy",current);
    }
    static DateTimeFormatter tickFormatter(boolean months) {
        return DateTimeFormatter.ofPattern("de".equals(current.getLanguage())?(months?"MM.yyyy":"dd.MM.yy"):(months?"MMM yyyy":"MMM d"),current);
    }
    static String importDate(JSONObject row) {
        try { return LocalDateTime.parse(row.getString("datetime_local")).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT).withLocale(current)); }
        catch(Exception ignored) { return row.optString("displayed_datetime",row.optString("datetime_local",t(R.string.ui_date_unknown_182))); }
    }
    /** Retarget transient status fragments; never transform stored source data. */
    static String retranslate(String value,Resources previous) {
        ArrayList<Integer> ids=new ArrayList<>();for(int id:I18nIds.ALL)ids.add(id);
        ids.sort(Comparator.comparingInt((Integer id)->previous.getString(id).length()).reversed());
        ArrayList<String> replacements=new ArrayList<>();
        for(int id:ids){String old=previous.getString(id),next=t(id);if(old.equals(next) || old.contains("%") || old.isEmpty() || !value.contains(old))continue;
            String token="\u0001"+replacements.size()+"\u0002";replacements.add(next);value=value.replace(old,token);}
        for(int i=0;i<replacements.size();i++)value=value.replace("\u0001"+i+"\u0002",replacements.get(i));return value;
    }
    static String originalLabel(Context c,String value) {
        if("Dauer".equals(value))return t(R.string.source_duration);
        if("Kalorien".equals(value))return t(R.string.source_calories);
        if("datetime".equals(value))return t(R.string.source_datetime);
        if("sport".equals(value))return t(R.string.source_sport);
        if("en".equals(current.getLanguage())) {
            switch(value) {
                case "Geschwindigkeit (Durchschnitt)": return t(R.string.ui_avg_speed_431);
                case "Leistung (durchschnittlich)": return t(R.string.ui_avg_power_330);
                case "Trittfrequenz (durchschnittlich)": return t(R.string.ui_avg_cadence_430);
                case "Widerstand (durchschnittlich)": return t(R.string.ui_avg_resistance_433);
            }
        }
        Configuration de=new Configuration(c.getResources().getConfiguration());de.setLocale(Locale.GERMAN);
        return retranslate(value,c.createConfigurationContext(de).getResources());
    }
}
