package org.openandroidauto.navigation;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real JSON persistence with memory backend; no network, GPS or Android runtime. */
public class DestinationStoreTest {
    private static final class Memory implements DestinationStore.Backend {
        String value="";int writes;
        public String read(){return value;}
        public void write(String text){value=text;writes++;}
    }
    @Test public void savedAndRecentRoundTripAcrossFreshInstancesWithoutRecordingOnSave() {
        Memory memory=new Memory();DestinationStore first=new DestinationStore(memory);
        DestinationStore.Place saved=first.save("Home",10,20,DestinationStore.Shortcut.HOME);
        assertTrue(first.snapshot().recent.isEmpty());
        first.recordSelection("Selected place",11,21);
        DestinationStore.Snapshot restored=new DestinationStore(memory).snapshot();
        assertEquals(1,restored.saved.size());assertEquals(saved.id,restored.saved.get(0).id);
        assertEquals("Home",restored.shortcut(DestinationStore.Shortcut.HOME).name);
        assertEquals(10,restored.saved.get(0).latitude,0);
        assertEquals("Selected place",restored.recent.get(0).name);
    }
    @Test public void recentsAreBoundedDeduplicatedAndMostRecentFirst() {
        Memory memory=new Memory();DestinationStore store=new DestinationStore(memory);
        for(int i=0;i<30;i++)store.recordSelection("Place "+i,10+i*.01,20);
        assertEquals(20,store.snapshot().recent.size());assertEquals("Place 29",store.snapshot().recent.get(0).name);
        String id=store.snapshot().recent.get(19).id;
        store.recordSelection("Renamed selected place",10.10+0.000001,20.000001);
        assertEquals(20,store.snapshot().recent.size());assertEquals(id,store.snapshot().recent.get(0).id);
        assertEquals("Renamed selected place",store.snapshot().recent.get(0).name);
        assertEquals("Place 29",store.snapshot().recent.get(1).name);
    }
    @Test public void fullSavedListNeverEvictsAndExistingCoordinatesCanStillBeUpdated() {
        DestinationStore store=new DestinationStore(new Memory());
        for(int i=0;i<50;i++)store.save("Saved "+i,10+i*.01,20,DestinationStore.Shortcut.NONE);
        String oldest=store.snapshot().saved.get(49).id;
        try{store.save("One too many",30,30,DestinationStore.Shortcut.NONE);fail();}catch(IllegalStateException expected){}
        assertEquals(50,store.snapshot().saved.size());assertEquals(oldest,store.snapshot().saved.get(49).id);
        store.save("Same point renamed",10,20,DestinationStore.Shortcut.WORK);
        assertEquals(50,store.snapshot().saved.size());assertEquals(oldest,store.snapshot().saved.get(0).id);
        assertEquals("Same point renamed",store.snapshot().shortcut(DestinationStore.Shortcut.WORK).name);
        assertTrue(store.removeSaved(oldest));store.save("Replacement",30,30,DestinationStore.Shortcut.NONE);
        assertEquals(50,store.snapshot().saved.size());
    }
    @Test public void movingHomeOrWorkKeepsTheFormerPlaceAndSlotsRemainUnique() {
        DestinationStore store=new DestinationStore(new Memory());
        DestinationStore.Place old=store.save("Former home",10,20,DestinationStore.Shortcut.HOME);
        DestinationStore.Place current=store.save("Current home",11,21,DestinationStore.Shortcut.HOME);
        assertEquals(2,store.snapshot().saved.size());assertEquals(current.id,store.snapshot().shortcut(DestinationStore.Shortcut.HOME).id);
        assertEquals(DestinationStore.Shortcut.NONE,store.snapshot().saved.get(1).shortcut);
        assertTrue(store.rename(old.id,"Work",DestinationStore.Shortcut.WORK));
        assertEquals(old.id,store.snapshot().shortcut(DestinationStore.Shortcut.WORK).id);
        assertTrue(store.rename(current.id,"New work",DestinationStore.Shortcut.WORK));
        assertNull(store.snapshot().shortcut(DestinationStore.Shortcut.HOME));
        assertEquals(current.id,store.snapshot().shortcut(DestinationStore.Shortcut.WORK).id);
        assertFalse(store.rename("missing","No target",DestinationStore.Shortcut.NONE));
    }
    @Test public void clearRecentPreservesSavedShortcutsAndFreshReadersSeePhoneChanges() {
        Memory memory=new Memory();DestinationStore map=new DestinationStore(memory),phone=new DestinationStore(memory);
        DestinationStore.Place place=phone.save("Work",10,20,DestinationStore.Shortcut.WORK);
        assertEquals(place.id,map.snapshot().saved.get(0).id);
        map.recordSelection("Recent",11,21);phone.clearRecent();
        assertTrue(map.snapshot().recent.isEmpty());assertEquals(place.id,map.snapshot().shortcut(DestinationStore.Shortcut.WORK).id);
        phone.removeSaved(place.id);assertTrue(map.snapshot().saved.isEmpty());
    }
    @Test public void invalidCoordinatesOrNamesCannotChangePersistence() {
        Memory memory=new Memory();DestinationStore store=new DestinationStore(memory);
        for(double[] pair:new double[][]{{Double.NaN,1},{1,Double.POSITIVE_INFINITY},{91,0},{0,-181}}) {
            try{store.recordSelection("Invalid",pair[0],pair[1]);fail();}catch(IllegalArgumentException expected){}
            try{store.save("Invalid",pair[0],pair[1],DestinationStore.Shortcut.NONE);fail();}catch(IllegalArgumentException expected){}
        }
        for(String name:new String[]{"", "   ", "line\nbreak", "x".repeat(201)}) {
            try{store.recordSelection(name,10,20);fail();}catch(IllegalArgumentException expected){}
        }
        assertEquals(0,memory.writes);assertTrue(store.snapshot().saved.isEmpty());
    }
    @Test public void malformedOversizedOrUnsupportedJsonNeverCreatesARouteTarget() {
        Memory memory=new Memory();DestinationStore store=new DestinationStore(memory);
        for(String text:new String[]{"not-json","{}","{\"version\":2,\"saved\":[],\"recent\":[]}","x".repeat(120001)}) {
            memory.value=text;assertTrue(store.snapshot().saved.isEmpty());assertTrue(store.snapshot().recent.isEmpty());
        }
        assertEquals(0,memory.writes);
    }
    @Test public void invalidEntriesDuplicateSourcesCoordinatesAndShortcutsAreDiscarded()throws Exception {
        Memory memory=new Memory();DestinationStore store=new DestinationStore(memory);
        store.save("Home",10,20,DestinationStore.Shortcut.HOME);
        JSONObject root=new JSONObject(memory.value);JSONArray saved=root.getJSONArray("saved");
        JSONObject first=saved.getJSONObject(0);
        saved.put(new JSONObject(first.toString()));
        saved.put(new JSONObject(first.toString()).put("id",java.util.UUID.randomUUID().toString()).put("latitude",11));
        saved.put(new JSONObject(first.toString()).put("id","bad-id").put("latitude",12).put("shortcut","NONE"));
        saved.put(new JSONObject(first.toString()).put("id",java.util.UUID.randomUUID().toString()).put("latitude",13).put("longitude",999).put("shortcut","NONE"));
        memory.value=root.toString();assertEquals(1,store.snapshot().saved.size());
    }
    @Test public void snapshotsCannotMutateStoredListsAndReadOnlyInspectionDoesNotWrite() {
        Memory memory=new Memory();DestinationStore store=new DestinationStore(memory);
        store.save("Saved",10,20,DestinationStore.Shortcut.NONE);int writes=memory.writes;
        DestinationStore.Snapshot snapshot=store.snapshot();
        try{snapshot.saved.clear();fail();}catch(UnsupportedOperationException expected){}
        assertEquals(1,store.snapshot().saved.size());assertEquals(writes,memory.writes);
    }
    @Test public void failedBackendWriteDoesNotFabricateSavedState() {
        DestinationStore store=new DestinationStore(new DestinationStore.Backend(){public String read(){return "";}public void write(String text){throw new IllegalStateException("synthetic");}});
        try{store.save("Home",10,20,DestinationStore.Shortcut.HOME);fail();}catch(IllegalStateException expected){}
        assertTrue(store.snapshot().saved.isEmpty());
    }
}
