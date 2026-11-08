#!/usr/bin/env python3
"""Validate the committed Gradle release version without changing or bumping it."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")


def parse_version(text):
    match = VERSION.fullmatch(text)
    if not match:
        raise ValueError("Use a stable version such as 1.0.0, without a v prefix or prerelease suffix.")
    value = tuple(map(int, match.groups()))
    if value[0] > 1999 or value[1] > 999 or value[2] > 999:
        raise ValueError("Version limits: major <= 1999, minor and patch <= 999.")
    return value


def resolve_version(project_version, project_code, tags):
    chosen = parse_version(project_version)
    code = chosen[0] * 1_000_000 + chosen[1] * 1_000 + chosen[2]
    if code < 1:
        raise ValueError("The release version must produce a positive Android versionCode.")
    if str(project_code) != str(code):
        raise ValueError(f"sheno.versionCode must be {code} for sheno.versionName={project_version}. "
                         "Update both values in gradle.properties and commit the change.")
    stable = []
    for tag in tags:
        if not tag.startswith("v"):
            continue
        try:
            stable.append(parse_version(tag[1:]))
        except ValueError:
            continue
    latest = max(stable, default=None)
    if latest is not None and chosen <= latest:
        raise ValueError("The project version must be newer than v" + ".".join(map(str, latest)) +
                         ". Update sheno.versionName and sheno.versionCode in gradle.properties and commit the change. "
                         "Existing tags are never replaced.")
    return {"version": project_version, "tag": "v" + project_version, "version_code": code}


def read_project_version(path):
    properties = {}
    for raw in Path(path).read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")):
            continue
        name, separator, value = line.partition("=")
        name = name.strip()
        if not separator or name not in ("sheno.versionName", "sheno.versionCode"):
            continue
        if name in properties:
            raise ValueError(f"Define {name} only once in {path}.")
        properties[name] = value.strip()
    for name in ("sheno.versionName", "sheno.versionCode"):
        if name not in properties:
            raise ValueError(f"Set {name} in {path} before building a production APK.")
    return properties["sheno.versionName"], properties["sheno.versionCode"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--properties", default="gradle.properties")
    args = parser.parse_args()
    if os.environ.get("GITHUB_ACTIONS") == "true" and os.environ.get("GITHUB_REF") != "refs/heads/main":
        raise ValueError("Production builds must run from the main branch.")
    version, code = read_project_version(args.properties)
    tags = subprocess.check_output(["git", "tag", "--list"], text=True).splitlines()
    result = resolve_version(version, code, tags)
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            for name, value in result.items():
                output.write(f"{name}={value}\n")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
            summary.write(f"### sheno {result['version']}\n\nVersion from committed `{args.properties}`. "
                          f"Android versionCode: `{result['version_code']}`. Release tag: `{result['tag']}`.\n")
    print(json.dumps(result))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        sys.exit("Project release version is invalid: " + str(error))
