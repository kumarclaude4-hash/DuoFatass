#!/usr/bin/env python3
"""
Regenerate app/libs/libsignal-client-0.54.1-stripped.jar

Run from the repo root:
    python3 scripts/strip_signal_records.py

Requires the JAR to already exist in the Gradle cache (i.e. run
./gradlew :app:compileDebugJavaWithJavac at least once so Gradle downloads it).

WHY THIS EXISTS
libsignal-client-0.54.1.jar contains 6 classes that extend java.lang.Record
(a Java 16 sealed class). When coreLibraryDesugaringEnabled=true and minSdk<34,
D8 must produce a "global synthetic" helper for Record desugaring.
The DexingNoClasspathTransform that processes external Maven JARs runs with
enableGlobalSynthetics=false, causing a fatal build error:
  "Attempt to create a global synthetic for 'Record desugaring' without a consumer"
The stripped JAR (which Gradle picks up as a local file, not a Maven artifact)
goes through a different transform path that does NOT have this restriction.
All 6 removed classes are from the libsignal.net and libsignal.zkgroup packages
which DuoShield does not use.
"""

import glob
import hashlib
import zipfile
import os
import sys

STRIP = {
    "org/signal/libsignal/net/ChatService$DebugInfo.class",
    "org/signal/libsignal/net/ChatService$Request.class",
    "org/signal/libsignal/net/ChatService$Response.class",
    "org/signal/libsignal/net/ChatService$ResponseAndDebugInfo.class",
    "org/signal/libsignal/net/Svr3$RestoredSecret.class",
    "org/signal/libsignal/zkgroup/groupsend/GroupSendEndorsementsResponse$ReceivedEndorsements.class",
}

GRADLE_CACHE = os.path.expanduser(
    "~/.gradle/caches/modules-2/files-2.1/org.signal/libsignal-client/0.54.1"
)
DEST = "app/libs/libsignal-client-0.54.1-stripped.jar"


def find_source_jar():
    pattern = os.path.join(GRADLE_CACHE, "**", "libsignal-client-0.54.1.jar")
    matches = glob.glob(pattern, recursive=True)
    if not matches:
        sys.exit(
            f"Source JAR not found under {GRADLE_CACHE}.\n"
            "Run: ANDROID_HOME=/home/runner/android-sdk ./gradlew :app:compileDebugJavaWithJavac --no-daemon\n"
            "to trigger the Gradle download, then re-run this script."
        )
    return matches[0]


def main():
    src = find_source_jar()
    os.makedirs("app/libs", exist_ok=True)

    removed = []
    kept = 0
    with zipfile.ZipFile(src, "r") as z_in, zipfile.ZipFile(DEST, "w", zipfile.ZIP_DEFLATED) as z_out:
        for item in z_in.infolist():
            if item.filename in STRIP:
                removed.append(item.filename)
            else:
                z_out.writestr(item, z_in.read(item.filename))
                kept += 1

    expected_removed = STRIP.intersection(removed)
    missing = STRIP.difference(removed)
    if missing:
        raise RuntimeError(
            "Refusing to publish an incomplete stripped JAR; missing expected classes: "
            + ", ".join(sorted(missing))
        )
    if expected_removed != STRIP:
        raise RuntimeError("Stripped JAR removal set does not match STRIP")

    with open(DEST, "rb") as jar_file:
        output_sha256 = hashlib.sha256(jar_file.read()).hexdigest()

    print(f"Source : {src}")
    print(f"Output : {DEST}")
    print(f"SHA256 : {output_sha256}")
    print(f"Kept   : {kept} entries")
    print(f"Removed: {len(removed)} Record classes")
    for r in removed:
        print(f"  - {r}")


if __name__ == "__main__":
    main()
