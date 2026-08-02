package sgdk.rescomp.extension.spriteengine;

import java.awt.Dimension;
import java.util.List;

import sgdk.rescomp.tool.SpriteCutter;
import sgdk.rescomp.type.SpriteCell;
import sgdk.rescomp.type.SpriteCell.OptimizationLevel;
import sgdk.rescomp.type.SpriteCell.OptimizationType;

/**
 * Shared helpers for the fast sprite engine extension, duplicated from core rescomp logic that is either
 * package-private (so not callable from this extension package) or was only ever added to core as part of the
 * in-progress FASTSPRITE patch this extension replaces.
 */
final class SpriteEngineUtil
{
    private SpriteEngineUtil()
    {
    }

    /**
     * Returns the bitmask of palette rows (0-3) used by opaque pixels of the given 8bpp image.
     */
    static int getUsedSpritePalettes(byte[] image8bpp)
    {
        int result = 0;

        for (byte b : image8bpp)
        {
            final int pixel = b & 0xFF;

            // opaque pixel ? --> add its palette row to the mask
            if ((pixel & 0xF) != 0)
                result |= 1 << ((pixel >> 4) & 3);
        }

        return result;
    }

    /**
     * Returns a copy of the image keeping only opaque pixels of the given palette row (color index 0-15, palette
     * row bits stripped), every other pixel becomes fully transparent (0).<br>
     * The result serves both the sprite cutter (opacity based) and tile data generation (single consistent palette).
     */
    static byte[] getPaletteMaskedImage(byte[] image8bpp, int pal)
    {
        final byte[] result = new byte[image8bpp.length];

        for (int i = 0; i < image8bpp.length; i++)
        {
            final int pixel = image8bpp[i] & 0xFF;
            final int color = pixel & 0xF;

            result[i] = (byte) ((((pixel >> 4) & 3) == pal) ? color : 0);
        }

        return result;
    }

    /**
     * Sprite cutting, reimplemented from core (package-private) {@code SpriteFrame.computeSpriteCutting} since it
     * calls only public {@link SpriteCutter} methods.
     */
    static List<SpriteCell> computeSpriteCutting(String id, byte[] frameImage8bpp, int wf, int hf, OptimizationType optType, OptimizationLevel optLevel)
            throws UnsupportedOperationException
    {
        List<SpriteCell> sprites;
        final Dimension frameDim = new Dimension(wf * 8, hf * 8);

        // special case of no optimization ? --> use default solution covering the whole sprite frame
        if (optType == OptimizationType.NONE)
            sprites = SpriteCutter.getFastOptimizedSpriteList(frameImage8bpp, frameDim, OptimizationType.NONE, false);
        else
        {
            // slow optimization ?
            if ((optLevel == OptimizationLevel.SLOW) || (optLevel == OptimizationLevel.MAX))
            {
                final int iteration = (optLevel == OptimizationLevel.SLOW) ? 500000 : 5000000;

                sprites = SpriteCutter.getSlowOptimizedSpriteList(frameImage8bpp, frameDim, iteration, optType);

                // above the limit of internal sprite ? force MIN_SPRITE optimization strategy
                if ((sprites.size() > 16) && (optType != OptimizationType.MIN_SPRITE))
                    sprites = SpriteCutter.getSlowOptimizedSpriteList(frameImage8bpp, frameDim, iteration, OptimizationType.MIN_SPRITE);
            }
            else
            {
                final boolean optBetter = optLevel == OptimizationLevel.MEDIUM;

                // always start with the fast optimization first
                sprites = SpriteCutter.getFastOptimizedSpriteList(frameImage8bpp, frameDim, optType, optBetter);

                // too many sprites used for this sprite ? try MIN_SPRITE opt strategy
                if ((sprites.size() > 16) && (optType != OptimizationType.MIN_SPRITE))
                    sprites = SpriteCutter.getFastOptimizedSpriteList(frameImage8bpp, frameDim, OptimizationType.MIN_SPRITE, optBetter);

                // still too many sprites used for this sprite ? try MIN_SPRITE with optBetter option
                if ((sprites.size() > 16) && !optBetter)
                    sprites = SpriteCutter.getFastOptimizedSpriteList(frameImage8bpp, frameDim, OptimizationType.MIN_SPRITE, true);

                // still too many sprites used for this sprite ? try better (but slower) sprite optimization method
                if (sprites.size() > 16)
                    sprites = SpriteCutter.getSlowOptimizedSpriteList(frameImage8bpp, frameDim, 100000, OptimizationType.MIN_SPRITE);
            }
        }

        // still above the limit ? --> stop here :-(
        if (sprites.size() > 16)
            throw new UnsupportedOperationException("Sprite frame '" + id + "' uses " + sprites.size()
                    + " internal sprites, that is above the limit (16), try to reduce the sprite size or split it.");

        // special case of NONE optimization type
        if ((!sprites.isEmpty()) && (optType == OptimizationType.NONE))
        {
            // check if frame is empty or not
            boolean empty = true;
            for (byte b : frameImage8bpp)
            {
                if ((b & 0xF) != 0)
                {
                    empty = false;
                    break;
                }
            }

            // empty frame ? --> clear sprite list
            if (empty)
                sprites.clear();
        }

        return sprites;
    }

    /**
     * Palette aware sprite cutting for the fast sprite engine: when the frame uses several palette rows, each row
     * is cut separately (a hardware sprite can only use a single palette) and cells are tagged with their palette
     * delta relative to the sprite base palette.
     */
    static List<SpriteCellWithPalette> computeSpriteCuttingFast(String id, byte[] frameImage8bpp, int wf, int hf, OptimizationType optType,
            OptimizationLevel optLevel, int basePal) throws UnsupportedOperationException
    {
        final int palMask = getUsedSpritePalettes(frameImage8bpp);
        final List<SpriteCellWithPalette> result = new java.util.ArrayList<>();

        // empty frame or single palette --> classic cutting, just tag the palette delta on all cells
        if ((palMask & (palMask - 1)) == 0)
        {
            final List<SpriteCell> cells = computeSpriteCutting(id, frameImage8bpp, wf, hf, optType, optLevel);
            final int delta = (palMask != 0) ? (Integer.numberOfTrailingZeros(palMask) - basePal) : 0;

            for (SpriteCell cell : cells)
                result.add(new SpriteCellWithPalette(cell, delta));

            return result;
        }

        // multi palette frame --> cut each palette region separately (opaque pixel sets are disjoint so
        // overlapping cells from different palettes render correctly whatever the SAT order)
        for (int pal = 0; pal < 4; pal++)
        {
            if ((palMask & (1 << pal)) == 0)
                continue;

            final List<SpriteCell> cells = computeSpriteCutting(id + "_pal" + pal, getPaletteMaskedImage(frameImage8bpp, pal), wf, hf, optType, optLevel);

            final int delta = pal - basePal;
            for (SpriteCell cell : cells)
                result.add(new SpriteCellWithPalette(cell, delta));
        }

        // above the limit ? --> stop here :-(
        if (result.size() > 16)
            throw new UnsupportedOperationException("Sprite frame '" + id + "' uses " + result.size()
                    + " internal sprites in total over its palette regions, that is above the limit (16), try to reduce the sprite size or split it.");

        return result;
    }
}
