#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
cat 0663a96-MainActivity.patch.part0 0663a96-MainActivity.patch.part1 0663a96-MainActivity.patch.part2 > 0663a96-MainActivity.patch
echo "Reconstructed patches/0663a96-MainActivity.patch ($(wc -c < 0663a96-MainActivity.patch) bytes)"
echo "Apply with: git apply patches/0663a96-MainActivity.patch"
