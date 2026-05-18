#!/usr/bin/env bash
set -euo pipefail

# Сборка libbox.aar из sing-box.
# Требуется: Go 1.24.7+, Android SDK, Android NDK, make, git.

SING_BOX_TAG="${SING_BOX_TAG:-v1.10.7}"
SING_BOX_DIR="${1:-/tmp/sing-box-autovless}"
OUT_DIR="${2:-app/libs}"

if [ -z "${ANDROID_HOME:-}" ]; then
  echo "ANDROID_HOME is not set" >&2
  exit 1
fi

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  if [ -d "$ANDROID_HOME/ndk" ]; then
    export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$(ls "$ANDROID_HOME/ndk" | sort -V | tail -n 1)"
  fi
fi

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "ANDROID_NDK_HOME is not set or directory does not exist" >&2
  exit 1
fi

export PATH="$PATH:$(go env GOPATH)/bin"

rm -rf "$SING_BOX_DIR"
git clone --depth=1 --branch "$SING_BOX_TAG" https://github.com/SagerNet/sing-box.git "$SING_BOX_DIR"

cd "$SING_BOX_DIR"
go version

python3 - <<'PY2'
from pathlib import Path
p = Path('cmd/internal/build_libbox/main.go')
s = p.read_text()
s = s.replace('"with_naive_outbound", ', '')
s = s.replace(', "with_naive_outbound"', '')
p.write_text(s)
PY2
! grep -q "with_naive_outbound" cmd/internal/build_libbox/main.go

make lib_install
gomobile init
go run ./cmd/internal/build_libbox -target android -platform android/arm64

AAR_PATH="$(find "$SING_BOX_DIR" -name 'libbox.aar' -type f | head -n 1)"
if [ -z "$AAR_PATH" ]; then
  echo "libbox.aar not found" >&2
  exit 1
fi

cd - >/dev/null
mkdir -p "$OUT_DIR"
cp "$AAR_PATH" "$OUT_DIR/libbox.aar"
ls -lh "$OUT_DIR/libbox.aar"
echo "Done: $OUT_DIR/libbox.aar"
