// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline.address;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.os.CancellationSignal;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.openandroidauto.offline.OfflineAreaPolicy;

/** Local-only OSM address/POI search. All disk/import methods are synchronous worker operations. */
public final class OfflineAddressIndex {
    private static volatile OfflineAddressIndex instance;
    private final Store store;
    private final Object writeLock=new Object();
    public static OfflineAddressIndex open(Context context) {
        if(instance==null)synchronized(OfflineAddressIndex.class) { if(instance==null)instance=new OfflineAddressIndex(context.getApplicationContext()); }
        return instance;
    }
    private OfflineAddressIndex(Context context) { store=new Store(context);store.setWriteAheadLoggingEnabled(true); }
    public static final class Result {
        public final String osmKey,name,kind;
        public final double latitude,longitude;
        public final boolean approximate;
        Result(String key,String name,double latitude,double longitude,boolean approximate,String kind) {
            this.osmKey=key;this.name=name;this.latitude=latitude;this.longitude=longitude;this.approximate=approximate;this.kind=kind;
        }
    }
    public static final class AreaStatus {
        public final String areaId,sourceTimestamp;
        public final int entryCount;
        public final long downloadedAtMillis;
        public final OfflineAreaPolicy.Bounds bounds;
        AreaStatus(String areaId,int count,long downloadedAt,OfflineAreaPolicy.Bounds bounds,String timestamp) {
            this.areaId=areaId;entryCount=count;downloadedAtMillis=downloadedAt;this.bounds=bounds;sourceTimestamp=timestamp;
        }
    }
    /** Null means no completed import. A completed zero-entry extract remains explicitly zero. */
    public AreaStatus status(String areaId) throws IOException {
        try(Cursor cursor=store.getReadableDatabase().rawQuery("SELECT a.area_id,a.north,a.east,a.south,a.west,a.downloaded_at,a.source_timestamp,(SELECT count(*) FROM area_entries r WHERE r.area_id=a.area_id) FROM areas a WHERE a.area_id=?",new String[]{areaId})) {
            return cursor.moveToFirst()?area(cursor):null;
        } catch(SQLException error) { throw disk(error); }
    }
    public List<AreaStatus> areas() throws IOException {
        List<AreaStatus> found=new ArrayList<>();
        try(Cursor cursor=store.getReadableDatabase().rawQuery("SELECT a.area_id,a.north,a.east,a.south,a.west,a.downloaded_at,a.source_timestamp,(SELECT count(*) FROM area_entries r WHERE r.area_id=a.area_id) FROM areas a ORDER BY a.downloaded_at DESC",null)) {
            while(cursor.moveToNext())found.add(area(cursor));return Collections.unmodifiableList(found);
        } catch(SQLException error) { throw disk(error); }
    }
    public boolean hasEntries() {
        try(Cursor cursor=store.getReadableDatabase().rawQuery("SELECT 1 FROM area_entries LIMIT 1",null)) { return cursor.moveToFirst(); }
        catch(SQLException error) { return false; }
    }
    /** Replaces one area's references atomically; overlapping packages keep their shared features. */
    public AreaStatus replaceArea(String areaId,OfflineAreaPolicy.Bounds bounds,long downloadedAt,InputStream json,CancellationSignal cancel,OfflineAddressDownloader.Progress progress) throws IOException {
        UUID.fromString(areaId);OfflineAreaPolicy.validate(bounds.north,bounds.east,bounds.south,bounds.west);
        synchronized(writeLock) {
            SQLiteDatabase database;
            try { database=store.getWritableDatabase();database.beginTransactionNonExclusive(); }
            catch(SQLException error) { throw disk(error); }
            try {
                OfflineAddressParser.active(cancel);
                ContentValues area=new ContentValues();area.put("area_id",areaId);area.put("north",bounds.north);area.put("east",bounds.east);area.put("south",bounds.south);area.put("west",bounds.west);
                area.put("downloaded_at",downloadedAt);area.put("source_timestamp","");
                // UPDATE rather than REPLACE preserves membership until this transaction
                // explicitly resets it; rollback keeps the prior complete package intact.
                if(database.update("areas",area,"area_id=?",new String[]{areaId})==0)database.insertOrThrow("areas",null,area);
                database.delete("area_entries","area_id=?",new String[]{areaId});
                OfflineAddressInput input=new OfflineAddressInput(json);
                OfflineAddressParser.Parsed parsed;
                try(Inserts inserts=new Inserts(database,areaId,downloadedAt)) {
                    parsed=OfflineAddressParser.read(input,bounds,cancel,inserts::entry,progress);
                }
                OfflineAddressParser.active(cancel);
                ContentValues timestamp=new ContentValues();timestamp.put("source_timestamp",parsed.timestamp);
                database.update("areas",timestamp,"area_id=?",new String[]{areaId});
                database.execSQL(OfflineAddressSchema.DELETE_ORPHANS);
                int count;
                try(Cursor cursor=database.rawQuery("SELECT count(*) FROM area_entries WHERE area_id=?",new String[]{areaId})) { cursor.moveToFirst();count=cursor.getInt(0); }
                OfflineAddressParser.active(cancel);database.setTransactionSuccessful();
                return new AreaStatus(areaId,count,downloadedAt,bounds,parsed.timestamp);
            } catch(SQLException error) { throw disk(error); }
            finally { database.endTransaction(); }
        }
    }
    public void removeArea(String areaId) throws IOException {
        synchronized(writeLock) {
            SQLiteDatabase database;
            try { database=store.getWritableDatabase();database.beginTransactionNonExclusive(); }
            catch(SQLException error) { throw disk(error); }
            try { database.delete("areas","area_id=?",new String[]{areaId});database.execSQL(OfflineAddressSchema.DELETE_ORPHANS);database.setTransactionSuccessful(); }
            catch(SQLException error) { throw disk(error); }
            finally { database.endTransaction(); }
        }
    }
    /** No network, no Android Geocoder. Cursor contents are copied before returning an immutable list. */
    public List<Result> search(String text,Double biasLatitude,Double biasLongitude,int limit) throws IOException {
        OfflineAddressText.Query query=OfflineAddressText.query(text);if(query==null)return List.of();
        List<String> args=new ArrayList<>();args.add(query.match);String sql=OfflineAddressSchema.SEARCH;
        if(query.houseToken!=null) { sql+=" AND instr(e.house_tokens,?)>0";args.add(query.houseToken); }
        boolean bias=biasLatitude!=null&&biasLongitude!=null&&Double.isFinite(biasLatitude)&&Double.isFinite(biasLongitude)&&Math.abs(biasLatitude)<=90&&Math.abs(biasLongitude)<=180;
        if(bias) {
            double weight=Math.pow(Math.cos(Math.toRadians(biasLatitude)),2);
            sql+=" ORDER BY (e.latitude-?)*(e.latitude-?)+?*(e.longitude-?)*(e.longitude-?),e.name COLLATE NOCASE";
            args.add(biasLatitude.toString());args.add(biasLatitude.toString());args.add(Double.toString(weight));args.add(biasLongitude.toString());args.add(biasLongitude.toString());
        } else sql+=" ORDER BY CASE e.kind WHEN 'address' THEN 0 WHEN 'place' THEN 1 ELSE 2 END,e.name COLLATE NOCASE";
        sql+=" LIMIT "+Math.max(1,Math.min(limit,20));List<Result> found=new ArrayList<>();
        try(Cursor cursor=store.getReadableDatabase().rawQuery(sql,args.toArray(new String[0]))) {
            while(cursor.moveToNext())found.add(new Result(cursor.getString(0),cursor.getString(1),cursor.getDouble(2),cursor.getDouble(3),cursor.getInt(4)!=0,cursor.getString(5)));
            return Collections.unmodifiableList(found);
        } catch(SQLException error) { throw disk(error); }
    }
    private static final class Inserts implements AutoCloseable {
        private final SQLiteStatement insert,update,find,reference;
        private final String areaId;
        private final long downloadedAt;
        Inserts(SQLiteDatabase database,String areaId,long downloadedAt) {
            this.areaId=areaId;this.downloadedAt=downloadedAt;
            insert=database.compileStatement("INSERT OR IGNORE INTO entries(name,latitude,longitude,approximate,kind,house_tokens,search_text,downloaded_at,osm_key) VALUES(?,?,?,?,?,?,?,?,?)");
            update=database.compileStatement("UPDATE entries SET name=?,latitude=?,longitude=?,approximate=?,kind=?,house_tokens=?,search_text=?,downloaded_at=? WHERE osm_key=? AND downloaded_at<=?");
            find=database.compileStatement("SELECT _id FROM entries WHERE osm_key=?");
            reference=database.compileStatement("INSERT OR IGNORE INTO area_entries(area_id,entry_id) VALUES(?,?)");
        }
        void entry(OfflineAddressEntry value) {
            bind(insert,value);long id=insert.executeInsert();
            if(id<0) {
                bind(update,value);update.bindLong(10,downloadedAt);update.executeUpdateDelete();
                find.clearBindings();find.bindString(1,value.osmKey);id=find.simpleQueryForLong();
            }
            reference.clearBindings();reference.bindString(1,areaId);reference.bindLong(2,id);reference.executeInsert();
        }
        private void bind(SQLiteStatement statement,OfflineAddressEntry value) {
            statement.clearBindings();statement.bindString(1,value.name);statement.bindDouble(2,value.latitude);statement.bindDouble(3,value.longitude);
            statement.bindLong(4,value.approximate?1:0);statement.bindString(5,value.kind);statement.bindString(6,value.houseTokens);
            statement.bindString(7,value.searchText);statement.bindLong(8,downloadedAt);statement.bindString(9,value.osmKey);
        }
        @Override public void close() { insert.close();update.close();find.close();reference.close(); }
    }
    private static AreaStatus area(Cursor cursor) {
        return new AreaStatus(cursor.getString(0),cursor.getInt(7),cursor.getLong(5),OfflineAreaPolicy.validate(cursor.getDouble(1),cursor.getDouble(2),cursor.getDouble(3),cursor.getDouble(4)),cursor.getString(6));
    }
    private static IOException disk(SQLException error) { return new IOException("The offline address database could not be updated or read",error); }
    private static final class Store extends SQLiteOpenHelper {
        Store(Context context) { super(context,"offline-addresses-v1.sqlite",null,1); }
        @Override public void onConfigure(SQLiteDatabase database) { database.setForeignKeyConstraintsEnabled(true); }
        @Override public void onCreate(SQLiteDatabase database) { for(String sql:OfflineAddressSchema.CREATE)database.execSQL(sql); }
        @Override public void onUpgrade(SQLiteDatabase database,int oldVersion,int newVersion) { throw new IllegalStateException("Unsupported address database upgrade"); }
    }
}
