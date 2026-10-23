// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.ui;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.view.Display;

/** Phone-only screens must not inherit the private projection display from their caller. */
public final class PhoneActivityLauncher {
    private PhoneActivityLauncher() {}

    public static void open(Context context, Intent intent) {
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle());
    }
}
