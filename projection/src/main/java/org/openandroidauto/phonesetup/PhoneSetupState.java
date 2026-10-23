// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.phonesetup;

/** Small phone-only presentation policy. It grants no permissions and starts no service. */
public final class PhoneSetupState {
    private PhoneSetupState() {}

    public enum Screen {
        HOME("home", "Car display"), CONNECTION("connection", "Connection"),
        MAPS("maps", "Maps & Saved"), MESSAGES("messages", "Messages"),
        CALLS("calls", "Calls"), VOICE("voice", "Voice input"),
        ADVANCED("advanced", "Advanced");
        public final String key, title;
        Screen(String key, String title) { this.key=key; this.title=title; }
        public static Screen fromKey(String key) {
            for (Screen screen : values()) if (screen.key.equals(key)) return screen;
            return HOME;
        }
    }
    public enum Action { SETUP_CONNECTION, START_CONNECTION, STOP_CONNECTION }
    public static final class ConnectionCard {
        public final String title, detail, actionLabel;
        public final Action action;
        ConnectionCard(String title, String detail, String label, Action action) {
            this.title=title; this.detail=detail; this.actionLabel=label; this.action=action;
        }
    }

    /** A selected compatibility mode is not a successful compatibility or car check. */
    public static ConnectionCard connection(boolean active, String stage,
                                             boolean compatibilitySelected, boolean developmentBuild) {
        if (active) {
            String title = "STREAMING".equals(stage) ? "Dashboard connected"
                : "ERROR".equals(stage) ? "Connection needs attention" : "Connecting…";
            return new ConnectionCard(title,
                "Stop the current connection before starting another.", "Stop connection", Action.STOP_CONNECTION);
        }
        boolean selected = compatibilitySelected || developmentBuild;
        String title = "ERROR".equals(stage) ? "Connection needs attention" : "Not connected";
        String detail = selected ? "Connect by USB, or open Connection to choose a paired car."
            : "Open Connection to set up installed Android Auto compatibility.";
        return new ConnectionCard(title, detail, selected ? "Start connection" : "Set up connection",
            selected ? Action.START_CONNECTION : Action.SETUP_CONNECTION);
    }

    /** Launcher/navigation extras never connect. A recreated USB Activity must not replay its attach. */
    public static boolean autoStartUsb(String action, boolean recreated) {
        return !recreated && "android.hardware.usb.action.USB_ACCESSORY_ATTACHED".equals(action);
    }
}
