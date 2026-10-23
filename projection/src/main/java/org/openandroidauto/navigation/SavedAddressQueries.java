// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Bounded search-only variants. The caller retains the full address, including its unit. */
public final class SavedAddressQueries {
    private SavedAddressQueries() {}
    private static final Pattern UNIT=Pattern.compile("(?i)\\s+(?:#\\s*|(?:suite|ste|unit|apt|apartment)\\.?\\s+)[A-Za-z0-9-]+(?=\\s*(?:,|$))");
    public static List<String> forAddress(String address) {
        if(address==null)throw new IllegalArgumentException("Enter an address to find");
        String value=address.strip();
        if(value.length()<2||value.length()>DestinationStore.MAX_ADDRESS_CHARS||value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Enter an address of 2 to 300 characters on one line");
        ArrayList<String> result=new ArrayList<>();result.add(value);
        String building=UNIT.matcher(value).replaceAll("").strip();
        if(!building.equals(value)&&building.length()>=2)result.add(building);
        return List.copyOf(result);
    }
}
