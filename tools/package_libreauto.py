#!/usr/bin/env python3
"""Stage an already tested LibreAuto APK and an explicit, credential-free source allowlist."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "org.bluetoothauto"
SIGNER = "aa6d91b1bbeff93b792bd38440721fc0f38392c2795253e0c0e2630545bfd414"
SCHEMA = "libreauto-release-audit-v1"
TOP_FILES = (
    ".gitignore", ".gitattributes", "README.md", "UPSTREAM.md", "TESTED.md", "LICENSE",
    "CONTRIBUTING.md", "CHANGELOG.md",
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
    "app/build.gradle.kts", "projection/build.gradle.kts", "projection/consumer-rules.pro",
    "projection/LICENSE", "projection/README.md",
)
SOURCE_TREES = ("app/src", "projection/src", "projection/docs", "third_party", "gradle/wrapper", "docs", "tools", ".github")
# These guides are already verified source members. No arbitrary documentation tree,
# private report or future file is copied beside the outer release README.
PUBLIC_DOCS = (
    "docs/BUILDING.md", "docs/mode-switching.md", "docs/car-profiles.md",
    "docs/legacy-openauto-migration.md", "docs/bluetoothauto-player-reference.md",
    "docs/official-projection-monitor.md",
)
SKIP_PARTS = frozenset({"private", "evidence", "build", ".gradle", ".kotlin", ".git", "__pycache__", ".pytest_cache", ".mypy_cache", "node_modules"})
KEY_SUFFIXES = frozenset({".pem", ".key", ".jks", ".keystore", ".p12", ".pfx", ".pkcs12", ".der", ".cer", ".crt"})
COMPILED_SUFFIXES = frozenset({".pyc", ".pyo", ".class", ".apk", ".aab", ".aar", ".dex", ".so", ".o", ".a"})
PRIVATE_NAMES = frozenset({"local.properties", "keystore.properties", "signing.properties", ".env", ".ds_store"})
PEM_PRIVATE_KEY = re.compile(rb"(?m)^\s*-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----\s*$")
HEX = re.compile(r"[0-9a-f]{64}\Z")


class PackagingError(ValueError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def project_path(value: str | Path, root: Path = ROOT) -> Path:
    path = Path(value).expanduser()
    return path if path.is_absolute() else root / path


def regular_file(path: Path) -> None:
    if path.is_symlink() or not path.is_file():
        raise PackagingError(f"Expected a regular file, not a link: {path}")


def build_identity(root: Path) -> tuple[str, int]:
    source = (root / "app/build.gradle.kts").read_text()
    package = re.search(r'applicationId\s*=\s*"([^"]+)"', source)
    version = re.search(r'versionName\s*=\s*"([^"]+)"', source)
    code = re.search(r'versionCode\s*=\s*(\d+)', source)
    if not package or package.group(1) != PACKAGE or not version or not code:
        raise PackagingError("Cannot verify the unified app's package/version declaration")
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?", version.group(1)):
        raise PackagingError("Unsafe or unsupported version name")
    if int(code.group(1)) < 20:
        raise PackagingError("This packager requires the unified LibreAuto app")
    return version.group(1), int(code.group(1))


def verify_receipt(receipt_path: Path, apk: Path, version: str, code: int) -> str:
    regular_file(receipt_path)
    regular_file(apk)
    if receipt_path.stat().st_size > 1024 * 1024:
        raise PackagingError("Audit receipt exceeds the supported size")
    receipt = json.loads(receipt_path.read_text())
    if not isinstance(receipt, dict):
        raise PackagingError("Audit receipt must be an object")
    expected = {"schema": SCHEMA, "package": PACKAGE, "version_name": version, "version_code": code, "signer_sha256": SIGNER}
    for field, value in expected.items():
        if receipt.get(field) != value or type(receipt.get(field)) is not type(value):
            raise PackagingError(f"Audit receipt has the wrong {field}")
    if receipt.get("final") is not True or receipt.get("required_checks_passed") is not True:
        raise PackagingError("A final audit with actual required checks passed is required")
    digest = sha256(apk)
    for field in ("apk_sha256", "installed_apk_sha256", "tested_apk_sha256"):
        value = receipt.get(field)
        if not isinstance(value, str) or not HEX.fullmatch(value) or value != digest:
            raise PackagingError(f"Final APK does not match the audit's {field}")
    try:
        with zipfile.ZipFile(apk) as archive:
            abis = {name.split("/")[1] for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")}
            if "AndroidManifest.xml" not in archive.namelist() or abis != {"arm64-v8a"}:
                raise PackagingError("The supplied artifact must be the ARM64 APK")
    except zipfile.BadZipFile as error:
        raise PackagingError("The supplied artifact is not a readable APK ZIP") from error
    return digest


def source_files(root: Path) -> list[Path]:
    paths: set[Path] = set()
    for name in TOP_FILES:
        path = root / name
        regular_file(path)
        paths.add(path)
    for folder in SOURCE_TREES:
        directory = root / folder
        if directory.is_symlink() or not directory.is_dir():
            raise PackagingError(f"Missing or linked source directory: {folder}")
        for base, directories, files in os.walk(directory, followlinks=False):
            base_path = Path(base)
            for name in directories[:]:
                candidate = base_path / name
                if name.lower() in SKIP_PARTS or name.startswith("."):
                    directories.remove(name)
                elif candidate.is_symlink():
                    raise PackagingError(f"Source directory links are not permitted: {candidate.relative_to(root)}")
            for name in files:
                path = base_path / name
                relative = path.relative_to(root)
                if name.lower() in PRIVATE_NAMES or name.startswith(".") or path.suffix.lower() in COMPILED_SUFFIXES:
                    continue
                if path.suffix.lower() in KEY_SUFFIXES:
                    raise PackagingError(f"Credential/certificate file is not allowed in source staging: {relative}")
                if path.suffix.lower() == ".jar" and relative.as_posix() != "gradle/wrapper/gradle-wrapper.jar":
                    raise PackagingError(f"Only the Gradle wrapper JAR is allowed: {relative}")
                # The retired single-app packager cannot stage this unified release correctly.
                if relative.as_posix() == "tools/package_preview.py":
                    continue
                regular_file(path)
                paths.add(path)
    for path in paths:
        if path.stat().st_size > 32 * 1024 * 1024:
            raise PackagingError(f"Source file exceeds the supported size: {path.relative_to(root)}")
        if PEM_PRIVATE_KEY.search(path.read_bytes()):
            raise PackagingError(f"Private-key payload detected: {path.relative_to(root)}")
        # Restrict this check to build configuration. Protocol tests intentionally contain
        # synthetic fields called "credentials" and must remain in the complete test source.
        if path.relative_to(root).as_posix() in {"gradle.properties", "gradle/wrapper/gradle-wrapper.properties"}:
            for line in path.read_text().splitlines():
                if line.lstrip().startswith(("#", "!")):
                    continue
                match = re.match(r"\s*([^=:]+)\s*[=:]", line)
                if match and re.search(r"password|passwd|token|secret|credential|api[._-]?key|access[._-]?key|private[._-]?key|keystore", match.group(1), re.IGNORECASE):
                    raise PackagingError(f"Sensitive property is not permitted in source staging: {path.relative_to(root)}")
    if sum(path.stat().st_size for path in paths) > 128 * 1024 * 1024:
        raise PackagingError("Source allowlist exceeds the supported size")
    return sorted(paths, key=lambda path: path.relative_to(root).as_posix())


def screenshot_inputs(values: list[str], root: Path) -> list[tuple[Path, str]]:
    result: list[tuple[Path, str]] = []
    names: set[str] = set()
    for value in values:
        if "=" not in value:
            raise PackagingError("Each screenshot must be an explicit SOURCE=Filename.png pair")
        source, name = value.rsplit("=", 1)
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._ -]*\.(?:png|jpg|jpeg|webp)", name, re.IGNORECASE):
            raise PackagingError("Screenshot output must be a plain PNG/JPEG/WebP filename")
        if name.casefold() in names:
            raise PackagingError("Duplicate screenshot output filename")
        names.add(name.casefold())
        path = project_path(source, root)
        regular_file(path)
        if path.stat().st_size > 32 * 1024 * 1024:
            raise PackagingError("Screenshot exceeds the supported size")
        header = path.read_bytes()[:12]
        suffix = Path(name).suffix.lower()
        valid = (suffix == ".png" and header.startswith(b"\x89PNG\r\n\x1a\n")) or (
            suffix in {".jpg", ".jpeg"} and header.startswith(b"\xff\xd8\xff")) or (
            suffix == ".webp" and header.startswith(b"RIFF") and header[8:12] == b"WEBP")
        if not valid:
            raise PackagingError("Screenshot bytes do not match the selected image type")
        result.append((path, name))
    return result


def write_source_zip(target: Path, paths: list[Path], root: Path) -> dict[str, str]:
    manifest: dict[str, str] = {}
    with zipfile.ZipFile(target, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in paths:
            name = path.relative_to(root).as_posix()
            data = path.read_bytes()
            manifest[name] = hashlib.sha256(data).hexdigest()
            info = zipfile.ZipInfo(f"LibreAuto-source/{name}", date_time=(2000, 1, 1, 0, 0, 0))
            info.create_system = 3
            mode = 0o755 if name == "gradlew" or (path.stat().st_mode & 0o111 and name.startswith("tools/")) else 0o644
            info.external_attr = (stat.S_IFREG | mode) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
        archive.writestr("LibreAuto-source/SOURCE-SHA256.json", json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    with zipfile.ZipFile(target) as archive:
        if archive.testzip() is not None:
            raise PackagingError("Source ZIP checksum verification failed")
        expected_names = {f"LibreAuto-source/{name}" for name in manifest} | {"LibreAuto-source/SOURCE-SHA256.json"}
        if len(archive.namelist()) != len(expected_names) or set(archive.namelist()) != expected_names:
            raise PackagingError("Source ZIP contains unexpected or duplicate entries")
        for name, digest in manifest.items():
            if hashlib.sha256(archive.read(f"LibreAuto-source/{name}")).hexdigest() != digest or sha256(root / name) != digest:
                raise PackagingError(f"Source changed or archive verification failed: {name}")
        if not (archive.getinfo("LibreAuto-source/gradlew").external_attr >> 16) & 0o111:
            raise PackagingError("Gradle wrapper executable permission was lost")
    return manifest


def package(root: Path, apk: Path, receipt: Path, output_root: Path, screenshots: list[str]) -> dict:
    version, code = build_identity(root)
    digest = verify_receipt(receipt, apk, version, code)
    tested = (root / "TESTED.md").read_text()
    if version not in tested or digest not in tested:
        raise PackagingError("TESTED.md must identify this exact final version and APK SHA-256")
    paths = source_files(root)
    images = screenshot_inputs(screenshots, root)
    output = output_root / f"LibreAuto-{version}"
    if output.exists() or output.is_symlink():
        raise PackagingError(f"Refusing to overwrite release output: {output}")
    output_root.mkdir(parents=True, exist_ok=True)
    # Build in a private temporary directory. Reserve the final directory exclusively only
    # after every artifact verifies; failures cannot leave a package that looks complete.
    with tempfile.TemporaryDirectory(prefix=".libreauto-staging-", dir=output_root) as temporary:
        staged = Path(temporary)
        artifact = staged / f"LibreAuto-{version}-arm64.apk"
        shutil.copyfile(apk, artifact)
        if sha256(artifact) != digest:
            raise PackagingError("APK changed while copying")
        for name in ("README.md", "UPSTREAM.md", "TESTED.md", "LICENSE", "CONTRIBUTING.md", "CHANGELOG.md"):
            shutil.copyfile(root / name, staged / name)
        manifest = write_source_zip(staged / "LibreAuto-source.zip", paths, root)
        for name in ("README.md", "UPSTREAM.md", "TESTED.md", "LICENSE", "CONTRIBUTING.md", "CHANGELOG.md"):
            if sha256(staged / name) != manifest[name]:
                raise PackagingError(f"Top-level documentation changed while packaging: {name}")
        for name in PUBLIC_DOCS:
            if name not in manifest:
                raise PackagingError(f"Public guide is absent from the verified source allowlist: {name}")
            target = staged / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(root / name, target)
            if sha256(target) != manifest[name]:
                raise PackagingError(f"Public guide changed while packaging: {name}")
        for source, name in images:
            directory = staged / "Screenshots"
            directory.mkdir(exist_ok=True)
            shutil.copyfile(source, directory / name)
        sums = {path.relative_to(staged).as_posix(): sha256(path) for path in sorted(staged.rglob("*")) if path.is_file()}
        (staged / "SHA256SUMS.txt").write_text("".join(f"{value}  {name}\n" for name, value in sums.items()))
        # Recheck the audited artifact/receipt after source staging as well.
        if verify_receipt(receipt, apk, version, code) != digest:
            raise PackagingError("Final APK changed while packaging")
        output.mkdir(exist_ok=False)
        try:
            for item in staged.iterdir():
                item.rename(output / item.name)
        except Exception:
            # The directory was exclusively created by this invocation; preserve an explicit
            # marker if an unusual filesystem error interrupts the final file moves.
            (output / "INCOMPLETE-PACKAGE.txt").write_text("Packaging did not finish. Do not distribute this directory.\n")
            raise
    return {"folder": str(output), "apk_sha256": digest, "source_files": len(manifest), "files": sums}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit-receipt", required=True, help="Private final audit JSON; never included in output")
    parser.add_argument("--apk", default="app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--output-root", type=Path, default=ROOT.parents[1] / "outputs")
    parser.add_argument("--screenshot", action="append", default=[], metavar="SOURCE=Filename.png", help="Explicitly reviewed public screenshot; repeat to add more")
    args = parser.parse_args()
    try:
        result = package(ROOT, project_path(args.apk), project_path(args.audit_receipt), project_path(args.output_root), args.screenshot)
    except (PackagingError, OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"Packaging refused: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
