// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import android.content.Context;
import android.content.SharedPreferences;
import org.openandroidauto.integration.SharedLibraryIntegration;

/** Local app-private destination JSON. Data changes only in response to a destination action. */
public final class DestinationPreferences {
    private DestinationPreferences() {}
    public static DestinationStore open(Context context) {
        DestinationStore.Backend shared = SharedLibraryIntegration.destinationBackend(context);
        if (shared != null) return new DestinationStore(shared);
        SharedPreferences prefs=context.getApplicationContext().getSharedPreferences("navigation-destinations",Context.MODE_PRIVATE);
        return new DestinationStore(new DestinationStore.Backend() {
            @Override public String read() { return prefs.getString("destinations",""); }
            @Override public void write(String value) { prefs.edit().putString("destinations",value).apply(); }
        });
    }
}
