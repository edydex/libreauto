// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Explicit user-selected destinations only. No location tracking, network, timer or database.
 * Each operation rereads its bounded backend so the phone editor and car picker stay consistent. */
public final class DestinationStore {
    public static final int MAX_SAVED = 50;
    public static final int MAX_RECENT = 20;
    public static final int MAX_ADDRESS_CHARS = 300;
    private static final int MAX_JSON_CHARS = 120_000;
    public enum Shortcut { NONE, HOME, WORK }
    public interface Backend { String read(); void write(String value); }
    public static final class Place {
        public final String id;
        public final String name;
        public final String address;
        public final double latitude;
        public final double longitude;
        public final Shortcut shortcut;
        private Place(String id, String name, String address, double latitude, double longitude, Shortcut shortcut) {
            this.id=id;this.name=name;this.address=address;this.latitude=latitude;this.longitude=longitude;this.shortcut=shortcut;
        }
    }
    public static final class Snapshot {
        public final List<Place> saved;
        public final List<Place> recent;
        private Snapshot(List<Place> saved,List<Place> recent) {
            this.saved=Collections.unmodifiableList(new ArrayList<>(saved));
            this.recent=Collections.unmodifiableList(new ArrayList<>(recent));
        }
        public Place shortcut(Shortcut shortcut) {
            if(shortcut==Shortcut.NONE)return null;
            for(Place place:saved)if(place.shortcut==shortcut)return place;
            return null;
        }
    }
    private final Backend backend;
    public DestinationStore(Backend backend) {
        if(backend==null)throw new IllegalArgumentException("Destination storage is required");
        this.backend=backend;
    }
    public synchronized Snapshot snapshot() { return load(); }

    /** Most-recent-first, deduplicated by selected coordinate to about one metre.
     * No current GPS location is recorded unless the user selected it as a destination. */
    public synchronized void recordSelection(String name,double latitude,double longitude) {
        name=validName(name);validateCoordinates(latitude,longitude);
        Snapshot current=load();ArrayList<Place> recent=new ArrayList<>();
        String coordinate=coordinate(latitude,longitude);String id=UUID.randomUUID().toString();
        for(Place place:current.recent)if(coordinate(place.latitude,place.longitude).equals(coordinate)) { id=place.id;break; }
        String address="";
        for(Place place:current.recent)if(coordinate(place.latitude,place.longitude).equals(coordinate))address=place.address;
        for(Place place:current.saved)if(coordinate(place.latitude,place.longitude).equals(coordinate))address=place.address;
        recent.add(new Place(id,name,address,latitude,longitude,Shortcut.NONE));
        for(Place place:current.recent)if(!coordinate(place.latitude,place.longitude).equals(coordinate)&&recent.size()<MAX_RECENT)recent.add(place);
        persist(current.saved,recent);
    }

    /** A full favorites list refuses a new place; it never silently evicts a saved address.
     * Reassigning Home/Work retains the former place as an ordinary saved place. */
    public synchronized Place save(String name,double latitude,double longitude,Shortcut shortcut) {
        return saveInternal(name,null,latitude,longitude,shortcut);
    }
    public synchronized Place save(String name,String address,double latitude,double longitude,Shortcut shortcut) {
        return saveInternal(name,validAddress(address),latitude,longitude,shortcut);
    }
    private Place saveInternal(String name,String address,double latitude,double longitude,Shortcut shortcut) {
        name=validName(name);validateCoordinates(latitude,longitude);
        if(shortcut==null)throw new IllegalArgumentException("Choose a place shortcut");
        Snapshot current=load();ArrayList<Place> saved=new ArrayList<>();
        String coordinate=coordinate(latitude,longitude);Place previous=null;
        for(Place place:current.saved)if(coordinate(place.latitude,place.longitude).equals(coordinate)) { previous=place;break; }
        if(previous==null&&current.saved.size()>=MAX_SAVED)throw new IllegalStateException("Saved places are full; remove one first");
        Place selected=new Place(previous==null?UUID.randomUUID().toString():previous.id,name,
            address!=null?address:previous==null?"":previous.address,latitude,longitude,shortcut);
        saved.add(selected);
        for(Place place:current.saved)if(!place.id.equals(selected.id))
            saved.add(shortcut!=Shortcut.NONE&&place.shortcut==shortcut
                ?new Place(place.id,place.name,place.address,place.latitude,place.longitude,Shortcut.NONE):place);
        persist(saved,current.recent);return selected;
    }
    public synchronized boolean rename(String id,String name,Shortcut shortcut) {
        Snapshot current=load();
        for(Place place:current.saved)if(place.id.equals(id))
            return update(id,name,place.address,place.latitude,place.longitude,shortcut);
        validName(name);return false;
    }
    /** Explicit phone edit keeps stable identity and never replaces another saved place's pin. */
    public synchronized boolean update(String id,String name,String address,double latitude,double longitude,Shortcut shortcut) {
        name=validName(name);address=validAddress(address);validateCoordinates(latitude,longitude);
        if(shortcut==null)return false;
        Snapshot current=load();Place selected=null;
        for(Place place:current.saved)if(place.id.equals(id))selected=place;
        if(selected==null)return false;
        String target=coordinate(latitude,longitude);
        for(Place place:current.saved)if(!place.id.equals(id)&&coordinate(place.latitude,place.longitude).equals(target))
            throw new IllegalArgumentException("Another saved place already uses this pin. Edit that place or choose another pin.");
        ArrayList<Place> saved=new ArrayList<>();
        for(Place place:current.saved) {
            if(place.id.equals(id))saved.add(new Place(place.id,name,address,latitude,longitude,shortcut));
            else if(shortcut!=Shortcut.NONE&&place.shortcut==shortcut)saved.add(new Place(place.id,place.name,place.address,place.latitude,place.longitude,Shortcut.NONE));
            else saved.add(place);
        }
        persist(saved,current.recent);return true;
    }
    public synchronized boolean removeSaved(String id) {
        Snapshot current=load();ArrayList<Place> saved=new ArrayList<>(current.saved);
        if(!saved.removeIf(place->place.id.equals(id)))return false;
        persist(saved,current.recent);return true;
    }
    public synchronized void clearRecent() { Snapshot current=load();persist(current.saved,Collections.emptyList()); }

    private Snapshot load() {
        try {
            String text=backend.read();
            if(text==null||text.isEmpty()||text.length()>MAX_JSON_CHARS)return empty();
            JSONObject root=new JSONObject(text);
            if(root.optInt("version",-1)!=1)return empty();
            return new Snapshot(parse(root.getJSONArray("saved"),MAX_SAVED,true),parse(root.getJSONArray("recent"),MAX_RECENT,false));
        } catch(Exception ignored) { return empty(); }
    }
    private static Snapshot empty() { return new Snapshot(Collections.emptyList(),Collections.emptyList()); }
    private static List<Place> parse(JSONArray array,int maximum,boolean saved) {
        ArrayList<Place> result=new ArrayList<>();Set<String> ids=new HashSet<>(),coordinates=new HashSet<>();
        Set<Shortcut> slots=new HashSet<>();
        // Bound work even if an imported/corrupted preference has an excessive entry count.
        for(int index=0;index<Math.min(array.length(),maximum*2);index++) {
            try {
                JSONObject value=array.getJSONObject(index);String id=value.getString("id");
                if(!UUID.fromString(id).toString().equals(id))continue;
                String name=validName(value.getString("name"));String address=value.has("address")?validAddress(value.getString("address")):"";double latitude=value.getDouble("latitude"),longitude=value.getDouble("longitude");
                validateCoordinates(latitude,longitude);
                Shortcut shortcut=saved?Shortcut.valueOf(value.getString("shortcut")):Shortcut.NONE;
                String key=coordinate(latitude,longitude);
                if(ids.contains(id)||coordinates.contains(key)||(shortcut!=Shortcut.NONE&&slots.contains(shortcut)))continue;
                ids.add(id);coordinates.add(key);if(shortcut!=Shortcut.NONE)slots.add(shortcut);
                result.add(new Place(id,name,address,latitude,longitude,shortcut));
                if(result.size()==maximum)break;
            } catch(Exception ignored) { /* Invalid entries never become route targets. */ }
        }
        return result;
    }
    private void persist(List<Place> saved,List<Place> recent) {
        try {
            JSONObject root=new JSONObject().put("version",1).put("saved",encode(saved)).put("recent",encode(recent));
            String text=root.toString();
            if(text.length()>MAX_JSON_CHARS)throw new IllegalStateException("Destination storage is full");
            backend.write(text);
        } catch(RuntimeException error) { throw error; }
        catch(Exception error) { throw new IllegalStateException("Could not save destinations"); }
    }
    private static JSONArray encode(List<Place> places)throws Exception {
        JSONArray result=new JSONArray();
        for(Place place:places)result.put(new JSONObject().put("id",place.id).put("name",place.name)
            .put("address",place.address).put("latitude",place.latitude).put("longitude",place.longitude).put("shortcut",place.shortcut.name()));
        return result;
    }
    private static String validName(String name) {
        if(name==null)throw new IllegalArgumentException("Enter a place name");
        String value=name.strip();
        if(value.isEmpty()||value.length()>200||value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Use a place name of 1 to 200 characters");
        return value;
    }
    private static String validAddress(String address) {
        if(address==null)throw new IllegalArgumentException("Enter an address or leave it blank for a chosen pin");
        String value=address.strip();
        if(value.length()>MAX_ADDRESS_CHARS||value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Use an address of up to 300 characters on one line");
        return value;
    }
    private static void validateCoordinates(double latitude,double longitude) {
        if(!Double.isFinite(latitude)||!Double.isFinite(longitude)||latitude< -90||latitude>90||longitude< -180||longitude>180)
            throw new IllegalArgumentException("This destination has invalid coordinates");
    }
    private static String coordinate(double latitude,double longitude) { return Math.round(latitude*100000)+":"+Math.round(longitude*100000); }
}
