"""Publication-contract tests using synthetic source and APK ZIP fixtures only."""
import base64
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import package_libreauto as packaging


def digest(data):
    return hashlib.sha256(data).hexdigest()


class ReleaseFixture:
    """No device evidence, signing material, SDK or real APK is used by these tests."""
    version = "0.3.0-preview.1"
    code = 20

    def __init__(self, folder):
        self.root = folder / "source"
        self.output_root = folder / "output"
        self.root.mkdir()
        self.expected_source = {}
        for name in packaging.TOP_FILES:
            self.source(name, f"Synthetic public fixture: {name}\n".encode())
        for tree in packaging.SOURCE_TREES:
            (self.root / tree).mkdir(parents=True, exist_ok=True)
            self.source(f"{tree}/fixture/public.txt", f"Public {tree}\n".encode())
        for name in packaging.PUBLIC_DOCS:
            self.source(name, f"Public guide {name}\n".encode())
        self.source("app/build.gradle.kts", (
            f'applicationId = "{packaging.PACKAGE}"\n'
            f'versionName = "{self.version}"\nversionCode = {self.code}\n'
        ).encode())
        self.source("gradle.properties", b"org.gradle.jvmargs=-Xmx2048m\n")
        self.source("gradle/wrapper/gradle-wrapper.properties", b"distributionUrl=https://example.invalid/gradle.zip\n")
        self.source("gradle/wrapper/gradle-wrapper.jar", b"synthetic wrapper fixture")
        self.source("tools/check.sh", b"#!/bin/sh\nexit 0\n")
        (self.root / "gradlew").chmod(0o755)
        (self.root / "tools/check.sh").chmod(0o755)
        # These expected protocol fields are data, not build credentials.
        self.source("projection/src/test/resources/protocol.properties", b"credentials=synthetic-protocol-vector\n")
        self.apk = self.root / "app/build/outputs/apk/debug/app-debug.apk"
        self.apk.parent.mkdir(parents=True)
        self.write_apk()
        self.receipt = self.root / "evidence/release-audit.json"
        self.receipt.parent.mkdir()
        self.valid_receipt = {
            "schema": packaging.SCHEMA, "final": True,
            "required_checks_passed": True, "package": packaging.PACKAGE,
            "version_name": self.version, "version_code": self.code,
            "signer_sha256": packaging.SIGNER,
            **{field: packaging.sha256(self.apk) for field in (
                "apk_sha256", "installed_apk_sha256", "tested_apk_sha256")},
        }
        self.write_receipt()
        self.source("TESTED.md", f"Synthetic {self.version} {packaging.sha256(self.apk)}\n".encode())

    def source(self, name, data):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        self.expected_source[name] = data

    def ignored(self, name, data=b"EXCLUDED-SYNTHETIC-DATA"):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def write_apk(self, abi="arm64-v8a"):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"synthetic binary manifest fixture")
            archive.writestr(f"lib/{abi}/libfixture.so", b"synthetic native fixture")

    def write_receipt(self, **changes):
        self.receipt.write_text(json.dumps({**self.valid_receipt, **changes}))

    def package(self, screenshots=None):
        return packaging.package(self.root, self.apk, self.receipt, self.output_root, screenshots or [])

    @property
    def output(self):
        return self.output_root / f"LibreAuto-{self.version}"


class PackagePublicationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="libreauto-package-test-")
        self.addCleanup(self.temporary.cleanup)
        self.fixture = ReleaseFixture(Path(self.temporary.name))

    def assert_no_published_or_partial_output(self):
        self.assertFalse(self.fixture.output.exists())
        if self.fixture.output_root.exists():
            self.assertEqual([], list(self.fixture.output_root.iterdir()))

    def test_complete_public_source_and_only_explicit_screenshot_are_verified(self):
        fixture = self.fixture
        for name in (
            "private/identity.txt", "evidence/device-report.json", "build/generated.txt",
            "tools/private/local.txt", "app/src/evidence/debug.txt", "tools/__pycache__/cache.pyc",
            "tools/.hidden", "tools/.env", "tools/local.properties", "tools/keystore.properties",
            "tools/node_modules/dependency.txt", "projection/src/build/generated.kt",
            "app/src/main/unused.so", "tools/package_preview.py", "unlisted.txt",
        ):
            fixture.ignored(name)
        fixture.ignored("evidence/not-selected.png", b"\x89PNG\r\n\x1a\nnot selected")
        png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
        fixture.ignored("evidence/reviewed.png", png)
        result = fixture.package(["evidence/reviewed.png=Player.png"])
        self.assertEqual(packaging.sha256(fixture.apk), result["apk_sha256"])
        self.assertEqual(len(fixture.expected_source), result["source_files"])
        with zipfile.ZipFile(fixture.output / "LibreAuto-source.zip") as archive:
            manifest = json.loads(archive.read("LibreAuto-source/SOURCE-SHA256.json"))
            self.assertEqual(set(fixture.expected_source), set(manifest))
            self.assertEqual(len(manifest) + 1, len(archive.namelist()))
            self.assertIsNone(archive.testzip())
            for name, data in fixture.expected_source.items():
                self.assertEqual(data, archive.read(f"LibreAuto-source/{name}"), name)
                self.assertEqual(digest(data), manifest[name], name)
            for name in ("gradlew", "tools/check.sh"):
                self.assertTrue((archive.getinfo(f"LibreAuto-source/{name}").external_attr >> 16) & 0o111)
        for name in packaging.PUBLIC_DOCS:
            self.assertEqual(fixture.expected_source[name], (fixture.output / name).read_bytes())
        self.assertFalse((fixture.output / "docs/fixture/public.txt").exists())
        for name in ("README.md", "UPSTREAM.md", "TESTED.md", "LICENSE", "CONTRIBUTING.md", "CHANGELOG.md"):
            self.assertEqual(fixture.expected_source[name], (fixture.output / name).read_bytes())
        self.assertEqual(["Player.png"], [p.name for p in (fixture.output / "Screenshots").iterdir()])
        self.assertEqual(png, (fixture.output / "Screenshots/Player.png").read_bytes())
        actual = {p.relative_to(fixture.output).as_posix(): digest(p.read_bytes())
                  for p in fixture.output.rglob("*") if p.is_file() and p.name != "SHA256SUMS.txt"}
        sums = {}
        for line in (fixture.output / "SHA256SUMS.txt").read_text().splitlines():
            value, name = line.split("  ", 1)
            sums[name] = value
        self.assertEqual(actual, sums)
        self.assertEqual(actual, result["files"])
        self.assertFalse(any("EXCLUDED-SYNTHETIC-DATA" in p.read_bytes().decode(errors="ignore")
                             for p in fixture.output.rglob("*") if p.is_file()))

    def test_receipt_must_match_package_version_signer_and_each_final_apk_hash(self):
        changes = {
            "package": "org.example.other", "version_name": "0.3.0-other", "version_code": True,
            "signer_sha256": "0" * 64, "apk_sha256": "0" * 64,
            "installed_apk_sha256": "0" * 64, "tested_apk_sha256": "0" * 64,
            "final": False, "required_checks_passed": False,
        }
        for field, value in changes.items():
            with self.subTest(field=field):
                self.fixture.write_receipt(**{field: value})
                with self.assertRaises(packaging.PackagingError):
                    self.fixture.package()
                self.assert_no_published_or_partial_output()

    def test_unexpected_apk_abi_is_rejected_even_with_matching_receipt_hash(self):
        fixture = self.fixture
        fixture.write_apk("x86_64")
        value = packaging.sha256(fixture.apk)
        fixture.write_receipt(apk_sha256=value, installed_apk_sha256=value, tested_apk_sha256=value)
        with self.assertRaisesRegex(packaging.PackagingError, "ARM64"):
            fixture.package()
        self.assert_no_published_or_partial_output()

    def test_secret_files_private_key_payload_and_sensitive_build_property_are_refused(self):
        fixture = self.fixture
        cases = (
            ("tools/signing.pem", b"synthetic certificate extension"),
            ("tools/unexpected.jar", b"synthetic binary dependency"),
            ("app/src/main/key.txt", b"-----BEGIN PRIVATE KEY-----\nsynthetic\n-----END PRIVATE KEY-----\n"),
            ("gradle.properties", b"release.password=synthetic-only\n"),
        )
        for name, data in cases:
            with self.subTest(name=name):
                path = fixture.root / name
                original = path.read_bytes() if path.exists() else None
                fixture.ignored(name, data)
                with self.assertRaises(packaging.PackagingError):
                    fixture.package()
                self.assert_no_published_or_partial_output()
                if original is None:
                    path.unlink()
                else:
                    path.write_bytes(original)

    def test_file_and_directory_links_cannot_import_data_from_outside_source_tree(self):
        outside = Path(self.temporary.name) / "outside"
        outside.mkdir()
        (outside / "payload.txt").write_text("SYNTHETIC-OUTSIDE-DATA")
        for target in (outside / "payload.txt", outside):
            with self.subTest(directory=target.is_dir()):
                link = self.fixture.root / "tools/linked-payload"
                link.symlink_to(target, target_is_directory=target.is_dir())
                with self.assertRaisesRegex(packaging.PackagingError, "link"):
                    self.fixture.package()
                self.assert_no_published_or_partial_output()
                link.unlink()

    def test_existing_release_is_never_overwritten(self):
        self.fixture.package()
        original = {p.relative_to(self.fixture.output): p.read_bytes()
                    for p in self.fixture.output.rglob("*") if p.is_file()}
        with self.assertRaisesRegex(packaging.PackagingError, "overwrite"):
            self.fixture.package()
        self.assertEqual(original, {p.relative_to(self.fixture.output): p.read_bytes()
                                    for p in self.fixture.output.rglob("*") if p.is_file()})

    def test_missing_required_public_guide_does_not_leave_publishable_output(self):
        guide = self.fixture.root / packaging.PUBLIC_DOCS[0]
        guide.unlink()
        with self.assertRaisesRegex(packaging.PackagingError, "guide.*absent"):
            self.fixture.package()
        self.assert_no_published_or_partial_output()

    def test_public_guide_copy_must_match_verified_source_archive(self):
        guide = self.fixture.root / packaging.PUBLIC_DOCS[0]
        original_copy = packaging.shutil.copyfile

        def corrupt_guide_copy(source, target, *args, **kwargs):
            result = original_copy(source, target, *args, **kwargs)
            if Path(source) == guide:
                Path(target).write_text("SYNTHETIC-CORRUPTED-COPY")
            return result

        with mock.patch.object(packaging.shutil, "copyfile", side_effect=corrupt_guide_copy):
            with self.assertRaisesRegex(packaging.PackagingError, "guide changed"):
                self.fixture.package()
        self.assert_no_published_or_partial_output()


if __name__ == "__main__":
    unittest.main()
