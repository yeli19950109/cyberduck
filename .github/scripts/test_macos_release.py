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

import unittest
import argparse
import json
import pathlib
import plistlib
import tempfile

from macos_release import metadata, next_version, parse_version, prepare


class VersionTest(unittest.TestCase):
    def test_zero_padded_date(self):
        self.assertEqual("9.6.0-2026.09.01.1", next_version("9.6.0", "2026.09.01", []))
        with self.assertRaises(ValueError):
            parse_version("9.6.0-2026.9.1.1")

    def test_next_build_is_numeric(self):
        tags = ["v9.6.0-2026.10.15.9", "v9.6.0-2026.10.15.10", "v9.6.0-2026.10.14.99"]
        self.assertEqual("9.6.0-2026.10.15.11", next_version("9.6.0", "2026.10.15", tags))

    def test_month_and_build_order(self):
        self.assertLess(parse_version("9.6.0-2026.09.01.1"), parse_version("9.6.0-2026.10.01.1"))
        self.assertLess(parse_version("9.6.0-2026.10.15.9"), parse_version("9.6.0-2026.10.15.10"))
        self.assertEqual("20261015.10", metadata("9.6.0-2026.10.15.10")["revision"])

    def test_invalid_calendar_date(self):
        with self.assertRaises(ValueError):
            parse_version("9.6.0-2026.02.30.1")

    def test_prepared_fork_has_independent_storage(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            app = root / "Cyberduck.app"
            (app / "Contents").mkdir(parents=True)
            info = app / "Contents/Info.plist"
            info.write_bytes(plistlib.dumps({"CFBundleIdentifier": "ch.sudo.cyberduck"}))
            release = root / "version.json"
            release.write_text(json.dumps(metadata("9.6.0-2026.10.15.2")))
            prepare(argparse.Namespace(app=app, metadata=release, repository="example/cyberduck",
                                       public_key=pathlib.Path(__file__).resolve().parents[2] / "setup/app/fork-update.pub"))
            result = plistlib.loads(info.read_bytes())
            self.assertEqual("CyberSilverWolf", result["CyberduckForkDataFolder"])
            self.assertEqual("ch.sudo.cyberduck", result["CFBundleIdentifier"])
            self.assertEqual("https://github.com/example/cyberduck/releases/latest/download/appcast.xml", result["SUFeedURL"])


if __name__ == "__main__":
    unittest.main()
