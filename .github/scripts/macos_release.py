#!/usr/bin/env python3
# Copyright (c) 2002-2026 iterate GmbH. All rights reserved.
# https://cyberduck.io/
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU General Public License for more details.

"""Version and prepare the fork's macOS packages and signed Sparkle feed."""

import argparse
import base64
import datetime
import email.utils
import json
import pathlib
import plistlib
import re
import subprocess
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo


def parse_version(version):
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)-(\d{4})\.(\d{2})\.(\d{2})\.([1-9]\d*)", version)
    if not match:
        raise ValueError("Expected upstream-YYYY.MM.DD.build, for example 9.6.0-2026.09.01.1")
    parts = tuple(map(int, match.groups()))
    datetime.date(*parts[3:6])
    return parts


def next_version(upstream, day, tags):
    prefix = f"v{upstream}-{day}."
    builds = [int(tag[len(prefix):]) for tag in tags
              if tag.startswith(prefix) and tag[len(prefix):].isdigit()]
    version = f"{upstream}-{day}.{max(builds, default=0) + 1}"
    parse_version(version)
    return version


def metadata(version):
    parts = parse_version(version)
    return {
        "version": version,
        "tag": f"v{version}",
        # Sparkle compares numeric components, so build 10 is newer than build 9.
        "revision": f"{parts[3]:04d}{parts[4]:02d}{parts[5]:02d}.{parts[6]}",
    }


def write_version(args):
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    upstream = ET.parse("pom.xml").getroot().findtext("m:version", namespaces=ns).removesuffix("-SNAPSHOT")
    if args.tag:
        version = args.tag.removeprefix("v")
        if not version.startswith(upstream + "-"):
            raise ValueError("Release tag does not match the upstream version in pom.xml")
    else:
        day = args.date or datetime.datetime.now(ZoneInfo("Asia/Shanghai")).strftime("%Y.%m.%d")
        tags = subprocess.check_output(["git", "tag", "--list"], text=True).splitlines()
        version = next_version(upstream, day, tags)
    result = metadata(version)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    if args.github_output:
        with args.github_output.open("a") as output:
            for key, value in result.items():
                output.write(f"{key}={value}\n")
    print(version)


def prepare(args):
    release = json.loads(args.metadata.read_text())
    parse_version(release["version"])
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", args.repository):
        raise ValueError("Invalid GitHub repository")
    key = args.public_key.read_text().strip()
    if len(base64.b64decode(key, validate=True)) != 32:
        raise ValueError("Expected a 32-byte Ed25519 public key")
    path = args.app / "Contents/Info.plist"
    info = plistlib.loads(path.read_bytes())
    feed = f"https://github.com/{args.repository}/releases/latest/download/appcast.xml"
    info.update({
        "CFBundleShortVersionString": release["version"],
        "CFBundleVersion": release["revision"],
        "SUFeedURL": feed,
        "CyberduckForkUpdateFeed": feed,
        "CyberduckForkDataFolder": "CyberSilverWolf",
        "SUPublicEDKey": key,
        "SUEnableAutomaticChecks": False,
        "SUExpectsDSASignature": False,
    })
    info.pop("SUPublicDSAKeyFile", None)
    path.write_bytes(plistlib.dumps(info, sort_keys=False))


def appcast(args):
    release = json.loads(args.metadata.read_text())
    archive = args.directory / f"Cyberduck-{release['version']}.zip"
    # The private key is supplied by Actions Secrets, never bundled in the app.
    signature = subprocess.check_output([
        "openssl", "pkeyutl", "-sign", "-inkey", str(args.private_key),
        "-rawin", "-in", str(archive),
    ])
    namespace = "http://www.andymatuschak.org/xml-namespaces/sparkle"
    ET.register_namespace("sparkle", namespace)
    rss = ET.Element("rss", {"version": "2.0"})
    channel = ET.SubElement(rss, "channel")
    ET.SubElement(channel, "title").text = "Cyberduck fork updates"
    ET.SubElement(channel, "link").text = f"https://github.com/{args.repository}/releases"
    ET.SubElement(channel, "description").text = "macOS updates for this Cyberduck fork"
    item = ET.SubElement(channel, "item")
    ET.SubElement(item, "title").text = f"Cyberduck {release['version']}"
    ET.SubElement(item, "pubDate").text = email.utils.format_datetime(datetime.datetime.now(datetime.timezone.utc), usegmt=True)
    ET.SubElement(item, f"{{{namespace}}}version").text = release["revision"]
    ET.SubElement(item, f"{{{namespace}}}shortVersionString").text = release["version"]
    ET.SubElement(item, f"{{{namespace}}}minimumSystemVersion").text = "26.0"
    ET.SubElement(item, "link").text = f"https://github.com/{args.repository}/releases/tag/{release['tag']}"
    ET.SubElement(item, "enclosure", {
        "url": f"https://github.com/{args.repository}/releases/download/{release['tag']}/{archive.name}",
        "length": str(archive.stat().st_size),
        "type": "application/octet-stream",
        f"{{{namespace}}}edSignature": base64.b64encode(signature).decode(),
    })
    ET.indent(rss)
    ET.ElementTree(rss).write(args.directory / "appcast.xml", encoding="utf-8", xml_declaration=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    version = commands.add_parser("version")
    version.add_argument("--tag")
    version.add_argument("--date")
    version.add_argument("--output", type=pathlib.Path, required=True)
    version.add_argument("--github-output", type=pathlib.Path)
    version.set_defaults(run=write_version)
    bundle = commands.add_parser("prepare")
    bundle.add_argument("--metadata", type=pathlib.Path, required=True)
    bundle.add_argument("--app", type=pathlib.Path, required=True)
    bundle.add_argument("--repository", required=True)
    bundle.add_argument("--public-key", type=pathlib.Path, required=True)
    bundle.set_defaults(run=prepare)
    feed = commands.add_parser("appcast")
    feed.add_argument("--metadata", type=pathlib.Path, required=True)
    feed.add_argument("--directory", type=pathlib.Path, required=True)
    feed.add_argument("--repository", required=True)
    feed.add_argument("--private-key", type=pathlib.Path, required=True)
    feed.set_defaults(run=appcast)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
