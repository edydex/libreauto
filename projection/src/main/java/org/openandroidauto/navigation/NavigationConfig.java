package org.openandroidauto.navigation;

import android.content.Context;
import android.content.SharedPreferences;
import org.openandroidauto.BuildConfig;
import java.net.URI;

/** Provider selection is stored outside source so self-hosting never needs a rebuild. */
public final class NavigationConfig {
    public static final String PREFERENCES = "navigation-providers";
    public static final String DEFAULT_STYLE = "https://tiles.openfreemap.org/styles/liberty";
    public static final String DEFAULT_ROUTE = "https://valhalla1.openstreetmap.de/route";
    public static final String DEFAULT_SEARCH = "https://photon.komoot.io/api/";
    public static final String USER_AGENT = "OpenAutoClient/0.1 (research prototype; https://github.com/edydex/hibreak-auto-scale)";

    public final String styleUrl;
    public final String routeUrl;
    public final String searchUrl;
    public final boolean speakInstructions;
    public final int maximumMapFps;

    public AdaptiveMapFramePolicy createFramePolicy() {
        return new AdaptiveMapFramePolicy(AdaptiveMapFramePolicy.modeFromBuildValue(
                BuildConfig.OPEN_AUTO_MAP_FRAME_MODE), maximumMapFps);
    }

    public NavigationConfig(String style, String route, String search, boolean speak, int maximumMapFps) {
        styleUrl = validateUrl(style);
        routeUrl = validateUrl(route);
        searchUrl = validateUrl(search);
        speakInstructions = speak;
        this.maximumMapFps = Math.max(5, Math.min(30, maximumMapFps));
    }

    public static NavigationConfig load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        boolean adaptive = AdaptiveMapFramePolicy.modeFromBuildValue(BuildConfig.OPEN_AUTO_MAP_FRAME_MODE)
                == AdaptiveMapFramePolicy.Mode.ADAPTIVE30;
        return new NavigationConfig(prefs.getString("style_url", DEFAULT_STYLE),
                prefs.getString("route_url", DEFAULT_ROUTE), prefs.getString("search_url", DEFAULT_SEARCH),
                prefs.getBoolean("speak_instructions", true),
                prefs.getInt(adaptive ? "adaptive_maximum_map_fps" : "maximum_map_fps", adaptive ? 30 : 15));
    }

    public void save(Context context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString("style_url", styleUrl).putString("route_url", routeUrl)
                .putString("search_url", searchUrl).putBoolean("speak_instructions", speakInstructions)
                .putInt(AdaptiveMapFramePolicy.modeFromBuildValue(BuildConfig.OPEN_AUTO_MAP_FRAME_MODE)
                        == AdaptiveMapFramePolicy.Mode.ADAPTIVE30 ? "adaptive_maximum_map_fps" : "maximum_map_fps", maximumMapFps).apply();
    }

    private static String validateUrl(String value) {
        URI parsed;
        try { parsed = URI.create(value); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Invalid navigation provider URL"); }
        if (!"https".equals(parsed.getScheme()) || parsed.getHost() == null || parsed.getRawUserInfo() != null
                || parsed.getFragment() != null) {
            throw new IllegalArgumentException("Navigation provider must use HTTPS without embedded credentials");
        }
        return value;
    }
}
