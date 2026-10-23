// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Spatial selection returns every overlapping owned area; the phone asks the user which one. */
public final class OfflineAreaSelection {
    private OfflineAreaSelection() {}
    public static List<Integer> containing(List<OfflineAreaPolicy.Bounds> bounds,double latitude,double longitude) {
        List<Integer> found=new ArrayList<>();
        if(!Double.isFinite(latitude)||!Double.isFinite(longitude))return found;
        for(int index=0;index<bounds.size();index++) {
            OfflineAreaPolicy.Bounds area=bounds.get(index);
            if(latitude>=area.south&&latitude<=area.north&&longitude>=area.west&&longitude<=area.east)found.add(index);
        }
        found.sort(Comparator.comparingDouble(index -> OfflineAreaPolicy.widthKilometers(bounds.get(index))
                *OfflineAreaPolicy.heightKilometers(bounds.get(index))));
        return found;
    }
}
