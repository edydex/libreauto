// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration;

import rikka.shizuku.ShizukuProvider;

/** No root/Sui bootstrap. The optional helper works only with explicitly approved ADB-shell Shizuku. */
public final class OfficialAutoShizukuProvider extends ShizukuProvider {
    @Override public boolean onCreate() {
        disableAutomaticSuiInitialization();
        return super.onCreate();
    }
}
