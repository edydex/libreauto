// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline;

/** Native SDK isComplete alone accepts unknown 0/0 counts; a saved visual pack must be proven. */
public final class OfflineCompletion {
    private OfflineCompletion() {}
    public static boolean ready(boolean precise,long required,long completed,long tiles) {
        return precise&&required>0&&completed>=required&&tiles>0;
    }
}
