// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class DestinationAddressTest {
    private static class Memory implements DestinationStore.Backend { String text="";int writes;public String read(){return text;}public void write(String value){text=value;writes++;} }
    @Test public void addressAndNicknameRoundTripAndLegacyRecordsRemainReadable()throws Exception {
        Memory memory=new Memory();DestinationStore store=new DestinationStore(memory);
        DestinationStore.Place p=store.save("Church","123 Example Ave #7109, Example City",10,20,DestinationStore.Shortcut.NONE);
        DestinationStore.Place restored=new DestinationStore(memory).snapshot().saved.get(0);
        assertEquals(p.id,restored.id);assertEquals("Church",restored.name);assertEquals("123 Example Ave #7109, Example City",restored.address);
        JSONObject json=new JSONObject(memory.text);json.getJSONArray("saved").getJSONObject(0).remove("address");memory.text=json.toString();
        assertEquals("",store.snapshot().saved.get(0).address);assertEquals(p.id,store.snapshot().saved.get(0).id);
    }
    @Test public void legacyRenameAndCoordinateSavePreserveFullAddressAndSelectionCopiesItToRecent() {
        Memory memory=new Memory();DestinationStore store=new DestinationStore(memory);
        DestinationStore.Place p=store.save("Church","123 Example Ave #7109",10,20,DestinationStore.Shortcut.HOME);
        assertTrue(store.rename(p.id,"Nickname",DestinationStore.Shortcut.WORK));
        store.save("From car",10,20,DestinationStore.Shortcut.WORK);
        assertEquals(1,store.snapshot().saved.size());assertEquals("123 Example Ave #7109",store.snapshot().saved.get(0).address);
        store.recordSelection("From car",10,20);assertEquals("123 Example Ave #7109",store.snapshot().recent.get(0).address);
    }
    @Test public void explicitEditKeepsIdAndReassignsShortcutWithoutLosingOtherAddress() {
        DestinationStore store=new DestinationStore(new Memory());
        DestinationStore.Place a=store.save("A","Address A",10,20,DestinationStore.Shortcut.HOME);
        DestinationStore.Place b=store.save("B","Address B",11,21,DestinationStore.Shortcut.NONE);
        assertTrue(store.update(b.id,"New B","Changed address",12,22,DestinationStore.Shortcut.HOME));
        assertEquals(b.id,store.snapshot().shortcut(DestinationStore.Shortcut.HOME).id);
        DestinationStore.Place old=store.snapshot().saved.stream().filter(p->p.id.equals(a.id)).findFirst().orElseThrow();
        assertEquals("Address A",old.address);assertEquals(DestinationStore.Shortcut.NONE,old.shortcut);
    }
    @Test public void staleIdCollisionAndInvalidAddressCannotOverwriteSavedPlaces() {
        Memory memory=new Memory();DestinationStore store=new DestinationStore(memory);
        DestinationStore.Place a=store.save("A","Address A",10,20,DestinationStore.Shortcut.NONE);
        DestinationStore.Place b=store.save("B","Address B",11,21,DestinationStore.Shortcut.NONE);int writes=memory.writes;
        assertFalse(store.update("missing","Wrong","Wrong",12,22,DestinationStore.Shortcut.NONE));
        try{store.update(b.id,"Wrong","Wrong",10,20,DestinationStore.Shortcut.NONE);fail();}catch(IllegalArgumentException expected){}
        for(String address:new String[]{null,"line\nbreak","x".repeat(301)})try{store.update(a.id,"Wrong",address,10,20,DestinationStore.Shortcut.NONE);fail();}catch(IllegalArgumentException expected){}
        assertEquals(writes,memory.writes);assertEquals("A",store.snapshot().saved.get(0).id.equals(a.id)?store.snapshot().saved.get(0).name:store.snapshot().saved.get(1).name);
    }
}
