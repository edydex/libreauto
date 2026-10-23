// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import androidx.annotation.Keep;

/** Short-lived helper: one fixed package, enable-state reads and compare-and-set only. No command strings. */
@Keep
public final class OfficialAutoShellService extends Binder {
    public static final String PACKAGE = "com.google.android.projection.gearhead";
    public static final String DESCRIPTOR = "org.bluetoothauto.OfficialAutoEnableState.v1";
    public static final int READ = IBinder.FIRST_CALL_TRANSACTION;
    public static final int COMPARE_SET = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int DESTROY = 16777115; // Shizuku's reserved UserService destroy transaction.
    private final Context context;
    private final int clientUid;
    @Keep public OfficialAutoShellService(Context context) throws Exception {
        if (Process.myUid() != 2000 || !"org.bluetoothauto".equals(context.getPackageName()))
            throw new SecurityException("ADB-shell LibreAuto helper required");
        this.context = context;
        clientUid = context.getPackageManager().getApplicationInfo(context.getPackageName(), 0).uid;
        if (clientUid < 10000) throw new SecurityException("Application caller required");
        attachInterface(null, DESCRIPTOR);
    }
    @Override protected synchronized boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags);
        int caller = Binder.getCallingUid();
        if (Process.myUid() != 2000 || (caller != clientUid && !(code == DESTROY && caller == 2000)))
            throw new SecurityException("Unexpected caller");
        if (code == DESTROY) {
            // Shizuku manager supplies its own reserved destruction protocol, not our interface token.
            new Thread(() -> System.exit(0), "LibreAuto-mode-helper-stop").start();
            return true;
        }
        data.enforceInterface(DESCRIPTOR);
        if (code != READ && code != COMPARE_SET) throw new SecurityException("Unsupported operation");
        PackageManager pm = context.getPackageManager();
        int current = pm.getApplicationEnabledSetting(PACKAGE);
        boolean changed = false;
        if (code == COMPARE_SET) {
            int expected = data.readInt();
            int target = data.readInt();
            if (data.dataAvail() != 0 || !valid(expected) || !valid(target)) throw new SecurityException("Invalid state request");
            if (current == expected) {
                // Clear app Binder identity before the nested PackageManager transaction: execute as shell.
                long identity = Binder.clearCallingIdentity();
                try { pm.setApplicationEnabledSetting(PACKAGE, target, 0); }
                finally { Binder.restoreCallingIdentity(identity); }
                changed = true; // A write was performed, independent of a later outside change/readback.
                current = pm.getApplicationEnabledSetting(PACKAGE);
            }
        } else if (data.dataAvail() != 0) throw new SecurityException("Unexpected payload");
        boolean enabled;
        try { enabled = pm.getApplicationInfo(PACKAGE, 0).enabled; }
        catch (PackageManager.NameNotFoundException missing) { throw new IllegalStateException("Android Auto is not installed", missing); }
        if (reply != null) {
            reply.writeNoException(); reply.writeInt(current); reply.writeInt(enabled ? 1 : 0); reply.writeInt(changed ? 1 : 0);
        }
        return true;
    }
    private static boolean valid(int state) { return state >= 0 && state <= 4; }
}
