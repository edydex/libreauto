// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Compatibility entry for existing car Save/Recent actions. Uses the normal phone editor. */
public final class DestinationManagerActivity extends Activity {
    public static void open(Context context) { SavedPlacesActivity.open(context); }
    public static void save(Context context,String name,double latitude,double longitude) {
        SavedPlacesActivity.saveCandidate(context,name,latitude,longitude);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(state==null) {
            Intent intent=getIntent();String name=intent==null?null:intent.getStringExtra("destination-name");
            double latitude=intent==null?Double.NaN:intent.getDoubleExtra("destination-latitude",Double.NaN);
            double longitude=intent==null?Double.NaN:intent.getDoubleExtra("destination-longitude",Double.NaN);
            if(name!=null&&name.length()<=200&&Double.isFinite(latitude)&&Double.isFinite(longitude)&&Math.abs(latitude)<=90&&Math.abs(longitude)<=180)
                save(this,name,latitude,longitude);
            else open(this);
        }
        finish();
    }
}
