"""Check the actual APK manifest, certificate and embedded provenance before upload."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=Path)
parser.add_argument("code", type=int)
parser.add_argument("name")
args = parser.parse_args()
tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/34.0.0"
badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(args.apk)], text=True).splitlines()[0]
assert "name='dev.hyprdroid.launcher'" in badging, badging
assert f"versionCode='{args.code}'" in badging, badging
assert f"versionName='{args.name}'" in badging, badging
certs = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(args.apk)], text=True)
digests = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", certs)
assert digests == [Path("config/debug-signing-cert.sha256").read_text().strip()], certs
with zipfile.ZipFile(args.apk) as archive:
    identity = json.loads(archive.read("assets/build-info.json"))
expected = dict(applicationId="dev.hyprdroid.launcher", versionCode=args.code, versionName=args.name,
    commit=os.environ["HYPRDROID_BUILD_COMMIT"], runId=os.environ["GITHUB_RUN_ID"], distribution=True)
assert identity == expected, (identity, expected)
metadata = dict(identity, certificateSha256=digests[0], apkSha256=hashlib.sha256(args.apk.read_bytes()).hexdigest(),
                runAttempt=int(os.environ["GITHUB_RUN_ATTEMPT"]), file=args.apk.name)
args.apk.with_suffix(".json").write_text(json.dumps(metadata, indent=2) + "\n")
args.apk.with_suffix(".sha256").write_text(metadata["apkSha256"] + "  " + args.apk.name + "\n")
print(json.dumps(metadata))
