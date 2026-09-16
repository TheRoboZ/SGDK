#include "genesis.h"

/*
 * Benchmark / regression test for the SYS_doVBlankProcessEx(..) rework.
 *
 * The timing part calls SYS_doVBlankProcessEx(IMMEDIATELY): that skips the VBlank
 * wait and runs only the process body, which is exactly the code that changed, so
 * the frame wait does not swamp the measurement.
 *
 * Timing unit is the subtick returned by getSubTick(): about 98 CPU cycles, and
 * the V-Counter behind it steps 5 subticks per scanline.
 */

// VBlankProcess is externally_visible in src/sys.c and is already declared this
// way by dma.c / pal.c / bmp.c / vdp_bg.c
extern vu16 VBlankProcess;

// an unused VBlankProcess bit: the V-Int handler only tests bit 1 and the library
// only ever sets or clears its own bits, so nothing acts on this one
#define PROBE_BIT           (1 << 15)

#define ITER                100     // process calls per measurement
#define RUNS                8       // measurements per scenario, minimum is kept
#define QUEUED_DMA          8       // transfers queued per call in scenario S4

#define RACE_ATTEMPTS       6       // H-Int delays tried by the race check

#define RESULT_FAIL         0
#define RESULT_PASS         1
#define RESULT_INCONCLUSIVE 2

static u16 dmaSrc[64];
static u16 savedPal[16];
static char buf[64];

// H-Int probe: fires once, hintDelay scanlines after it is armed
static vu16 hintDelay;
static vu16 hintFired;


// must be HINTERRUPT_CALLBACK: the H-Int vector jumps straight at hintCaller,
// which jumps here, so this function *is* the interrupt handler and has to end
// with RTE. A plain C function returns with RTS and sends the PC into space.
static HINTERRUPT_CALLBACK hintProbe()
{
    if (hintDelay)
    {
        hintDelay--;
        if (hintDelay == 0)
        {
            VBlankProcess |= PROBE_BIT;
            hintFired = TRUE;
        }
    }
}


/* ------------------------------------------------------------------------- */
/* timing                                                                     */
/* ------------------------------------------------------------------------- */

static void queueSome(u16 count)
{
    while(count--)
        DMA_queueDma(DMA_VRAM, dmaSrc, TILE_USER_INDEX * TILE_SIZE, sizeof(dmaSrc) / 2, 2);
}

static u32 measureOnce(u16 queueCount)
{
    u16 i = ITER;
    u32 start;

    // start from a known point in the frame
    SYS_doVBlankProcess();

    start = getSubTick();

    while(i--)
    {
        queueSome(queueCount);
        SYS_doVBlankProcessEx(IMMEDIATELY);
    }

    return getSubTick() - start;
}

static u32 measure(u16 queueCount)
{
    u32 best = 0xFFFFFFFF;
    u16 n = RUNS;

    while(n--)
    {
        const u32 v = measureOnce(queueCount);
        if (v < best) best = v;
    }

    return best;
}


/* ------------------------------------------------------------------------- */
/* correctness checks                                                         */
/* ------------------------------------------------------------------------- */

// C1: async palette fade still runs to completion and clears its task bit.
// Only PAL3 is faded: the report text uses PAL0 (text_basetile defaults to 0), so
// the screen stays readable whatever the fade leaves behind.
static u16 checkAsyncFade(void)
{
    u16 timeout = 180;
    u16 result;

    PAL_getColors(PAL3 * 16, savedPal, 16);

    PAL_fadeOutPalette(PAL3, 8, TRUE);

    // the fade must have registered its task bit
    if (!(VBlankProcess & PROCESS_PALETTE_FADING)) result = RESULT_FAIL;
    else
    {
        while (PAL_isDoingFade() && timeout)
        {
            SYS_doVBlankProcess();
            timeout--;
        }

        if (timeout && !(VBlankProcess & PROCESS_PALETTE_FADING)) result = RESULT_PASS;
        else result = RESULT_FAIL;
    }

    // PAL_doFadeStep queues its palette transfer with DMA_QUEUE, and the fade
    // reports itself done before that last transfer has been flushed. Flush it
    // first, otherwise it lands on top of the restore below.
    SYS_doVBlankProcess();
    PAL_setColors(PAL3 * 16, savedPal, 16, CPU);

    return result;
}

// C2: the async scroll task still clears itself after one frame
static u16 checkScrollTask(void)
{
    VDP_setHorizontalScrollVSync(BG_A, 0);

    // the scroll request must have registered its task bit
    if (!(VBlankProcess & PROCESS_VDP_SCROLL_TASK)) return RESULT_FAIL;

    SYS_doVBlankProcess();

    if (VBlankProcess & PROCESS_VDP_SCROLL_TASK) return RESULT_FAIL;

    return RESULT_PASS;
}

// C3: a task bit set by an interrupt *while the process is running* must survive.
// The old code cached VBlankProcess on entry and stored the cached word back at
// the end, which wiped it.
static u16 checkBitSetDuringProcess(void)
{
    u16 delay;
    u16 result = RESULT_INCONCLUSIVE;
    bool probeEverFired = FALSE;

    SYS_setHIntCallback(hintProbe);
    VDP_setHIntCounter(0);              // one H-Int per scanline
    VDP_setHInterrupt(TRUE);

    // delay starts at 2: the probe has to fire *after* the process has read
    // VBlankProcess, otherwise even the old code would preserve the bit and we
    // would report a false pass
    for (delay = 2; delay < (2 + RACE_ATTEMPTS); delay++)
    {
        // start near the top of active display, so the whole process runs while
        // H-Ints are still firing (they do not fire during VBlank)
        {
            u16 vc;
            do { vc = GET_VCOUNTER; } while ((vc < 40) || (vc > 60));
        }

        // real work to widen the window between the read and the store back
        queueSome(QUEUED_DMA);

        VBlankProcess &= ~PROBE_BIT;
        hintFired = FALSE;
        hintDelay = delay;

        SYS_doVBlankProcessEx(IMMEDIATELY);

        if (hintFired)
        {
            probeEverFired = TRUE;

            if (VBlankProcess & PROBE_BIT)
            {
                result = RESULT_PASS;
                break;
            }

            result = RESULT_FAIL;
        }
    }

    hintDelay = 0;
    VDP_setHInterrupt(FALSE);
    SYS_setHIntCallback(NULL);
    VBlankProcess &= ~PROBE_BIT;

    // if the probe never landed inside the process we measured nothing
    if (!probeEverFired) result = RESULT_INCONCLUSIVE;

    return result;
}


/* ------------------------------------------------------------------------- */
/* report                                                                     */
/* ------------------------------------------------------------------------- */

static const char* resultText(u16 result)
{
    if (result == RESULT_PASS) return "PASS";
    if (result == RESULT_FAIL) return "FAIL";
    return "INCONCLUSIVE";
}

static void drawTiming(const char* label, u32 subticks, u16 y)
{
    // about 98 CPU cycles per subtick, spread over ITER calls
    sprintf(buf, "%-21s %5lu %5lu", label, subticks, (subticks * 98) / ITER);
    VDP_drawText(buf, 1, y);
}

static void drawCheck(const char* label, u16 result, u16 y)
{
    sprintf(buf, "%-21s %s", label, resultText(result));
    VDP_drawText(buf, 1, y);
}


int main(bool hardReset)
{
    u32 s1, s2, s3, s4;
    u16 c1, c2, c3;
    bool loadShown = FALSE;
    u16 prevJoy = 0;

    VDP_drawText("VBLANK PROCESS BENCHMARK", 1, 1);
    VDP_drawText("measuring...", 1, 3);

    // make sure nothing is left over from init
    DMA_clearQueue();

    // S1: no DMA task at all, the fixed floor (vblankCB + JOY_update)
    Z80_setForceDelayDMA(FALSE);
    DMA_setAutoFlush(FALSE);
    s1 = measure(0);

    // S2: DMA task registered but nothing queued
    DMA_setAutoFlush(TRUE);
    s2 = measure(0);

    // S3: same, with the sound driver forced DMA delay on
    Z80_setForceDelayDMA(TRUE);
    s3 = measure(0);

    // S4: DMA task with real transfers queued, the control
    Z80_setForceDelayDMA(FALSE);
    s4 = measure(QUEUED_DMA);

    DMA_clearQueue();

    // show the timings before running the checks, so that a check which hangs or
    // faults still leaves the numbers (and the checks already done) on screen
    VDP_clearTextArea(0, 3, 40, 24);

    VDP_drawText("scenario              ticks  cycl", 1, 3);
    drawTiming("S1 no DMA task",         s1, 4);
    drawTiming("S2 DMA task, empty",     s2, 5);
    drawTiming("S3 + forced DMA delay",  s3, 6);
    drawTiming("S4 DMA task, 8 queued",  s4, 7);
    VDP_drawText("ticks = 100 calls, cycl = 1 call", 1, 8);

    c1 = checkAsyncFade();
    drawCheck("C1 async fade",           c1, 11);

    c2 = checkScrollTask();
    drawCheck("C2 scroll vsync task",    c2, 12);

    c3 = checkBitSetDuringProcess();
    drawCheck("C3 bit set in process",   c3, 13);

    VDP_drawText("C4 START toggles load meter", 1, 15);

    while(TRUE)
    {
        const u16 joy = JOY_readJoypad(JOY_1);

        if ((joy & BUTTON_START) && !(prevJoy & BUTTON_START))
        {
            loadShown = !loadShown;

            if (loadShown) SYS_showFrameLoad(FALSE);
            else SYS_hideFrameLoad();
        }

        prevJoy = joy;

        SYS_doVBlankProcess();
    }

    return 0;
}
