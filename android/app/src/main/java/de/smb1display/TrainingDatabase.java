package de.smb1display;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Durable archive: never remove a local session because an ESP evicted it. */
final class TrainingDatabase extends SQLiteOpenHelper {
    TrainingDatabase(Context context) { super(context,"training.db",null,2); }
    @Override public void onConfigure(SQLiteDatabase db) { db.execSQL("PRAGMA synchronous=FULL"); }
    @Override public void onCreate(SQLiteDatabase db) {
        createImports(db);
        db.execSQL("CREATE TABLE bridges (bridge TEXT PRIMARY KEY, archive TEXT NOT NULL)");
        db.execSQL("CREATE TABLE snapshots (archive TEXT PRIMARY KEY, json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE sessions (archive TEXT NOT NULL,id INTEGER NOT NULL,json TEXT NOT NULL,PRIMARY KEY(archive,id))");
        db.execSQL("CREATE TABLE raw (archive TEXT NOT NULL,id INTEGER NOT NULL,checksum INTEGER NOT NULL,bytes BLOB NOT NULL,PRIMARY KEY(archive,id))");
    }
    private void createImports(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS imports (key TEXT PRIMARY KEY,json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS import_documents (checksum INTEGER PRIMARY KEY,json TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if(oldVersion<2) createImports(db);
    }
    boolean hasImport(long checksum) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM import_documents WHERE checksum=?",new String[]{Long.toString(checksum)})) {return c.moveToFirst();}
    }
    int importBodytone(byte[] bytes) throws JSONException {
        JSONObject document=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        if(!"smb1-training-import".equals(document.optString("format")) || document.optInt("schema_version")!=1)
            throw new JSONException(I18n.t(R.string.ui_unsupported_mybodytone_import_file_376));
        JSONArray rows=document.getJSONArray("sessions");
        if(rows.length()>10000) throw new JSONException(I18n.t(R.string.ui_too_many_sessions_377));
        java.util.HashSet<String> keys=new java.util.HashSet<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i);String key=row.getString("import_key");
            if(!key.equals("mybodytone:"+row.getString("source_session_id")) || !keys.add(key)) throw new JSONException(I18n.t(R.string.ui_invalid_or_duplicate_import_id_381));
            for(String field:new String[]{"duration_seconds","distance_km","calories_kcal"})
                if(!row.isNull(field) && (!Double.isFinite(row.getDouble(field)) || row.getDouble(field)<0)) throw new JSONException(I18n.t(R.string.ui_invalid_value_382)+field);
        }
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();int added=0;
        try {
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i);ContentValues values=new ContentValues();values.put("key",row.getString("import_key"));values.put("json",row.toString());
                try(Cursor c=db.rawQuery("SELECT json FROM imports WHERE key=?",new String[]{row.getString("import_key")})) {
                    if(c.moveToFirst()) continue; // Stable source ID: reimport never duplicates a ride.
                }
                if(db.insertOrThrow("imports",null,values)<0) throw new IllegalStateException(I18n.t(R.string.ui_import_failed_387));added++;
            }
            ContentValues doc=new ContentValues();doc.put("checksum",checksum(bytes));doc.put("json",document.toString());
            if(db.insertWithOnConflict("import_documents",null,doc,SQLiteDatabase.CONFLICT_REPLACE)<0) throw new IllegalStateException(I18n.t(R.string.ui_import_file_not_backed_up_390));
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        return added;
    }
    JSONArray importedSessions() throws JSONException {
        java.util.ArrayList<JSONObject> rows=new java.util.ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT json FROM imports",null)) {while(c.moveToNext())rows.add(new JSONObject(c.getString(0)));}
        rows.sort((a,b)->b.optString("datetime_local").compareTo(a.optString("datetime_local")));
        JSONArray result=new JSONArray();for(JSONObject row:rows)result.put(row);return result;
    }
    void savePage(String bridge,JSONObject page) throws JSONException {
        String archive=page.optString("archiveId",bridge);
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues map=new ContentValues(); map.put("bridge",bridge); map.put("archive",archive);
            if(db.insertWithOnConflict("bridges",null,map,SQLiteDatabase.CONFLICT_REPLACE)<0) throw new IllegalStateException("Archive mapping write failed");
            ContentValues header=new ContentValues(); header.put("archive",archive); header.put("json",page.toString());
            if(db.insertWithOnConflict("snapshots",null,header,SQLiteDatabase.CONFLICT_REPLACE)<0) throw new IllegalStateException("Archive metadata write failed");
            JSONArray sessions=page.getJSONArray("sessions");
            for(int i=0;i<sessions.length();i++) {
                JSONObject session=sessions.getJSONObject(i);
                ContentValues row=new ContentValues(); row.put("archive",archive); row.put("id",session.getLong("id")); row.put("json",session.toString());
                if(db.insertWithOnConflict("sessions",null,row,SQLiteDatabase.CONFLICT_REPLACE)<0) throw new IllegalStateException("Session write failed");
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    JSONObject load(String bridge) throws JSONException {
        SQLiteDatabase db=getReadableDatabase();
        try(Cursor c=db.rawQuery("SELECT s.archive,s.json FROM snapshots s JOIN bridges b ON s.archive=b.archive WHERE b.bridge=?",new String[]{bridge})) {
            if(!c.moveToFirst()) return null;
            String archive=c.getString(0); JSONObject result=new JSONObject(c.getString(1)); JSONArray sessions=new JSONArray();
            try(Cursor rows=db.rawQuery("SELECT s.json,r.id FROM sessions s LEFT JOIN raw r ON r.archive=s.archive AND r.id=s.id WHERE s.archive=? ORDER BY s.id DESC",new String[]{archive})) {
                while(rows.moveToNext()) {
                    JSONObject session=new JSONObject(rows.getString(0)); session.put("rawSaved",!rows.isNull(1)); sessions.put(session);
                }
            }
            result.put("sessions",sessions); return result;
        }
    }
    static long checksum(byte[] bytes) {
        long h=2166136261L; for(byte b:bytes) h=((h^(b&255))*16777619L)&0xffffffffL; return h;
    }
    void saveRaw(String archive,long id,byte[] bytes,long checksum) {
        if(checksum(bytes)!=checksum) throw new IllegalArgumentException("Checksum mismatch");
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues row=new ContentValues(); row.put("archive",archive); row.put("id",id); row.put("checksum",checksum); row.put("bytes",bytes);
            if(db.insertWithOnConflict("raw",null,row,SQLiteDatabase.CONFLICT_REPLACE)<0) throw new IllegalStateException("Recording write failed");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        // Confirm the committed record, not just a successful INSERT callback.
        try(Cursor c=db.rawQuery("SELECT checksum,bytes FROM raw WHERE archive=? AND id=?",new String[]{archive,Long.toString(id)})) {
            if(!c.moveToFirst() || c.getLong(0)!=checksum || !java.util.Arrays.equals(bytes,c.getBlob(1)))
                throw new IllegalStateException("Stored recording verification failed");
        }
    }
    void exportZip(OutputStream stream) throws Exception {
        SQLiteDatabase db=getReadableDatabase();
        try(ZipOutputStream zip=new ZipOutputStream(stream)) {
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write(("SMB1 archive backup v1. Summary JSON retains ESP values and device metadata.\n"
                    +"Raw .bin: 16-byte header (magic uint32=0x31574152, session uint32, start UTC uint32, record bytes uint16=24, flags uint16).\n"
                    +"24-byte records: elapsed ms uint32, power int16 W, cadence uint16 x10 rpm, speed uint16 x100 km/h, bike kcal uint16, distance uint32 m, kcal uint32 x1000, flags uint16, reserved uint16. All little-endian.\n"
                    +"Record flags: fresh=1, power=2, cadence=4, speed=8, bike energy=16, estimated calories=32. Header flags: gaps=1, interrupted=2.\n")
                    .getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            try(Cursor c=db.rawQuery("SELECT checksum,json FROM import_documents",null)) {
                while(c.moveToNext()) {zip.putNextEntry(new ZipEntry("imports/mybodytone-"+c.getLong(0)+".json"));zip.write(c.getString(1).getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
            }
            try(Cursor c=db.rawQuery("SELECT archive,json FROM snapshots",null)) {
                while(c.moveToNext()) {
                    zip.putNextEntry(new ZipEntry(c.getString(0)+"/metadata.json")); zip.write(c.getString(1).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                }
            }
            try(Cursor c=db.rawQuery("SELECT archive,id,json FROM sessions ORDER BY archive,id",null)) {
                while(c.moveToNext()) {
                    zip.putNextEntry(new ZipEntry(c.getString(0)+"/sessions/"+c.getLong(1)+".json")); zip.write(c.getString(2).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                }
            }
            try(Cursor c=db.rawQuery("SELECT archive,id,bytes FROM raw ORDER BY archive,id",null)) {
                while(c.moveToNext()) {
                    zip.putNextEntry(new ZipEntry(c.getString(0)+"/raw/"+c.getLong(1)+".bin")); zip.write(c.getBlob(2)); zip.closeEntry();
                }
            }
        }
    }
}
