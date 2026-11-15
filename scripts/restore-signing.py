import base64
import hashlib
import os
from pathlib import Path
import subprocess

encoded = os.environ.get("HYPRDROID_KEYSTORE_BASE64", "")
if not encoded or not os.environ.get("HYPRDROID_KEYSTORE_PASSWORD"):
    raise SystemExit("Persistent signing secrets missing; no ephemeral distribution fallback")
destination = Path(os.environ["RUNNER_TEMP"]) / "hyprdroid-signing.p12"
descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(descriptor, "wb") as stream:
    stream.write(base64.b64decode(encoded, validate=True))
certificate = subprocess.check_output(["keytool", "-exportcert", "-keystore", str(destination), "-storetype", "PKCS12",
    "-alias", "hyprdroid-ci-debug", "-storepass:env", "HYPRDROID_KEYSTORE_PASSWORD"])
if hashlib.sha256(certificate).hexdigest() != Path("config/debug-signing-cert.sha256").read_text().strip():
    raise SystemExit("Signing certificate mismatch; refusing distribution")
print("Persistent signing certificate matches the public fingerprint")
with Path(os.environ["GITHUB_ENV"]).open("a") as output:
    output.write(f"HYPRDROID_KEYSTORE_FILE={destination}\n")
