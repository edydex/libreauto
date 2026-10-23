// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** One explicit address lookup. No GPS, autocomplete, polling, storage or result auto-selection.
 * Offline queries use only committed local places. Online queries can fall back to those places.
 * Public Geocoder has no cancellation API: cancelled/late answers are discarded by the caller. */
public final class SavedPlaceResolver {
    private final Context context;
    private final NavigationProviders providers;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    public SavedPlaceResolver(Context context,NavigationConfig config) {
        this.context=context.getApplicationContext();providers=new NavigationProviders(config);
    }
    public void cancel() { cancelled.set(true);providers.cancel(); }
    private void active()throws InterruptedException { if(cancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedException(); }
    @SuppressWarnings("deprecation")
    public List<NavigationProviders.Place> find(String address)throws InterruptedException,IOException {
        List<String> queries=SavedAddressQueries.forAddress(address);
        for(String query:queries) {
            active();
            if(!DefaultInternetMonitor.validated(context)) {
                List<NavigationProviders.Place> local=local(query);active();
                if(!local.isEmpty())return local;
                continue;
            }
            if(Geocoder.isPresent()) {
                try {
                    List<Address> found=new Geocoder(context,Locale.getDefault()).getFromLocationName(query,5);
                    active();ArrayList<NavigationProviders.Place> result=new ArrayList<>();
                    if(found!=null)for(Address item:found.subList(0,Math.min(5,found.size()))) {
                        if(!item.hasLatitude()||!item.hasLongitude())continue;
                        String label=item.getMaxAddressLineIndex()>=0?item.getAddressLine(0):query;
                        if(label==null||label.isBlank())label=query;
                        try { result.add(new NavigationProviders.Place(label,new GeoPoint(item.getLatitude(),item.getLongitude()))); }
                        catch(IllegalArgumentException invalid) { /* Not a usable candidate. */ }
                    }
                    if(!result.isEmpty())return List.copyOf(result);
                } catch(IOException|RuntimeException unavailable) { active(); }
            }
            active();
            try {
                List<NavigationProviders.Place> result=providers.search(query,null);active();
                if(!result.isEmpty())return List.copyOf(result.subList(0,Math.min(5,result.size())));
            } catch(org.json.JSONException|IOException|RuntimeException unavailable) { active(); }
            List<NavigationProviders.Place> local=local(query);active();
            if(!local.isEmpty())return local;
        }
        return List.of();
    }
    private List<NavigationProviders.Place> local(String query)throws InterruptedException {
        try { return OfflineNavigationAccess.search(context,query,null,5); }
        catch(IOException unavailable) { active();return List.of(); }
    }
}
