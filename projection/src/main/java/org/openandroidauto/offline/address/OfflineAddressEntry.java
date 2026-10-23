// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline.address;

import java.util.Map;
import org.openandroidauto.offline.OfflineAreaPolicy;

/** A mapped OSM feature. Way/relation bounding-box centers are deliberately marked approximate. */
public final class OfflineAddressEntry {
    public final String osmKey,name,kind,houseTokens,searchText;
    public final double latitude,longitude;
    public final boolean approximate;
    private OfflineAddressEntry(String osmKey,String name,String kind,String houseTokens,String searchText,double latitude,double longitude,boolean approximate) {
        this.osmKey=osmKey;this.name=name;this.kind=kind;this.houseTokens=houseTokens;this.searchText=searchText;
        this.latitude=latitude;this.longitude=longitude;this.approximate=approximate;
    }
    public static OfflineAddressEntry from(String type,long id,double latitude,double longitude,Map<String,String> tags,OfflineAreaPolicy.Bounds bounds) {
        if(id<=0||!(type.equals("node")||type.equals("way")||type.equals("relation"))||!Double.isFinite(latitude)||!Double.isFinite(longitude)
                ||latitude<bounds.south||latitude>bounds.north||longitude<bounds.west||longitude>bounds.east)return null;
        String name=first(tags,"name","name:en","official_name","addr:housename");
        String house=first(tags,"addr:housenumber"),street=first(tags,"addr:street","addr:place");
        boolean address=!house.isEmpty()&&!street.isEmpty();
        boolean road=!name.isEmpty()&&tags.containsKey("highway");
        boolean poi=!name.isEmpty()&&(tags.containsKey("amenity")||tags.containsKey("shop")||tags.containsKey("tourism")
                ||tags.containsKey("leisure")||tags.containsKey("office")||tags.containsKey("healthcare")||tags.containsKey("place"));
        if(!address&&!road&&!poi)return null;
        String addressLine=address?(house+" "+street):first(tags,"addr:full");
        String city=first(tags,"addr:city"),state=first(tags,"addr:state"),postcode=first(tags,"addr:postcode");
        String locality=String.join(" ",city,state,postcode).trim();
        String label=name.isEmpty()?addressLine:name;
        if(!name.isEmpty()&&!addressLine.isEmpty())label+=" · "+addressLine;
        if(!locality.isEmpty())label+=", "+locality;
        String body=String.join(" ",name,first(tags,"name:en"),first(tags,"official_name"),first(tags,"alt_name"),first(tags,"short_name"),
                house,street,city,state,postcode,first(tags,"addr:unit"),first(tags,"addr:country"));
        String normalized=OfflineAddressText.normalize(body);
        if(normalized.isBlank())return null;
        return new OfflineAddressEntry(type+"/"+id,label,address?"address":road?"street":"place",OfflineAddressText.houseTokens(house),normalized,latitude,longitude,!type.equals("node"));
    }
    private static String first(Map<String,String> tags,String...keys) {
        for(String key:keys) { String value=tags.get(key);if(value!=null&&!value.isBlank())return value.trim(); }
        return "";
    }
}
