// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import org.junit.Test;
import static org.junit.Assert.*;

public class OnlineRouteRefreshPolicyTest {
    @Test public void validatedConnectionSettlesBeforeOneRefresh() {
        OnlineRouteRefreshPolicy policy = new OnlineRouteRefreshPolicy();
        assertEquals(-1, policy.delayMillis(7, 0));
        policy.setOnline(true, 100);
        assertEquals(1500, policy.delayMillis(7, 100));
        assertNull(policy.begin(7, 1599));
        OnlineRouteRefreshPolicy.Ticket ticket = policy.begin(7, 1600);
        assertNotNull(ticket); assertTrue(policy.current(ticket, 7));
        assertNull(policy.begin(7, 10000));
        assertEquals(-1, policy.delayMillis(7, 10000));
    }
    @Test public void networkLossAndDifferentDestinationInvalidateAnOldAnswer() {
        OnlineRouteRefreshPolicy policy = new OnlineRouteRefreshPolicy();
        policy.setOnline(true, 0);
        OnlineRouteRefreshPolicy.Ticket old = policy.begin(4, 1500);
        assertFalse(policy.current(old, 5));
        policy.setOnline(false, 1600);
        assertFalse(policy.current(old, 4));
        policy.setOnline(true, 1700);
        assertFalse(policy.current(old, 4));
        assertNull(policy.begin(4, 3199));
        assertNotNull(policy.begin(4, 3200));
    }
    @Test public void repeatedCapabilityCallbacksDoNotRestartDebounceOrAllowRetryStorms() {
        OnlineRouteRefreshPolicy policy = new OnlineRouteRefreshPolicy();
        policy.setOnline(true, 0);
        long connection = policy.connectionRevision();
        policy.setOnline(true, 1400);
        assertEquals(connection, policy.connectionRevision());
        assertNotNull(policy.begin(1, 1500));
        policy.setOnline(true, 5000);
        assertNull(policy.begin(1, 5000));
    }
    @Test public void onlineFailureWithLocalFallbackWaitsForARealReconnect() {
        OnlineRouteRefreshPolicy policy = new OnlineRouteRefreshPolicy();
        policy.setOnline(true, 0);
        policy.suppressCurrentConnection(3);
        assertNull(policy.begin(3, 10000));
        policy.setOnline(false, 10001); policy.setOnline(true, 10002);
        assertNotNull(policy.begin(3, 11502));
    }
    @Test public void aNewLocalRouteCanRefreshWithoutReusingAnOldRouteTicket() {
        OnlineRouteRefreshPolicy policy = new OnlineRouteRefreshPolicy();
        policy.setOnline(true, 0);
        OnlineRouteRefreshPolicy.Ticket first = policy.begin(20, 1500);
        OnlineRouteRefreshPolicy.Ticket next = policy.begin(21, 1600);
        assertNotNull(next); assertFalse(policy.current(first, 21)); assertTrue(policy.current(next, 21));
    }
}
