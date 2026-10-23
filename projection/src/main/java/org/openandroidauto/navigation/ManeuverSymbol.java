// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

/** Valhalla's native JSON maneuver codes, independent of translated instruction text.
 * See https://valhalla.github.io/valhalla/api/route/api-reference/#trip-legs-and-maneuvers
 * Unknown and non-driving types deliberately carry no directional arrow. */
public enum ManeuverSymbol {
    UNKNOWN("Follow the written instruction"),
    STRAIGHT("Continue straight"),
    SLIGHT_RIGHT("Bear right"), RIGHT("Turn right"), SHARP_RIGHT("Turn sharply right"),
    UTURN_RIGHT("Make a right U-turn"), UTURN_LEFT("Make a left U-turn"),
    SHARP_LEFT("Turn sharply left"), LEFT("Turn left"), SLIGHT_LEFT("Bear left"),
    EXIT_RIGHT("Take the ramp or exit on the right"), EXIT_LEFT("Take the ramp or exit on the left"),
    KEEP_RIGHT("Keep right"), KEEP_LEFT("Keep left"),
    MERGE("Merge"), MERGE_RIGHT("Merge right"), MERGE_LEFT("Merge left"),
    ROUNDABOUT("Roundabout; follow the written exit instruction"),
    DESTINATION("Destination"), FERRY("Ferry");

    public final String description;
    ManeuverSymbol(String description) { this.description = description; }

    public static ManeuverSymbol fromValhalla(int type, boolean arrived) {
        if (arrived) return DESTINATION;
        switch (type) {
            // StartRight/StartLeft describe the start's side, not an immediate right/left turn.
            case 1: case 2: case 3: case 7: case 8: case 17: case 22: return STRAIGHT;
            case 4: case 5: case 6: return DESTINATION;
            case 9: return SLIGHT_RIGHT;
            case 10: return RIGHT;
            case 11: return SHARP_RIGHT;
            case 12: return UTURN_RIGHT;
            case 13: return UTURN_LEFT;
            case 14: return SHARP_LEFT;
            case 15: return LEFT;
            case 16: return SLIGHT_LEFT;
            case 18: case 20: return EXIT_RIGHT;
            case 19: case 21: return EXIT_LEFT;
            case 23: return KEEP_RIGHT;
            case 24: return KEEP_LEFT;
            case 25: return MERGE;
            case 26: case 27: return ROUNDABOUT;
            case 28: case 29: return FERRY;
            case 37: return MERGE_RIGHT;
            case 38: return MERGE_LEFT;
            default: return UNKNOWN;
        }
    }
}
