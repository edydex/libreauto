package org.openandroidauto.navigation;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import java.util.Locale;
import org.openandroidauto.integration.SharedLibraryIntegration;

/** Phone-only choice. No dialog is ever created from the projected map. */
public final class SpeedDisplayPreferences {
    private static final String KEY = "speed_units";
    private SpeedDisplayPreferences() {}
    public static SpeedUnits selected(Context context) {
        String shared = SharedLibraryIntegration.speedUnits(context);
        if (shared != null) return SpeedUnits.fromPreference(shared);
        return SpeedUnits.fromPreference(context.getSharedPreferences(NavigationConfig.PREFERENCES, Context.MODE_PRIVATE).getString(KEY, "auto"));
    }
    public static SpeedUnits effective(Context context) { return selected(context).resolved(Locale.getDefault()); }
    public static String summary(Context context) {
        SpeedUnits selected = selected(context);
        return selected == SpeedUnits.AUTOMATIC ? "Automatic (" + effective(context).symbol() + ")" : selected.symbol();
    }
    public static void openChooser(Activity activity) {
        openChooser(activity, () -> {});
    }
    public static void openChooser(Activity activity, Runnable changed) {
        SpeedUnits[] values = {SpeedUnits.AUTOMATIC, SpeedUnits.MPH, SpeedUnits.KPH};
        String[] labels = {"Automatic (" + SpeedUnits.AUTOMATIC.resolved(Locale.getDefault()).symbol() + ")", "Miles per hour (mph)", "Kilometers per hour (km/h)"};
        SpeedUnits current = selected(activity); int checked = current == SpeedUnits.AUTOMATIC ? 0 : current == SpeedUnits.MPH ? 1 : 2;
        new AlertDialog.Builder(activity).setTitle("Speed units").setSingleChoiceItems(labels, checked, (dialog, which) -> {
            if (!SharedLibraryIntegration.setSpeedUnits(activity, values[which].preference()))
                activity.getSharedPreferences(NavigationConfig.PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, values[which].preference()).apply();
            dialog.dismiss();
            changed.run();
        }).setNegativeButton("Cancel", null).show();
    }
}
