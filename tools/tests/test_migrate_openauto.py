import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import uuid
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import migrate_openauto as migration


def prefs(**values):
    root = ET.Element("map")
    for key, value in values.items():
        if isinstance(value, bool):
            ET.SubElement(root, "boolean", name=key, value=str(value).lower())
        elif isinstance(value, int):
            ET.SubElement(root, "int", name=key, value=str(value))
        elif isinstance(value, set):
            node = ET.SubElement(root, "set", name=key)
            for member in sorted(value):
                ET.SubElement(node, "string").text = member
        else:
            ET.SubElement(root, "string", name=key).text = value
    return migration.serialize(root)


def values(raw):
    result = {}
    for key, node in migration.entries(migration.parse_preferences(raw)).items():
        result[key] = node.text if node.tag == "string" else node.attrib.get("value")
    return result


def place(name, latitude=47.3, longitude=-122.1, **extra):
    return {"id": str(uuid.uuid5(uuid.NAMESPACE_URL, name)), "name": name,
            "latitude": latitude, "longitude": longitude, "address": "Public test address",
            "shortcut": "NONE", **extra}


def old_places(*saved, recent=None):
    return prefs(destinations=json.dumps({"version": 1, "saved": saved, "recent": recent or []}))


class MigrationPlanTests(unittest.TestCase):
    def test_missing_settings_added_but_existing_values_unknown_keys_and_source_kept(self):
        source = {"aa_settings.xml": prefs(audio=True, sensor=False, video_width=800, unrelatedSecret="never copy")}
        target = {"aa_settings.xml": prefs(audio=False, targetCustom="keep")}
        original_source, original_target = dict(source), dict(target)
        plan = migration.build_plan(source, target)
        self.assertEqual({"audio": "false", "sensor": "false", "video_width": "800", "targetCustom": "keep"}, values(plan.changes["aa_settings.xml"]))
        self.assertEqual(2, plan.keys_added)
        self.assertEqual(original_source, source)
        self.assertEqual(original_target, target)

    def test_queue_grants_connection_modes_helper_and_reports_are_never_imported(self):
        source = {"media-library.xml": prefs(queue="sensitive queue"), "wireless-connection-report.xml": prefs(token="do not import"),
                  "bluetoothauto.xml": prefs(tracks="old queue", musicGrants="old grants"),
                  "libreauto_connection.xml": prefs(car_display_mode="LIBREAUTO")}
        self.assertEqual({}, migration.build_plan(source, {}).changes)

    def test_supported_notification_calls_sms_and_identity_choices_copy_without_runtime_permissions(self):
        source = {"calls-access.xml": prefs(enabled=True), "carrier-sms-access.xml": prefs(enabled=True),
                  "open_auto_identity.xml": prefs(installed_aa_enabled=True, identityMaterial="never copy"),
                  "messaging_sources.xml": prefs(**{"org.telegram.messenger": True, "unsupported.app": True})}
        plan = migration.build_plan(source, {})
        self.assertEqual(4, plan.keys_added)
        self.assertEqual({"installed_aa_enabled": "true"}, values(plan.changes["open_auto_identity.xml"]))
        self.assertEqual({"org.telegram.messenger": "true"}, values(plan.changes["messaging_sources.xml"]))

    def test_wireless_selection_and_trusted_devices_are_bounded_settings_only(self):
        source = {"open_auto_wireless.xml": prefs(selected_car_address="AA:BB:CC:DD:EE:FF", selected_car_name="Private car", wifi_password="never"),
                  "aa_settings.xml": prefs(trusted_devices={"AA:BB:CC:DD:EE:FF"})}
        plan = migration.build_plan(source, {})
        self.assertNotIn("wifi_password", values(plan.changes["open_auto_wireless.xml"]))
        self.assertEqual(3, plan.keys_added)

    def test_speed_units_only_fill_a_missing_host_key(self):
        source = {"navigation-providers.xml": prefs(speed_units="mph")}
        target = {"bluetoothauto.xml": prefs(units="METRIC", tracks="preserve exactly", musicGrants="keep grants")}
        self.assertNotIn("bluetoothauto.xml", migration.build_plan(source, target).changes)
        target = {"bluetoothauto.xml": prefs(tracks="preserve exactly", musicGrants="keep grants")}
        self.assertEqual({"units": "IMPERIAL", "tracks": "preserve exactly", "musicGrants": "keep grants"},
                         values(migration.build_plan(source, target).changes["bluetoothauto.xml"]))

    def test_saved_places_append_without_replacing_current_pin_or_importing_recent_history(self):
        first, second = place("Legacy collision"), place("New place", 47.4, extraSecret="never copy")
        current = [{"name": "Current nickname", "lat": 47.3, "lon": -122.1}]
        target = {"own-map-destinations.xml": prefs(saved=json.dumps(current), recent="[\"unchanged recent data\"]")}
        source = {"navigation-destinations.xml": old_places(first, second, recent=[place("Private recent", 47.7)])}
        plan = migration.build_plan(source, target)
        result = values(plan.changes["own-map-destinations.xml"])
        rows = json.loads(result["saved"])
        self.assertEqual(["Current nickname", "New place"], [row["name"] for row in rows])
        self.assertEqual(target["own-map-destinations.xml"], prefs(saved=json.dumps(current), recent="[\"unchanged recent data\"]"))
        self.assertEqual("[\"unchanged recent data\"]", result["recent"])
        self.assertEqual(1, plan.places_added)
        self.assertEqual(1, plan.places_kept)
        self.assertNotIn("extraSecret", result["projection-details-v1"])
        self.assertNotIn("Private recent", result["projection-details-v1"])

    def test_home_and_work_metadata_preserve_current_shortcut_and_addresses(self):
        existing = place("Current home", shortcut="HOME")
        target = {"own-map-destinations.xml": prefs(saved=json.dumps([{"name": existing["name"], "lat": 47.3, "lon": -122.1}]),
                    **{"projection-details-v1": json.dumps({"version": 1, "saved": [existing], "recent": []})})}
        plan = migration.build_plan({"navigation-destinations.xml": old_places(place("Old home", 47.4, shortcut="HOME"), place("Work", 47.5, shortcut="WORK"))}, target)
        details = json.loads(values(plan.changes["own-map-destinations.xml"])["projection-details-v1"])
        self.assertEqual(["HOME", "NONE", "WORK"], [entry["shortcut"] for entry in details["saved"]])
        self.assertEqual(existing, details["saved"][0])

    def test_full_saved_list_does_not_evict_any_current_place(self):
        current = [{"name": f"Current {i}", "lat": 40 + i / 100, "lon": -120} for i in range(50)]
        raw = prefs(saved=json.dumps(current))
        plan = migration.build_plan({"navigation-destinations.xml": old_places(place("Extra"))}, {"own-map-destinations.xml": raw})
        self.assertNotIn("own-map-destinations.xml", plan.changes)
        self.assertEqual(1, plan.places_over_capacity)
        self.assertEqual(0, plan.places_added)

    def test_rerunning_a_successful_plan_is_a_noop(self):
        source = {"aa_settings.xml": prefs(audio=False), "navigation-providers.xml": prefs(speed_units="kmh"),
                  "navigation-destinations.xml": old_places(place("Saved"))}
        plan = migration.build_plan(source, {})
        self.assertEqual({}, migration.build_plan(source, plan.changes).changes)

    def test_unsafe_source_format_or_invalid_coordinates_fail_before_any_mutation(self):
        for raw in (b"<!DOCTYPE map [<!ENTITY value 'x'>]><map/>", b"<map><string name='bad'></map>"):
            with self.assertRaises(migration.MigrationError):
                migration.build_plan({"aa_settings.xml": raw}, {})
        with self.assertRaises(migration.MigrationError):
            migration.build_plan({"navigation-destinations.xml": old_places(place("Invalid", 999))}, {})

    def test_duplicate_or_wrong_type_keys_are_not_silently_replaced(self):
        with self.assertRaises(migration.MigrationError):
            migration.build_plan({"aa_settings.xml": b"<map><boolean name='audio' value='true'/><boolean name='audio' value='false'/></map>"}, {})
        with self.assertRaises(migration.MigrationError):
            migration.build_plan({"aa_settings.xml": prefs(audio="true")}, {})


class OperationTests(unittest.TestCase):
    def test_apply_failure_restores_original_files_including_android_backup(self):
        class Fake:
            files = {"aa_settings.xml": b"old", "aa_settings.xml.bak": b"backup"}
            failed = False
            def write_file(self, filename, data):
                if filename == "calls-access.xml" and not self.failed:
                    self.failed = True
                    raise migration.MigrationError("test")
                if data is None:
                    self.files.pop(filename, None)
                else:
                    self.files[filename] = data
            def read_file(self, package, filename):
                return self.files.get(filename)
        fake = Fake()
        original = dict(fake.files)
        with self.assertRaises(migration.MigrationError):
            migration.apply_plan(fake, migration.Plan({"aa_settings.xml": b"new", "calls-access.xml": b"new"}, 2, 0, 0, 0), original)
        self.assertEqual(original, fake.files)

    def test_preferences_are_sent_on_stdin_and_never_inserted_in_the_remote_shell_command(self):
        class Fake(migration.Device):
            captured = []
            def run(self, args, data=None, check=True):
                self.captured.append((args, data))
                return migration.subprocess.CompletedProcess(args, 0, data or b"", b"")
        fake = Fake("adb", None)
        private = b"<map><string name='label'>$(do-not-execute) `private`</string></map>"
        fake.write_file("aa_settings.xml", private)
        tee = [call for call in fake.captured if "tee" in call[0]]
        self.assertEqual(1, len(tee))
        self.assertEqual(private, tee[0][1])
        self.assertEqual(["shell", "run-as", migration.TARGET, "tee", "shared_prefs/aa_settings.xml.libreauto-import"], tee[0][0])
        self.assertTrue(all("private" not in " ".join(args) for args, _ in fake.captured))
        self.assertTrue(all(args[0] == "shell" for args, _ in fake.captured))
        self.assertEqual("mv", fake.captured[-1][0][3])

    def test_missing_debug_access_is_friendly_even_when_adb_returns_zero_with_error_stdout(self):
        class Fake(migration.Device):
            def run(self, args, data=None, check=True):
                self.last_args = args
                return migration.subprocess.CompletedProcess(args, 0, b"run-as: package not debuggable\n", b"")
        fake = Fake("adb", None)
        with self.assertRaisesRegex(migration.MigrationError, "separate OpenAuto Lite installation does not allow debugging access"):
            fake.require_debuggable(migration.SOURCE)
        self.assertEqual(["shell", "run-as", migration.SOURCE, "id"], fake.last_args)
        with self.assertRaisesRegex(migration.MigrationError, "preference file could not be read"):
            fake.read_file(migration.SOURCE, "aa_settings.xml")

    def test_nonzero_remote_exit_is_not_treated_as_xml_or_missing_file(self):
        class Fake(migration.Device):
            def run(self, args, data=None, check=True):
                return migration.subprocess.CompletedProcess(args, 1, b"", b"run-as: package not debuggable\n")
        fake = Fake("adb", None)
        with self.assertRaises(migration.MigrationError):
            fake.require_debuggable(migration.TARGET)
        with self.assertRaises(migration.MigrationError):
            fake.read_file(migration.TARGET, "aa_settings.xml")

    def test_incomplete_tee_echo_prevents_rename_of_the_original_file(self):
        class Fake(migration.Device):
            captured = []
            def run(self, args, data=None, check=True):
                self.captured.append(args)
                return migration.subprocess.CompletedProcess(args, 0, b"truncated" if "tee" in args else b"", b"")
        fake = Fake("adb", None)
        with self.assertRaisesRegex(migration.MigrationError, "original file was kept"):
            fake.write_file("aa_settings.xml", b"complete input")
        self.assertFalse(any("mv" in args for args in fake.captured))
        self.assertTrue(any("rm" in args for args in fake.captured))

    def test_private_backups_use_restricted_permissions_and_are_not_release_files(self):
        with tempfile.TemporaryDirectory() as folder:
            backup = migration.backup_preferences(folder, {"aa_settings.xml": b"private"}, {})
            self.assertEqual(0o700, backup.stat().st_mode & 0o777)
            self.assertEqual(0o600, (backup / "old-openauto/aa_settings.xml").stat().st_mode & 0o777)
        with self.assertRaises(migration.MigrationError):
            migration.backup_preferences(str(Path(migration.__file__).resolve().parents[1] / "outputs/private"), {}, {})

    def test_default_is_read_only_and_does_not_print_private_values(self):
        class Fake:
            stopped = False
            def __init__(self, *_): pass
            def require_debuggable(self, package): pass
            def read_preferences(self, package, filenames):
                return ({"open_auto_wireless.xml": prefs(selected_car_name="PRIVATE-CAR-NAME")} if package == migration.SOURCE else {}), {}
            def force_stop_target(self): self.stopped = True
        output = io.StringIO()
        with mock.patch.object(migration, "Device", Fake), mock.patch.object(migration, "find_apksigner", return_value="unused"), mock.patch.object(migration, "verify_target"), contextlib.redirect_stdout(output):
            self.assertEqual(0, migration.main(["--adb", "unused"]))
        self.assertFalse(Fake.stopped)
        self.assertNotIn("PRIVATE-CAR-NAME", output.getvalue())
        self.assertIn("Nothing changed", output.getvalue())


if __name__ == "__main__":
    unittest.main()
