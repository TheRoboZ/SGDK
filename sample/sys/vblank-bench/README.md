# VBlank process benchmark

Measures the cost of `SYS_doVBlankProcessEx(..)` and checks the behaviour of the
three VBlank task bits. It exists to validate changes to that function, so it is
built to be run twice — once against a baseline library and once against a
modified one — and compared.

## Building

```sh
make                 # release ROM in out/rom.bin
sh build_ab.sh       # rom_before.bin + rom_after.bin, see below
```

`make` here goes through a local `Makefile` that defines `INC_LIB` before
including `$(GDK)/makefile.gen`. That variable is referenced by `makefile.gen`
but defined nowhere (`common.mk` defines `INCLUDE_LIB`), so without the wrapper
every sample fails with `genesis.h: No such file or directory`.

## Reading the screen

### Timing

```
scenario              ticks  cycl
S1 no DMA task         ####   ###
S2 DMA task, empty     ####   ###
S3 + forced DMA delay  ####   ###
S4 DMA task, 8 queued  ####   ###
```

`ticks` is the total for 100 calls, `cycl` the approximate 68000 cycles for one
call. The unit is the subtick from `getSubTick()`: about 98 CPU cycles, with the
V-Counter behind it stepping 5 subticks per scanline. Each figure is the minimum
of 8 runs, which suppresses interrupt jitter.

The timing loop calls `SYS_doVBlankProcessEx(IMMEDIATELY)`. That mode skips the
VBlank wait and runs only the process body, so the frame wait does not dominate
the measurement.

| Row | What it isolates |
|-----|------------------|
| S1 | `PROCESS_DMA_TASK` clear. The fixed floor: the user VBlank callback plus `JOY_update()`. |
| S2 | DMA task registered, queue empty. Covers the Z80 bus protection pair and `DMA_flushQueue()` overhead. |
| S3 | S2 plus `Z80_setForceDelayDMA(TRUE)`, which a PCM sound driver sets. Adds `waitSubTick(10)`, roughly 1100 cycles. |
| S4 | DMA task with 8 real transfers queued per call. The control: this is the path that must not change. |

S1 and S4 are controls. If they move between two builds, something other than
the intended change is in play.

### Checks

| Check | What it asserts |
|-------|-----------------|
| C1 | An async `PAL_fade` registers `PROCESS_PALETTE_FADING`, runs to completion, and clears the bit. |
| C2 | `VDP_setHorizontalScrollVSync` registers `PROCESS_VDP_SCROLL_TASK` and one frame clears it. |
| C3 | A task bit set by an interrupt *while the process is running* survives. |
| C4 | START toggles the frame load meter. Visual check only. |

C3 arms an H-Int callback to set an unused `VBlankProcess` bit a fixed number of
scanlines after the process is entered, from mid active display (H-Ints do not
fire during VBlank), with a full DMA queue to widen the window. The delay starts
at 2 scanlines so the bit is always set *after* the process has read
`VBlankProcess` — a shorter delay would be preserved by any implementation and
report a false pass.

C3 reports `INCONCLUSIVE` if the probe never fired inside the process at any of
the delays tried. That is not a pass: it means the window never overlapped, and
the DMA load or the delay range needs widening.

## Comparing two builds

```sh
sh build_ab.sh [base-ref]
```

Builds the ROM against the working tree `src/sys.c` as `rom_after.bin`, then
against `src/sys.c` from `base-ref` (default `HEAD`) as `rom_before.bin`, then
restores the working tree and rebuilds the library. The working copy is restored
on any exit including Ctrl-C. Three full library rebuilds, a few minutes.

Run both ROMs and put the numbers side by side. A change that only makes the
empty-queue path cheaper should move S2 and S3 and leave S1 and S4 alone.

## Measured baseline

Cycles per call, NTSC, no sound driver loaded. `stock` is SGDK as of the commit
that introduced this sample; `final` is that commit plus the empty-DMA-queue gate,
the atomic `VBlankProcess` bit clear, and the frame-load display moved out of line.

| Row | stock | final | delta |
|-----|------:|------:|------:|
| S1 no DMA task        | 2739 | 2704  | -35 |
| S2 DMA task, empty    | 3302 | 2881* | -421 |
| S3 + forced DMA delay | 4179 | 2881* | -1298 |
| S4 DMA task, 8 queued | same | same  | ~-35 |

\* S2 and S3 were measured on an intermediate build. Every row is about 35 cycles
lower on the final build — the savings in the last bullet below sit on the path
all four scenarios take — so those two figures read slightly high. S1 is the only
row re-measured on the final build.

| Check | before | after |
|-------|--------|-------|
| C1 async fade | PASS | PASS |
| C2 scroll vsync task | PASS | PASS |
| C3 bit set in process | **FAIL** | PASS |

Reading the deltas:

- `S3 - S2` before is 877 cycles, which is `waitSubTick(10)` — close to the
  "about 100 cycles for 1 subtick" in `waitSubTick`'s own comment.
- `S2 - S1` before is 563 cycles: the Z80 bus protection pair plus
  `DMA_flushQueue` overhead on an empty queue. **This sample loads no sound
  driver**, so `busProtectSignalAddress` is 0 and `Z80_setBusProtection` early
  exits. With a driver that registers bus protection (XGM2) each call performs a
  real Z80 bus grab, so the saving there is larger than the figure above.
- `S2 - S1` after is 103 cycles: the cost of the gate itself, one
  `DMA_getQueueSize` plus one `DMA_clearQueue`.
- S3 and S2 are identical after the change, because with an empty queue the
  forced-delay branch is never reached.
- S1 is 35 cycles *faster*, and that gain is on the path every row takes. Three
  things contribute, and the third is not obvious:
  - the unconditional `move.w %d2,VBlankProcess` store-back is gone (-16);
  - `move.w flags,%d0` + `btst #0,%d0` collapsed to `btst #0,flags+1`;
  - moving the frame-load display out of line shrank `SYS_doVBlankProcessEx`
    enough that GCC could reach its branch targets with 8-bit displacements
    instead of 16-bit ones. A not-taken `Bcc.W` costs 4 cycles more than a
    not-taken `Bcc.B` on the 68000, and two of the three task-bit tests on the
    hot path are not-taken in the common case (-8).

  That last point is worth remembering when judging a size change on this CPU:
  the 68000 has no instruction cache, so smaller code looks like it should be
  cycle-neutral, but branch displacement width means it often is not.

- An earlier revision also split the process body into its own `NO_INLINE`
  function to stop the constant-propagated clone duplicating it. That saved
  220 bytes but cost an extra `jsr`/`moveq`/`rts` — 39 cycles — on every frame,
  and was reverted in favour of the cycles. Returning a value so the wrapper can
  tail-call the body is a wash: the body then loses its own tail call to
  `JOY_update`.
