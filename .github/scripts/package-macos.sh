#!/bin/bash
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

set -euo pipefail

metadata=${1:?Usage: package-macos.sh version.json}
app=osx/target/Cyberduck.app
version=$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["version"])' "$metadata")

while IFS= read -r -d '' binary; do
  codesign --force --sign - "$binary"
done < <(find "$app" -type f \( -name '*.dylib' -o -name '*.jnilib' -o -name jspawnhelper \) -print0)
codesign --force --deep --sign - "$app"
# Keep the app group declaration so macOS can request access to existing bookmarks.
# Apply application entitlements only to the outer app, not to its nested libraries.
codesign --force --sign - --entitlements setup/app/default.entitlements "$app"
codesign --verify --deep --strict "$app"

mkdir -p osx/target/release
productbuild --component "$app" /Applications "osx/target/release/Cyberduck-$version.pkg"
ditto -c -k --sequesterRsrc --keepParent "$app" "osx/target/release/Cyberduck-$version.zip"
(
  cd osx/target/release
  shasum -a 256 "Cyberduck-$version.pkg" "Cyberduck-$version.zip" > SHA256SUMS.txt
)
