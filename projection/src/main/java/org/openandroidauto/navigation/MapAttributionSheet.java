// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import android.content.Context;
import android.text.Html;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.sources.Source;
import org.openandroidauto.ui.CarUi;
import java.util.LinkedHashSet;

/**
 * Credits live inside the dashboard's existing window. A Presentation context has no Activity
 * window token, so opening MapLibre's ordinary AlertDialog here can terminate the projection.
 * This view deliberately creates no Dialog, Activity or browser Intent.
 */
final class MapAttributionSheet extends FrameLayout {
    private static final int MAX_SOURCE_NOTICES = 16;
    private static final int MAX_NOTICE_CHARACTERS = 16384;
    private final LinearLayout notices;

    MapAttributionSheet(Context context, Runnable close) {
        super(context);
        setBackgroundColor(0xf5101719);
        setClickable(true);
        setFocusable(true);
        setContentDescription("Map information and data licenses");
        setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = CarUi.text(context, "Map information", 22, CarUi.TEXT);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        Button done = CarUi.button(context, "Done", true);
        done.setContentDescription("Close map information");
        done.setOnClickListener(view -> close.run());
        heading.addView(done, new LinearLayout.LayoutParams(dp(88), dp(48)));
        column.addView(heading);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.setPadding(0, 0, 0, dp(76));
        scroll.setClipToPadding(false);
        notices = new LinearLayout(context);
        notices.setOrientation(LinearLayout.VERTICAL);
        notices.setPadding(0, dp(6), 0, dp(12));
        scroll.addView(notices, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        setElevation(dp(12));
        setVisibility(GONE);
    }

    void show(Style style) {
        notices.removeAllViews();
        text("Map data from OpenStreetMap contributors", 18, CarUi.TEXT);
        text("OpenStreetMap data is available under the Open Database License (ODbL) 1.0. "
                + "The data and license information are at:\nhttps://www.openstreetmap.org/copyright", 15, CarUi.TEXT_MUTED);
        text("© OpenMapTiles", 18, CarUi.TEXT);
        text("The map schema and cartographic design are licensed under Creative Commons Attribution 4.0 (CC BY 4.0).\n"
                + "https://openmaptiles.org/\nhttps://creativecommons.org/licenses/by/4.0/", 15, CarUi.TEXT_MUTED);
        text("OpenFreeMap · map tile hosting\nhttps://openfreemap.org/", 15, CarUi.TEXT_MUTED);
        text("MapLibre Native · map renderer\nhttps://maplibre.org/", 15, CarUi.TEXT_MUTED);

        // Preserve notices from a configured replacement style, too. Render its text and link
        // destinations locally; do not hand arbitrary provider markup or Intents to a browser.
        LinkedHashSet<String> additional = new LinkedHashSet<>();
        boolean shortened = false;
        if (style != null && style.isFullyLoaded()) {
            for (Source source : style.getSources()) {
                String html = source.getAttribution();
                if (html == null || html.isBlank()) continue;
                if (additional.size() >= MAX_SOURCE_NOTICES) { shortened = true; break; }
                if (html.length() > MAX_NOTICE_CHARACTERS) {
                    html = html.substring(0, MAX_NOTICE_CHARACTERS); shortened = true;
                }
                Spanned parsed = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
                StringBuilder notice = new StringBuilder(parsed.toString().trim());
                for (URLSpan link : parsed.getSpans(0, parsed.length(), URLSpan.class)) {
                    String url = link.getURL();
                    if (url.length() > MAX_NOTICE_CHARACTERS || notice.length() + url.length() > MAX_NOTICE_CHARACTERS) {
                        shortened = true; break;
                    }
                    notice.append('\n').append(url);
                }
                if (!notice.toString().isBlank()) additional.add(notice.toString());
            }
        }
        if (!additional.isEmpty()) {
            text("Tile-source notices", 18, CarUi.TEXT);
            for (String notice : additional) text(notice, 15, CarUi.TEXT_MUTED);
        }
        if (shortened) text("Some unusually long provider notices were shortened here. "
                + "Consult the configured map provider for its complete notices.", 14, CarUi.TEXT_MUTED);
        text("These license addresses are shown here so the information stays available without "
                + "opening another app on the car display.", 14, CarUi.TEXT_MUTED);
        setVisibility(VISIBLE);
        bringToFront();
        requestFocus();
    }

    private void text(String value, float size, int color) {
        TextView label = CarUi.text(getContext(), value, size, color);
        label.setPadding(0, dp(6), 0, dp(6));
        notices.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int dp(int value) { return CarUi.dp(getContext(), value); }
}
