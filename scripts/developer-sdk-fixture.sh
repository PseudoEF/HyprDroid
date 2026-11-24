#!/bin/sh
# Build and sign the reference counter using the published SDK contract.
set -eu
dest="$1"
mkdir -p "$dest"
dest=$(cd "$dest" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
hyp create "$work/my-tile" --template counter --api 2
hyp validate "$work/my-tile"
hyp test "$work/my-tile"
hyp build "$work/my-tile" --output "$dest"
printf '%s  %s\n' fc61de1a6d628f1bc02e2227ba7e90cce33934129ede2b4d4ae471a9a84832ab "$dest/my-tile-1.0.0.hyp" | sha256sum -c -
hyp keygen --private "$work/developer.pem" --public "$dest/developer.pub.pem"
hyp sign "$dest/my-tile-1.0.0.hyp" --private "$work/developer.pem" \
  --url https://example.invalid/my-tile-1.0.0.hyp --output "$dest/my-tile.signature.json"
hyp verify "$dest/my-tile-1.0.0.hyp" --signature "$dest/my-tile.signature.json" --public "$dest/developer.pub.pem"
