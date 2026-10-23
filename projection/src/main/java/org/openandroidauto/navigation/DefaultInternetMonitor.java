// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import java.util.function.Consumer;

/** Watches the validated default internet route, rather than a car's local Wi-Fi link. */
final class DefaultInternetMonitor {
    private final Context context;
    private final Handler main;
    private final Consumer<Boolean> listener;
    private ConnectivityManager.NetworkCallback callback;
    private boolean online;

    DefaultInternetMonitor(Context context, Handler main, Consumer<Boolean> listener) {
        this.context = context.getApplicationContext(); this.main = main; this.listener = listener;
    }
    static boolean validated(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) return false;
        try { return usable(manager.getNetworkCapabilities(manager.getActiveNetwork())); }
        catch (RuntimeException unavailable) { return false; }
    }
    private static boolean usable(NetworkCapabilities capabilities) {
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
    boolean isOnline() { return online; }
    void start() {
        if (callback != null) return;
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        online = validated(context); listener.accept(online);
        if (manager == null) return;
        callback = new ConnectivityManager.NetworkCallback() {
            private Network current;
            @Override public void onAvailable(Network network) {
                if (callback != this) return;
                if (current != null && !current.equals(network)) update(false);
                current = network;
            }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (callback != this || current != null && !current.equals(network)) return;
                current = network; update(usable(capabilities));
            }
            @Override public void onLost(Network network) {
                if (callback == this && network.equals(current)) { current = null; update(false); }
            }
            private void update(boolean value) {
                if (online == value) return;
                online = value; listener.accept(value);
            }
        };
        try { manager.registerDefaultNetworkCallback(callback, main); }
        catch (RuntimeException unavailable) { callback = null; }
    }
    void stop() {
        ConnectivityManager.NetworkCallback old = callback; callback = null;
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (old != null && manager != null) try { manager.unregisterNetworkCallback(old); }
        catch (RuntimeException ignored) { }
        online = false;
    }
}
