package sgdk.rescomp.extension.spriteengine;

import sgdk.rescomp.type.SpriteCell;

/**
 * Pairs a cut {@link SpriteCell} with its palette delta relative to the sprite base palette.<br>
 * Core {@code SpriteCell} can't carry this itself: {@code SpriteCutter}'s internal mutation/optimization methods
 * construct plain {@code new SpriteCell(...)} instances, so any extra field on a subclass would not survive the
 * cutting pipeline. This side-channel wrapper is attached only after cutting completes.
 */
public class SpriteCellWithPalette
{
    public final SpriteCell cell;
    // palette delta relative to the sprite base palette
    public int pal;

    public SpriteCellWithPalette(SpriteCell cell, int pal)
    {
        this.cell = cell;
        this.pal = pal;
    }
}
