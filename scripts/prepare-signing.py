"""One-time candidate generation. Only GitHub-sealed ciphertext and public cert leave the runner."""
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile

from nacl.public import PublicKey, SealedBox

public_key = PublicKey(base64.b64decode(os.environ["SIGNING_PUBLIC_KEY"], validate=True))
key_id = os.environ["SIGNING_PUBLIC_KEY_ID"]
output = Path(".local/signing-bootstrap")
output.mkdir(parents=True, exist_ok=False)
box = SealedBox(public_key)
with tempfile.TemporaryDirectory(prefix="hyprdroid-key-", dir=os.environ["RUNNER_TEMP"]) as private:
    password = secrets.token_urlsafe(48)
    print(f"::add-mask::{password}", flush=True)
    environment = dict(os.environ, HYPRDROID_KEYSTORE_PASSWORD=password)
    keystore = Path(private) / "signing.p12"
    common = ["-keystore", str(keystore), "-storetype", "PKCS12", "-alias", "hyprdroid-ci-debug",
              "-storepass:env", "HYPRDROID_KEYSTORE_PASSWORD"]
    subprocess.run(["keytool", "-genkeypair", *common, "-keypass:env", "HYPRDROID_KEYSTORE_PASSWORD",
                    "-keyalg", "RSA", "-keysize", "3072", "-sigalg", "SHA256withRSA", "-validity", "10950",
                    "-dname", "CN=HyprDroid CI Debug, O=HyprDroid", "-noprompt"], env=environment, check=True)
    for name, value in [("keystore-secret", base64.b64encode(keystore.read_bytes()).decode()),
                        ("password-secret", password)]:
        (output / f"{name}.json").write_text(json.dumps({"key_id": key_id,
            "encrypted_value": base64.b64encode(box.encrypt(value.encode())).decode()}))
    certificate = subprocess.check_output(["keytool", "-exportcert", *common], env=environment)
    import hashlib
    fingerprint = hashlib.sha256(certificate).hexdigest()
    (output / "certificate.sha256").write_text(fingerprint + "\n")
    (output / "certificate.der").write_bytes(certificate)
    print(f"Public certificate SHA-256: {fingerprint}")
