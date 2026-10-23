// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.phonesetup;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhoneSetupStateTest {
    @Test public void activeErrorStillOffersStopInsteadOfAnotherStart() {
        var state=PhoneSetupState.connection(true,"ERROR",false,false);
        assertEquals(PhoneSetupState.Action.STOP_CONNECTION,state.action);
        assertEquals("Stop connection",state.actionLabel);
    }
    @Test public void staleStreamingValueCannotClaimConnectedWithoutActiveOwner() {
        var state=PhoneSetupState.connection(false,"STREAMING",true,false);
        assertEquals("Not connected",state.title);
        assertEquals(PhoneSetupState.Action.START_CONNECTION,state.action);
    }
    @Test public void releaseSetupRequiredIsNotAdvertisedAsReady() {
        var state=PhoneSetupState.connection(false,"DISCONNECTED",false,false);
        assertEquals(PhoneSetupState.Action.SETUP_CONNECTION,state.action);
        assertTrue(state.detail.contains("set up"));
    }
    @Test public void selectedCompatibilityDoesNotClaimItsChecksPassed() {
        var state=PhoneSetupState.connection(false,"DISCONNECTED",true,false);
        assertEquals("Not connected",state.title);
        assertFalse(state.detail.toLowerCase().contains("passed"));
    }
    @Test public void privateDevelopmentStartPathIsPreserved() {
        assertEquals(PhoneSetupState.Action.START_CONNECTION,
            PhoneSetupState.connection(false,"DISCONNECTED",false,true).action);
    }
    @Test public void onlyRealNewUsbActionAutoStartsNeverNavigationOrRecreation() {
        assertTrue(PhoneSetupState.autoStartUsb("android.hardware.usb.action.USB_ACCESSORY_ATTACHED",false));
        assertFalse(PhoneSetupState.autoStartUsb("android.hardware.usb.action.USB_ACCESSORY_ATTACHED",true));
        assertFalse(PhoneSetupState.autoStartUsb("android.intent.action.MAIN",false));
        assertFalse(PhoneSetupState.autoStartUsb(null,false));
    }
    @Test public void navigationIsAnExactBoundedAllowlist() {
        assertEquals(PhoneSetupState.Screen.CONNECTION,PhoneSetupState.Screen.fromKey("connection"));
        assertEquals(PhoneSetupState.Screen.HOME,PhoneSetupState.Screen.fromKey("connection;start"));
        assertEquals(PhoneSetupState.Screen.HOME,PhoneSetupState.Screen.fromKey("unknown"));
        assertEquals(PhoneSetupState.Screen.HOME,PhoneSetupState.Screen.fromKey(null));
    }
}
