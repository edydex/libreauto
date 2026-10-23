// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import java.net.HttpURLConnection;
import java.net.URL;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class NavigationProvidersCancellationTest {
    @Test public void blockedSocketTeardownCannotBlockTheUiCancellationCaller() throws Exception {
        NavigationProviders providers = new NavigationProviders(new NavigationConfig(NavigationConfig.DEFAULT_STYLE,
                NavigationConfig.DEFAULT_ROUTE, NavigationConfig.DEFAULT_SEARCH, false, 30));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        HttpURLConnection socket = new HttpURLConnection(new URL("https://example.org/")) {
            public void connect() { }
            public boolean usingProxy() { return false; }
            public void disconnect() {
                calls.incrementAndGet(); entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        };
        Field connection = NavigationProviders.class.getDeclaredField("connection");
        connection.setAccessible(true); connection.set(providers, socket);
        FutureTask<Void> caller = new FutureTask<>(() -> { providers.cancel(); return null; });
        Thread thread = new Thread(caller, "test-ui-cancel"); thread.start();
        try {
            caller.get(2, TimeUnit.SECONDS);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(1, release.getCount());
            providers.cancel();
            assertEquals(1, calls.get());
        } finally { release.countDown(); thread.join(2000); }
    }
}
