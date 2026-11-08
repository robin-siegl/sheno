#!/usr/bin/env python3
"""Restore the four Android signing secrets used by the production workflow."""
import base64
import binascii
import os
from pathlib import Path
import sys

SIGNING_FIELDS = {
    "ANDROID_KEYSTORE_PASSWORD": "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS": "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD": "ANDROID_KEY_PASSWORD",
}


def main():
    required = ["ANDROID_KEYSTORE_BASE64", *SIGNING_FIELDS]
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        sys.exit("Missing repository Actions secrets: " + ", ".join(missing) + ". See docs/RELEASING.md.")
    for name in SIGNING_FIELDS:
        if any(c in os.environ[name] for c in "\r\n\0"):
            sys.exit(name + " must be a single-line value. See docs/RELEASING.md.")
    try:
        # Accept base64 copied with line wrapping or a trailing newline.
        encoded = "".join(os.environ["ANDROID_KEYSTORE_BASE64"].split())
        key_bytes = base64.b64decode(encoded, validate=True)
        if not 100 <= len(key_bytes) <= 1024 * 1024:
            raise ValueError("Invalid key size")
    except (ValueError, binascii.Error):
        sys.exit("ANDROID_KEYSTORE_BASE64 is invalid. Encode the whole keystore file. See docs/RELEASING.md.")
    for name in required:
        value = os.environ[name].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print("::add-mask::" + value)
    path = Path(os.environ["RUNNER_TEMP"]) / "sheno-release.p12"
    path.write_bytes(key_bytes)
    path.chmod(0o600)
    with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as output:
        output.write("SHENO_KEYSTORE_PATH=" + str(path) + "\n")
        for secret_name, variable in SIGNING_FIELDS.items():
            output.write(variable + "=" + os.environ[secret_name] + "\n")
    print("Release signing configured.")


if __name__ == "__main__":
    main()
