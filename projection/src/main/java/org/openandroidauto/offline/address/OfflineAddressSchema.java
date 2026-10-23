// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline.address;

/** SQL shared by the Android store and host SQLite contract checks. */
public final class OfflineAddressSchema {
    private OfflineAddressSchema() {}
    public static final String[] CREATE={
        "CREATE TABLE areas(area_id TEXT PRIMARY KEY,north REAL NOT NULL,east REAL NOT NULL,south REAL NOT NULL,west REAL NOT NULL,downloaded_at INTEGER NOT NULL,source_timestamp TEXT NOT NULL)",
        "CREATE TABLE entries(_id INTEGER PRIMARY KEY,osm_key TEXT NOT NULL UNIQUE,name TEXT NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,approximate INTEGER NOT NULL,kind TEXT NOT NULL,house_tokens TEXT NOT NULL,search_text TEXT NOT NULL,downloaded_at INTEGER NOT NULL)",
        "CREATE TABLE area_entries(area_id TEXT NOT NULL REFERENCES areas(area_id) ON DELETE CASCADE,entry_id INTEGER NOT NULL REFERENCES entries(_id) ON DELETE CASCADE,PRIMARY KEY(area_id,entry_id))",
        "CREATE INDEX area_entries_entry ON area_entries(entry_id)",
        "CREATE VIRTUAL TABLE entry_fts USING fts4(search_text,tokenize=unicode61)",
        "CREATE TRIGGER entries_insert AFTER INSERT ON entries BEGIN INSERT INTO entry_fts(docid,search_text) VALUES(new._id,new.search_text); END",
        "CREATE TRIGGER entries_update AFTER UPDATE ON entries BEGIN UPDATE entry_fts SET search_text=new.search_text WHERE docid=old._id; END",
        "CREATE TRIGGER entries_delete AFTER DELETE ON entries BEGIN DELETE FROM entry_fts WHERE docid=old._id; END"
    };
    public static final String DELETE_ORPHANS="DELETE FROM entries WHERE NOT EXISTS(SELECT 1 FROM area_entries WHERE area_entries.entry_id=entries._id)";
    public static final String SEARCH="SELECT e.osm_key,e.name,e.latitude,e.longitude,e.approximate,e.kind FROM entry_fts JOIN entries e ON e._id=entry_fts.docid WHERE entry_fts MATCH ? AND EXISTS(SELECT 1 FROM area_entries a WHERE a.entry_id=e._id)";
}
