#!/usr/bin/env python3
"""Validate native libraries, shared-runtime AARs and runtime brand resources in Android packages."""

from __future__ import annotations

import struct
import sys
from io import BytesIO
from pathlib import Path
from zipfile import BadZipFile, ZipFile

EXPECTED_RUNTIME_LIBRARIES = {
    "libc++_shared.so",
    "libggml-base.so",
    "libggml-cpu-android_armv8.0_1.so",
    "libggml-cpu-android_armv8.2_1.so",
    "libggml-cpu-android_armv8.2_2.so",
    "libggml-cpu-android_armv8.6_1.so",
    "libggml-cpu-android_armv9.0_1.so",
    "libggml-cpu-android_armv9.2_1.so",
    "libggml-cpu-android_armv9.2_2.so",
    "libggml.so",
    "libllama-common.so",
    "libllama.so",
    "liblocal_llm_jni.so",
    # JNI links the pinned llama.cpp multimodal projector in every embedded runtime package.
    "libmtmd.so",
}
PHONE_TEST_NATIVE_LIBRARIES = EXPECTED_RUNTIME_LIBRARIES | {
    "libandroidx.graphics.path.so",
}
EXPECTED_BRAND_RESOURCES = {
    "harness_launcher_background",
    "harness_launcher_foreground",
    "harness_launcher_monochrome",
    "ic_launcher",
    "ic_launcher_round",
}
EXPECTED_BINDER_CLIENT_CLASSES = {
    "io/github/daniele21/localllm/transport/binder/client/BinderLocalLlmClient.class",
    "io/github/daniele21/localllm/transport/binder/client/SharedRuntimeHostConfig.class",
    "io/github/daniele21/localllm/transport/binder/client/SharedRuntimeConnectionState.class",
    "io/github/daniele21/localllm/transport/binder/client/SharedRuntimeConnectionSnapshot.class",
    "io/github/daniele21/localllm/transport/binder/client/SharedRuntimeConnectionObserver.class",
}
FORBIDDEN_CLIENT_ARTIFACT_SUFFIXES = (".so", ".gguf", ".ggml")
EXPECTED_ABI = "arm64-v8a"
EM_AARCH64 = 183
ELF_HEADER_BYTES = 20


class PackagingError(RuntimeError):
    """Raised when an Android archive does not match the expected layout."""


def find_single(root: Path, pattern: str, label: str) -> Path:
    matches = sorted(root.glob(pattern))
    if len(matches) != 1:
        rendered = ", ".join(str(path) for path in matches) or "none"
        raise PackagingError(f"Expected one {label}; found {len(matches)}: {rendered}")
    return matches[0]


def find_optional_single(root: Path, pattern: str, label: str) -> Path | None:
    matches = sorted(root.glob(pattern))
    if len(matches) > 1:
        rendered = ", ".join(str(path) for path in matches)
        raise PackagingError(f"Expected at most one {label}; found {len(matches)}: {rendered}")
    return matches[0] if matches else None


def native_entries(archive: ZipFile, prefix: str) -> list[str]:
    return sorted(
        name
        for name in archive.namelist()
        if name.startswith(prefix) and name.endswith(".so")
    )


def verify_aarch64_elf(archive: ZipFile, entry: str) -> None:
    with archive.open(entry) as library:
        header = library.read(ELF_HEADER_BYTES)
    if len(header) < ELF_HEADER_BYTES or header[:4] != b"\x7fELF":
        raise PackagingError(f"{entry} is not an ELF shared library")
    if header[4] != 2:
        raise PackagingError(f"{entry} is not a 64-bit ELF library")
    byte_order = "<" if header[5] == 1 else ">" if header[5] == 2 else None
    if byte_order is None:
        raise PackagingError(f"{entry} has an unsupported ELF byte order")
    machine = struct.unpack_from(f"{byte_order}H", header, 18)[0]
    if machine != EM_AARCH64:
        raise PackagingError(f"{entry} targets ELF machine {machine}, expected AArch64")


def verify_native_archive(
    path: Path,
    prefix: str,
    label: str,
    expected_libraries: set[str] = EXPECTED_RUNTIME_LIBRARIES,
) -> None:
    try:
        with ZipFile(path) as archive:
            entries = native_entries(archive, prefix)
            if not entries:
                raise PackagingError(f"{label} contains no native libraries")

            abi_values = {entry.split("/")[1] for entry in entries}
            if abi_values != {EXPECTED_ABI}:
                raise PackagingError(
                    f"{label} contains unexpected ABIs: {sorted(abi_values)}"
                )

            library_names = [Path(entry).name for entry in entries]
            if len(library_names) != len(set(library_names)):
                raise PackagingError(f"{label} contains duplicate native library names")

            actual_libraries = set(library_names)
            missing = sorted(expected_libraries - actual_libraries)
            unexpected = sorted(actual_libraries - expected_libraries)
            if missing or unexpected:
                raise PackagingError(
                    f"{label} native library mismatch; missing={missing}, unexpected={unexpected}"
                )

            for entry in entries:
                verify_aarch64_elf(archive, entry)
    except BadZipFile as error:
        raise PackagingError(f"{label} is not a valid ZIP archive: {path}") from error

    print(f"Verified {label}: {path}")
    print(f"  ABI: {EXPECTED_ABI}")
    print(f"  native libraries: {len(expected_libraries)}")


def verify_binder_client_aar(path: Path) -> None:
    label = "shared-runtime Binder client debug AAR"
    try:
        with ZipFile(path) as archive:
            entries = set(archive.namelist())
            forbidden = sorted(
                name
                for name in entries
                if name.lower().endswith(FORBIDDEN_CLIENT_ARTIFACT_SUFFIXES)
            )
            if forbidden:
                raise PackagingError(
                    f"{label} contains forbidden runtime/model artifacts: {forbidden}"
                )

            if "proguard.txt" not in entries:
                raise PackagingError(f"{label} does not contain consumer shrinker rules")
            proguard = archive.read("proguard.txt").decode("utf-8")
            if "io.github.daniele21.localllm.transport.binder.contract" not in proguard:
                raise PackagingError(f"{label} consumer rules do not preserve the Binder contract")

            if "classes.jar" not in entries:
                raise PackagingError(f"{label} does not contain classes.jar")
            with ZipFile(BytesIO(archive.read("classes.jar"))) as classes:
                class_entries = set(classes.namelist())
    except BadZipFile as error:
        raise PackagingError(f"{label} is not a valid ZIP archive: {path}") from error

    missing = sorted(EXPECTED_BINDER_CLIENT_CLASSES - class_entries)
    if missing:
        raise PackagingError(f"{label} is missing reviewed public client classes: {missing}")

    print(f"Verified {label}: {path}")
    print(f"  reviewed public classes: {len(EXPECTED_BINDER_CLIENT_CLASSES)}")
    print("  consumer shrinker rules: present")
    print("  native/model artifacts: none")


def resource_name_present(resource_table: bytes, name: str) -> bool:
    return name.encode("utf-8") in resource_table or name.encode("utf-16le") in resource_table


def verify_phone_brand_resources(path: Path, table_entry: str, label: str) -> None:
    try:
        with ZipFile(path) as archive:
            if table_entry not in archive.namelist():
                raise PackagingError(f"{label} does not contain {table_entry}")
            resource_table = archive.read(table_entry)
    except BadZipFile as error:
        raise PackagingError(f"{label} is not a valid ZIP archive: {path}") from error

    missing = sorted(
        name
        for name in EXPECTED_BRAND_RESOURCES
        if not resource_name_present(resource_table, name)
    )
    if missing:
        raise PackagingError(
            f"{label} is missing Harness launcher resources in {table_entry}: {missing}"
        )
    print(f"Verified Harness launcher resources in {label}: {path}")


def verify_instrumentation_apk(path: Path) -> None:
    try:
        with ZipFile(path) as archive:
            entries = native_entries(archive, "lib/")
    except BadZipFile as error:
        raise PackagingError(f"Instrumentation APK is not a valid ZIP archive: {path}") from error
    if entries:
        raise PackagingError(
            "Instrumentation APK unexpectedly packages native libraries: "
            + ", ".join(entries)
        )
    print(f"Verified instrumentation APK has no duplicate native payload: {path}")


def main() -> int:
    repository = Path(__file__).resolve().parents[1]
    device_application_apk = find_single(
        repository,
        "apps/device-test-runner/build/outputs/apk/debug/*-debug.apk",
        "device-test application APK",
    )
    phone_application_apk = find_single(
        repository,
        "apps/local-llm-phone-test/build/outputs/apk/debug/*-debug.apk",
        "phone-test application APK",
    )
    phone_application_aab = find_optional_single(
        repository,
        "apps/local-llm-phone-test/build/outputs/bundle/**/*.aab",
        "phone-test application AAB",
    )
    instrumentation_apk = find_single(
        repository,
        "apps/device-test-runner/build/outputs/apk/androidTest/debug/*-androidTest.apk",
        "device-test instrumentation APK",
    )
    backend_aar = find_single(
        repository,
        "backends/llama-cpp/build/outputs/aar/llama-cpp-debug.aar",
        "llama.cpp debug AAR",
    )
    binder_client_aar = find_single(
        repository,
        "transports/android-binder-client/build/outputs/aar/android-binder-client-debug.aar",
        "shared-runtime Binder client debug AAR",
    )

    verify_native_archive(device_application_apk, "lib/", "device-test application APK")
    verify_native_archive(
        phone_application_apk,
        "lib/",
        "phone-test application APK",
        PHONE_TEST_NATIVE_LIBRARIES,
    )
    verify_phone_brand_resources(
        phone_application_apk,
        "resources.arsc",
        "phone-test application APK",
    )
    if phone_application_aab is not None:
        verify_phone_brand_resources(
            phone_application_aab,
            "base/resources.pb",
            "phone-test application AAB",
        )
    else:
        print("Phone-test AAB not present in this scoped build; APK brand check completed.")

    verify_instrumentation_apk(instrumentation_apk)
    verify_native_archive(backend_aar, "jni/", "llama.cpp debug AAR")
    verify_binder_client_aar(binder_client_aar)
    print("Android packaging verification completed successfully")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except PackagingError as error:
        print(f"Android packaging verification failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error
