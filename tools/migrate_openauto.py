#!/usr/bin/env python3
"""Optional, local-only migration from the separate debuggable OpenAuto Lite app.

Dry-run by default. The package IDs are intentionally fixed. No URI grants, media
queues, reports, recent places, credentials, or connection-mode state are copied.
"""
from __future__ import annotations

import argparse
import copy
import dataclasses
import datetime as dt
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import uuid
import xml.etree.ElementTree as ET

SOURCE = "org.openandroidauto.lite"
TARGET = "org.bluetoothauto"
EXPECTED_SIGNER = "aa6d91b1bbeff93b792bd38440721fc0f38392c2795253e0c0e2630545bfd414"
MAX_XML = 512 * 1024
MAX_PLACES = 120_000
NOTIFICATION_PACKAGES = (
    "com.google.android.apps.messaging", "com.android.messaging", "com.samsung.android.messaging",
    "org.telegram.messenger", "org.telegram.messenger.web", "org.thoughtcrime.securesms",
)
MAC = re.compile(r"(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\Z")

# Only these exact preference keys can cross the old application sandbox.
ALLOWLIST = {
    "aa_settings.xml": {
        "audio": "boolean", "sensor": "boolean", "fragment": "boolean", "testPattern": "boolean",
        "video_width": "int", "video_height": "int", "video_fps": "int", "video_bitrate": "int",
        "trusted_devices": "set",
    },
    "navigation-providers.xml": {
        "style_url": "string", "route_url": "string", "search_url": "string",
        "speak_instructions": "boolean", "adaptive_maximum_map_fps": "int", "maximum_map_fps": "int",
        "speed_units": "string",
    },
    "calls-access.xml": {"enabled": "boolean"},
    "carrier-sms-access.xml": {"enabled": "boolean"},
    "messaging_sources.xml": {package: "boolean" for package in NOTIFICATION_PACKAGES},
    "open_auto_identity.xml": {"installed_aa_enabled": "boolean"},
    "open_auto_wireless.xml": {"selected_car_address": "string", "selected_car_name": "string"},
}
SOURCE_FILES = tuple(ALLOWLIST) + ("navigation-destinations.xml",)
TARGET_FILES = tuple(ALLOWLIST) + ("bluetoothauto.xml", "own-map-destinations.xml")


class MigrationError(Exception):
    """A message safe to show without including preference values or command output."""


@dataclasses.dataclass(frozen=True)
class Plan:
    changes: dict[str, bytes]
    keys_added: int
    places_added: int
    places_kept: int
    places_over_capacity: int


def parse_preferences(raw: bytes | None) -> ET.Element:
    if raw is None:
        return ET.Element("map")
    if len(raw) > MAX_XML or b"<!DOCTYPE" in raw.upper() or b"<!ENTITY" in raw.upper():
        raise MigrationError("A preference file is too large or uses an unsupported XML format. Nothing was replaced.")
    try:
        root = ET.fromstring(raw)
    except ET.ParseError as error:
        raise MigrationError("A preference file could not be safely read. Nothing was replaced.") from error
    if root.tag != "map":
        raise MigrationError("A preference file uses an unsupported format. Nothing was replaced.")
    names = [child.attrib.get("name") for child in root]
    if any(name is None for name in names) or len(names) != len(set(names)):
        raise MigrationError("A preference file contains ambiguous keys. Nothing was replaced.")
    return root


def entries(root: ET.Element) -> dict[str, ET.Element]:
    return {child.attrib["name"]: child for child in root}


def serialize(root: ET.Element) -> bytes:
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def valid_setting(key: str, node: ET.Element, kind: str) -> bool:
    if node.tag != kind:
        return False
    value = node.attrib.get("value", "")
    if kind == "boolean":
        return value in ("true", "false") and len(node) == 0
    if kind == "int":
        try:
            integer = int(value)
        except ValueError:
            return False
        bounds = {"video_width": (256, 4096), "video_height": (144, 2160), "video_fps": (1, 60),
                  "video_bitrate": (100_000, 50_000_000), "maximum_map_fps": (5, 30), "adaptive_maximum_map_fps": (5, 30)}
        low, high = bounds.get(key, (-2147483648, 2147483647))
        return low <= integer <= high and len(node) == 0
    if kind == "set":
        return len(node) <= 50 and all(child.tag == "string" and MAC.fullmatch(child.text or "") for child in node)
    text = node.text or ""
    if len(node) or len(text) > 2048 or any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in text):
        return False
    if key == "speed_units":
        return text in ("auto", "mph", "kmh")
    if key == "selected_car_address":
        return bool(MAC.fullmatch(text))
    if key == "selected_car_name":
        return 0 < len(text) <= 200
    if key.endswith("_url"):
        from urllib.parse import urlsplit
        try:
            parsed = urlsplit(text)
            return parsed.scheme == "https" and bool(parsed.hostname) and parsed.username is None and parsed.password is None and not parsed.fragment
        except ValueError:
            return False
    return True


def pin(latitude: object, longitude: object) -> tuple[int, int]:
    if isinstance(latitude, bool) or isinstance(longitude, bool):
        raise MigrationError("Saved places contain invalid coordinates. Nothing was replaced.")
    try:
        lat, lon = float(latitude), float(longitude)
    except (ValueError, TypeError) as error:
        raise MigrationError("Saved places contain invalid coordinates. Nothing was replaced.") from error
    if not math.isfinite(lat) or not math.isfinite(lon) or not -90 <= lat <= 90 or not -180 <= lon <= 180:
        raise MigrationError("Saved places contain invalid coordinates. Nothing was replaced.")
    # Match Java Math.round used by the shared destination backend, including negative coordinates.
    return math.floor(lat * 100000 + .5), math.floor(lon * 100000 + .5)


def bounded_json(raw: str, expected: type):
    if not isinstance(raw, str) or len(raw) > MAX_PLACES:
        raise MigrationError("Saved places are too large to migrate safely. Nothing was replaced.")
    try:
        value = json.loads(raw)
    except (ValueError, TypeError) as error:
        raise MigrationError("Saved places could not be read. Nothing was replaced.") from error
    if not isinstance(value, expected):
        raise MigrationError("Saved places use an unsupported format. Nothing was replaced.")
    return value


def valid_name(value: object, limit: int = 200, empty: bool = False) -> bool:
    return isinstance(value, str) and (empty or bool(value.strip())) and len(value) <= limit and not any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in value)


def merge_saved_places(source: ET.Element, target: ET.Element) -> tuple[bool, int, int, int]:
    source_node = entries(source).get("destinations")
    if source_node is None:
        return False, 0, 0, 0
    if source_node.tag != "string":
        raise MigrationError("Legacy saved places use an unsupported format. Nothing was replaced.")
    old = bounded_json(source_node.text or "", dict)
    if old.get("version") != 1 or not isinstance(old.get("saved"), list) or len(old["saved"]) > 50:
        raise MigrationError("Legacy saved places use an unsupported format. Nothing was replaced.")
    current_nodes = entries(target)
    current_saved_node = current_nodes.get("saved")
    if current_saved_node is not None and current_saved_node.tag != "string":
        raise MigrationError("Current saved places use an unsupported format. Nothing was replaced.")
    current = bounded_json(current_saved_node.text if current_saved_node is not None else "[]", list)
    if len(current) > 50:
        raise MigrationError("Current saved places exceed the supported limit. Nothing was replaced.")
    pins = set()
    for place in current:
        if not isinstance(place, dict) or not valid_name(place.get("name")):
            raise MigrationError("Current saved places could not be safely read. Nothing was replaced.")
        pins.add(pin(place.get("lat"), place.get("lon")))
    details_node = current_nodes.get("projection-details-v1")
    if details_node is not None and details_node.tag != "string":
        raise MigrationError("Current saved-place details use an unsupported format. Nothing was replaced.")
    details = bounded_json(details_node.text, dict) if details_node is not None else {"version": 1, "saved": [], "recent": []}
    if details.get("version") != 1 or not isinstance(details.get("saved"), list):
        raise MigrationError("Current saved-place details use an unsupported format. Nothing was replaced.")
    rich = []
    for place in details["saved"]:
        if not isinstance(place, dict):
            raise MigrationError("Current saved-place details could not be safely read. Nothing was replaced.")
        if pin(place.get("latitude"), place.get("longitude")) in pins:
            rich.append(place)
    details["saved"] = rich
    rich_ids = {place.get("id") for place in rich if isinstance(place, dict)}
    used_shortcuts = {place.get("shortcut") for place in rich if isinstance(place, dict)}
    added = kept = overflow = 0
    for place in old["saved"]:
        if not isinstance(place, dict) or not valid_name(place.get("name")) or not valid_name(place.get("address", ""), 300, True):
            raise MigrationError("Legacy saved places could not be safely read. Nothing was replaced.")
        coordinate = pin(place.get("latitude"), place.get("longitude"))
        shortcut = place.get("shortcut", "NONE")
        if shortcut not in ("NONE", "HOME", "WORK"):
            raise MigrationError("Legacy saved places contain an invalid shortcut. Nothing was replaced.")
        if coordinate in pins:
            kept += 1
            continue
        if len(current) >= 50:
            overflow += 1
            continue
        saved = {key: place[key] for key in ("name", "latitude", "longitude")}
        try:
            identifier = str(uuid.UUID(str(place.get("id"))))
        except (ValueError, TypeError, AttributeError):
            identifier = str(uuid.uuid5(uuid.NAMESPACE_URL, f"libreauto:saved:{coordinate[0]}:{coordinate[1]}"))
        if identifier in rich_ids:
            identifier = str(uuid.uuid5(uuid.NAMESPACE_URL, f"libreauto:migrated:{coordinate[0]}:{coordinate[1]}"))
        saved["id"] = identifier
        saved["address"] = place.get("address", "")
        saved["shortcut"] = shortcut if shortcut not in used_shortcuts else "NONE"
        current.append({"name": saved["name"], "lat": saved["latitude"], "lon": saved["longitude"]})
        rich.append(saved)
        rich_ids.add(identifier)
        used_shortcuts.add(saved["shortcut"])
        pins.add(coordinate)
        added += 1
    if not added:
        return False, 0, kept, overflow
    for key, value in (("saved", current), ("projection-details-v1", details)):
        node = current_nodes.get(key)
        if node is None:
            node = ET.SubElement(target, "string", name=key)
        encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
        if len(encoded) > MAX_PLACES:
            raise MigrationError("Combined saved places are too large. Nothing was replaced.")
        node.text = encoded
    return True, added, kept, overflow


def build_plan(source: dict[str, bytes | None], target: dict[str, bytes | None]) -> Plan:
    changes: dict[str, bytes] = {}
    added_keys = 0
    for filename, allowed in ALLOWLIST.items():
        old, current = parse_preferences(source.get(filename)), parse_preferences(target.get(filename))
        old_entries, current_entries = entries(old), entries(current)
        modified = False
        for key, kind in allowed.items():
            node = old_entries.get(key)
            if node is None or key in current_entries:
                continue
            if not valid_setting(key, node, kind):
                raise MigrationError("A legacy setting has an unsupported value. Nothing was replaced.")
            current.append(copy.deepcopy(node))
            modified = True
            added_keys += 1
        if modified:
            changes[filename] = serialize(current)
    # Projection and player now share distance/speed units. Preserve any existing host choice.
    navigation = entries(parse_preferences(source.get("navigation-providers.xml")))
    units = navigation.get("speed_units")
    host = parse_preferences(target.get("bluetoothauto.xml"))
    if units is not None and "units" not in entries(host):
        if not valid_setting("speed_units", units, "string"):
            raise MigrationError("Legacy units could not be safely read. Nothing was replaced.")
        ET.SubElement(host, "string", name="units").text = {"mph": "IMPERIAL", "kmh": "METRIC", "auto": "AUTO"}[units.text]
        changes["bluetoothauto.xml"] = serialize(host)
        added_keys += 1
    places = parse_preferences(target.get("own-map-destinations.xml"))
    changed, added, kept, overflow = merge_saved_places(parse_preferences(source.get("navigation-destinations.xml")), places)
    if changed:
        changes["own-map-destinations.xml"] = serialize(places)
    return Plan(changes, added_keys, added, kept, overflow)


class Device:
    def __init__(self, adb: str, serial: str | None):
        self.command = [adb] + (["-s", serial] if serial else [])

    def run(self, args: list[str], data: bytes | None = None, check: bool = True) -> subprocess.CompletedProcess:
        try:
            result = subprocess.run(self.command + args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise MigrationError("The debugging connection failed. No command output or settings were printed.") from error
        if check and result.returncode:
            raise MigrationError("The phone rejected a debugging operation. Check the connection and debuggable app builds.")
        return result

    def require_debuggable(self, package: str):
        result = self.run(["shell", "run-as", package, "id"], check=False)
        if result.returncode or not re.search(rb"\buid=\d+", result.stdout) or result.stdout.lstrip().startswith(b"run-as:"):
            if package == SOURCE:
                raise MigrationError("The separate OpenAuto Lite installation does not allow debugging access. This optional migration requires a matching debuggable build; the old app and LibreAuto were not changed.")
            raise MigrationError("The installed LibreAuto build does not allow debugging access. This optional migration requires a debuggable LibreAuto build. Nothing was changed.")

    def read_file(self, package: str, filename: str) -> bytes | None:
        if package not in (SOURCE, TARGET) or filename.removesuffix(".bak") not in SOURCE_FILES + TARGET_FILES:
            raise MigrationError("An unapproved preference path was refused.")
        result = self.run(["shell", "run-as", package, "cat", f"shared_prefs/{filename}"], check=False)
        if result.returncode or result.stdout.lstrip().startswith(b"run-as:"):
            if b"No such file or directory" in result.stderr + result.stdout:
                return None
            raise MigrationError("An app preference file could not be read. Nothing was replaced.")
        if len(result.stdout) > MAX_XML:
            raise MigrationError("An app preference file is too large. Nothing was replaced.")
        return result.stdout

    def read_preferences(self, package: str, filenames: tuple[str, ...]) -> tuple[dict[str, bytes | None], dict[str, bytes | None]]:
        chosen, physical = {}, {}
        for name in filenames:
            base = self.read_file(package, name)
            backup = self.read_file(package, name + ".bak")
            physical[name], physical[name + ".bak"] = base, backup
            # Android prefers .bak after an interrupted SharedPreferences write.
            chosen[name] = backup if backup is not None else base
        return chosen, physical

    def write_file(self, filename: str, data: bytes | None):
        if filename.removesuffix(".bak") not in TARGET_FILES:
            raise MigrationError("An unapproved preference path was refused.")
        path = f"shared_prefs/{filename}"
        if data is None:
            self.run(["shell", "run-as", TARGET, "rm", "-f", path])
            return
        temporary = path + ".libreauto-import"
        self.run(["shell", "run-as", TARGET, "mkdir", "-p", "shared_prefs"])
        try:
            # adb shell's normal protocol carries stdin and preserves the remote exit status.
            # tee's captured echo is verified internally and is never printed.
            result = self.run(["shell", "run-as", TARGET, "tee", temporary], data, check=False)
            if result.returncode or result.stdout != data:
                raise MigrationError("The phone did not confirm the complete preference write. The original file was kept.")
            self.run(["shell", "run-as", TARGET, "chmod", "600", temporary])
            self.run(["shell", "run-as", TARGET, "mv", "-f", temporary, path])
        except Exception:
            try:
                self.run(["shell", "run-as", TARGET, "rm", "-f", temporary], check=False)
            except MigrationError:
                pass
            raise

    def force_stop_target(self):
        self.run(["shell", "am", "force-stop", TARGET])


def find_apksigner(explicit: str | None) -> str:
    if explicit:
        return explicit
    found = shutil.which("apksigner") or shutil.which("apksigner.bat")
    if found:
        return found
    sdk_roots = [Path(value) for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT") if (value := os.environ.get(key))]
    sdk_roots += [Path.home() / "Library/Android/sdk", Path.home() / "Android/Sdk"]
    local = Path(__file__).resolve().parents[1] / "local.properties"
    if local.exists():
        for line in local.read_text().splitlines():
            if line.startswith("sdk.dir="):
                sdk_roots.insert(0, Path(line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\")))
    for root in sdk_roots:
        for candidate in sorted((root / "build-tools").glob("*/apksigner*"), reverse=True):
            if candidate.is_file() and candidate.name in ("apksigner", "apksigner.bat"):
                return str(candidate)
    raise MigrationError("Android SDK apksigner is required to verify the installed LibreAuto signer. Supply --apksigner PATH.")


def verify_target(device: Device, apksigner: str, expected: str):
    expected = expected.lower().replace(":", "")
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise MigrationError("The expected signer must be a SHA-256 certificate fingerprint.")
    info = device.run(["shell", "dumpsys", "package", TARGET]).stdout.decode("utf-8", "replace")
    version = re.search(r"\bversionCode=(\d+)", info)
    if not version or int(version.group(1)) < 20:
        raise MigrationError("Install the unified LibreAuto update first; the target must be org.bluetoothauto versionCode 20 or newer.")
    paths = device.run(["shell", "pm", "path", TARGET]).stdout.decode("utf-8", "replace").splitlines()
    apk = next((line[len("package:"):].strip() for line in paths if line.startswith("package:") and line.strip().endswith("/base.apk")), None)
    if not apk or not apk.startswith("/data/app/") or any(character.isspace() for character in apk):
        raise MigrationError("The installed LibreAuto APK path could not be verified.")
    with tempfile.TemporaryDirectory(prefix="libreauto-signer-") as directory:
        target_apk = Path(directory) / "base.apk"
        with target_apk.open("wb") as output:
            os.chmod(target_apk, 0o600)
            try:
                result = subprocess.run(device.command + ["exec-out", "cat", apk], stdout=output, stderr=subprocess.PIPE, timeout=120)
            except (OSError, subprocess.TimeoutExpired) as error:
                raise MigrationError("The installed APK could not be read for signer verification.") from error
        if result.returncode:
            raise MigrationError("The installed APK could not be read for signer verification.")
        try:
            result = subprocess.run([apksigner, "verify", "--print-certs", str(target_apk)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise MigrationError("The installed APK signer could not be verified.") from error
        fingerprints = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", result.stdout.decode("utf-8", "replace"))
        if result.returncode or len(fingerprints) != 1 or fingerprints[0].lower() != expected:
            raise MigrationError("The installed LibreAuto signer does not match the expected release. Nothing was replaced.")


def backup_preferences(directory: str, source: dict[str, bytes | None], target: dict[str, bytes | None]) -> Path:
    destination = Path(directory).expanduser().resolve()
    project = Path(__file__).resolve().parents[1]
    forbidden = (project, project.parents[1] / "outputs")
    for location in forbidden:
        try:
            destination.relative_to(location)
        except ValueError:
            continue
        raise MigrationError("Choose a private backup directory outside the source tree and release outputs.")
    destination.mkdir(parents=True, exist_ok=True, mode=0o700)
    private = destination / ("legacy-openauto-" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8])
    private.mkdir(mode=0o700)
    for name, values in (("old-openauto", source), ("libreauto-before", target)):
        folder = private / name
        folder.mkdir(mode=0o700)
        for filename, raw in values.items():
            if raw is not None:
                path = folder / filename
                with path.open("xb") as output:
                    os.chmod(path, 0o600)
                    output.write(raw)
    return private


def apply_plan(device: Device, plan: Plan, original: dict[str, bytes | None]):
    touched = []
    try:
        for name, raw in plan.changes.items():
            touched.append(name)
            device.write_file(name, raw)
            device.write_file(name + ".bak", None)
            if device.read_file(TARGET, name) != raw:
                raise MigrationError("Imported settings did not verify.")
    except Exception as error:
        restored = True
        for name in reversed(touched):
            try:
                device.write_file(name, original.get(name))
                device.write_file(name + ".bak", original.get(name + ".bak"))
            except Exception:
                restored = False
        if not restored:
            raise MigrationError("Migration stopped and rollback was incomplete. Keep LibreAuto closed and use the private backup before retrying.") from error
        raise MigrationError("Migration stopped; the original target preference files were restored. LibreAuto remains closed.") from error


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Force-stop LibreAuto and apply the verified missing-key migration; default is read-only")
    parser.add_argument("--adb", default=shutil.which("adb"), help="Path to Android platform-tools adb")
    parser.add_argument("--serial", help="ADB serial when more than one phone is connected")
    parser.add_argument("--apksigner", help="Android SDK apksigner path; SDK/PATH discovery is automatic")
    parser.add_argument("--expected-signer", default=EXPECTED_SIGNER, help="Expected LibreAuto certificate SHA-256; defaults to this project's compatible debug signer")
    parser.add_argument("--backup-dir", help="Optional private backup parent directory outside source/release outputs; applies only with --apply")
    args = parser.parse_args(argv)
    try:
        if not args.adb:
            raise MigrationError("ADB was not found. Supply --adb PATH or install Android platform-tools.")
        device = Device(args.adb, args.serial)
        device.require_debuggable(SOURCE)
        device.require_debuggable(TARGET)
        verify_target(device, find_apksigner(args.apksigner), args.expected_signer)
        if args.apply:
            device.force_stop_target()
        source, source_physical = device.read_preferences(SOURCE, SOURCE_FILES)
        target, target_physical = device.read_preferences(TARGET, TARGET_FILES)
        plan = build_plan(source, target)
        print(f"{'Apply' if args.apply else 'Dry run'}: {plan.keys_added} missing settings, {plan.places_added} saved places, {len(plan.changes)} preference files.")
        if plan.places_kept:
            print(f"Kept {plan.places_kept} current saved-place pins where the legacy app has the same coordinates.")
        if plan.places_over_capacity:
            print(f"Left {plan.places_over_capacity} legacy saved places in the old app because the shared list is full.")
        if args.apply and plan.changes:
            if args.backup_dir:
                backup_preferences(args.backup_dir, source_physical, target_physical)
                print("Private pre-migration XML backup created.")
            apply_plan(device, plan, target_physical)
            print("Verified. Open LibreAuto normally to load the imported settings.")
        elif not args.apply:
            print("Nothing changed. Add --apply to migrate after reviewing these counts.")
        else:
            print("No changes needed. Existing settings were kept.")
        print("Playlists, audio queue, URI grants, recent/history data, permissions and connection-mode/helper state were not imported. The old app is unchanged.")
        return 0
    except MigrationError as error:
        print(f"Migration stopped: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
