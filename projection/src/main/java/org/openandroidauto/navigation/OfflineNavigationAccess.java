// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import android.content.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.openandroidauto.offline.OfflineNavigationStore;
import org.openandroidauto.offline.OfflineRoutingEngine;
import org.openandroidauto.offline.address.OfflineAddressIndex;

/** Worker-only access to committed local packages. Never downloads or changes map connectivity. */
final class OfflineNavigationAccess {
    private final Context context;
    private final OfflineRoutingEngine routing = new OfflineRoutingEngine();
    OfflineNavigationAccess(Context context) { this.context = context.getApplicationContext(); }

    RoutePlan route(GeoPoint origin, NavigationProviders.Place destination, Float bearing)
            throws IOException, InterruptedException {
        active();
        if (!OfflineNavigationStore.available(context, origin, destination.point))
            throw new IOException("Download these areas on the phone to navigate offline");
        RoutePlan planned = routing.route(context, origin, destination, bearing);
        active();
        if (!OfflineNavigationStore.coversRoute(context, planned.points))
            throw new IOException("This route leaves your downloaded areas");
        return planned.withOffline();
    }

    static List<NavigationProviders.Place> search(Context context, String query, GeoPoint bias, int limit)
            throws IOException, InterruptedException {
        active();
        try {
            OfflineAddressIndex index = OfflineAddressIndex.open(context.getApplicationContext());
            if (!index.hasEntries() && index.areas().isEmpty())
                throw new IOException("Download places on the phone to search offline");
            List<OfflineAddressIndex.Result> found = index.search(query,
                    bias == null ? null : bias.latitude, bias == null ? null : bias.longitude, Math.min(20, Math.max(1, limit)));
            active();
            List<NavigationProviders.Place> places = new ArrayList<>();
            for (OfflineAddressIndex.Result result : found)
                places.add(new NavigationProviders.Place(result.name, new GeoPoint(result.latitude, result.longitude)));
            return List.copyOf(places);
        } catch (RuntimeException unavailable) {
            throw new IOException("Downloaded places are unavailable; open Offline maps on the phone", unavailable);
        }
    }
    void cancel() { routing.cancel(); }
    private static void active() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }
}
