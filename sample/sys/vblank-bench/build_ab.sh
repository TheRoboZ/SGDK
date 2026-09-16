#!/bin/sh
#
# Build two ROMs of this benchmark so the SYS_doVBlankProcessEx(..) rework can be
# compared side by side:
#
#   rom_after.bin   src/sys.c as it is in the working tree
#   rom_before.bin  src/sys.c taken from <base-ref> (default HEAD)
#
# The working copy of src/sys.c is restored on any exit, including Ctrl-C, and the
# optimized library is rebuilt at the end so the tree is left as it was found.
#
# Usage: sh build_ab.sh [base-ref]
#
# Costs three full library rebuilds.

set -e

BASE_REF="${1:-HEAD}"

SAMPLE_DIR=$(pwd)
GDK=$(cd ../../.. && pwd)
MAKE="$GDK/bin/make"

SYS_C="$GDK/src/sys.c"
BACKUP="$SAMPLE_DIR/.sys.c.orig"

if [ ! -f "$SYS_C" ]; then
    echo "error: $SYS_C not found, run this from sample/sys/vblank-bench" >&2
    exit 1
fi

cp "$SYS_C" "$BACKUP"

restore()
{
    if [ -f "$BACKUP" ]; then
        cp "$BACKUP" "$SYS_C"
        rm -f "$BACKUP"
    fi
}

trap restore EXIT INT TERM

build_lib()
{
    # cleanobj is required: SGDK shares .o files between configurations, so
    # without it the archive is rebuilt from stale objects
    (cd "$GDK" && "$MAKE" -f makelib.gen cleanobj >/dev/null && "$MAKE" -f makelib.gen release >/dev/null)
}

build_rom()
{
    (cd "$SAMPLE_DIR" && rm -rf out && GDK="$GDK" "$MAKE" release >/dev/null)
}

echo "[1/3] building AFTER (working tree src/sys.c)"
build_lib
build_rom
cp out/rom.bin "$SAMPLE_DIR/rom_after.bin"

echo "[2/3] building BEFORE (src/sys.c from $BASE_REF)"
git -C "$GDK" show "$BASE_REF:src/sys.c" > "$SYS_C"
build_lib
build_rom
cp out/rom.bin "$SAMPLE_DIR/rom_before.bin"

echo "[3/3] restoring working tree and rebuilding the optimized library"
restore
trap - EXIT INT TERM
build_lib

echo
echo "done:"
echo "  $SAMPLE_DIR/rom_before.bin"
echo "  $SAMPLE_DIR/rom_after.bin"
echo
echo "run both and compare the S1..S4 rows and the C1..C3 results."
