package sgdk.rescomp.extension.spriteengine;

/**
 * Holds the geometry of a single VDP sprite cell composing a {@link FastSpriteFrame}, plus its palette delta
 * relative to the sprite base palette.<br>
 * Unlike the classic sprite engine's VDPSprite, instances of this class are never registered as a standalone
 * {@code Resource} (the owning frame writes them out inline as pre-packed SAT entry templates).
 */
public class FastVDPSprite
{
    public final int offsetX;
    public final int offsetY;
    public final int wt;
    public final int ht;
    public final int offsetXFlip;
    public final int offsetYFlip;
    // palette delta relative to the sprite base palette
    public final int pal;

    public FastVDPSprite(int offX, int offY, int w, int h, int wf, int hf, int pal, String id)
    {
        this.offsetX = offX;
        this.offsetY = offY;
        this.wt = w;
        this.ht = h;
        this.offsetXFlip = (wf * 8) - (offX + (w * 8));
        this.offsetYFlip = (hf * 8) - (offY + (h * 8));
        this.pal = pal;

        if ((offsetX < 0) || (offsetX > 255) || (offsetY < 0) || (offsetY > 255))
            throw new IllegalArgumentException("Error: sprite '" + id + "' offset X / Y is out of range (< 0 or > 255)");
        if ((offsetXFlip < 0) || (offsetXFlip > 255) || (offsetYFlip < 0) || (offsetYFlip > 255))
            throw new IllegalArgumentException("Error: sprite '" + id + "' flipped offset X / Y is out of range (< 0 or > 255)");
    }

    public FastVDPSprite(SpriteCellWithPalette cell, int wf, int hf, String id)
    {
        this(cell.cell.x, cell.cell.y, cell.cell.width / 8, cell.cell.height / 8, wf, hf, cell.pal, id);
    }

    public int getFormattedSize()
    {
        return ((wt - 1) << 2) | (ht - 1);
    }

    /**
     * Writes a pre-packed SAT entry template for the fast sprite engine, stored in SAT field order so building a
     * SAT entry at runtime only requires a single add per field: y offset, size pre-shifted in high byte (link
     * added at runtime), palette delta and cumulated tile index offset (sprite base attribute added at runtime),
     * x offset.
     */
    public void writeTemplate(StringBuilder outS, int tileOffset, boolean hflip, boolean vflip)
    {
        outS.append("    dc.w    " + (vflip ? offsetYFlip : offsetY) + "\n");
        outS.append("    dc.w    " + (getFormattedSize() << 8) + "\n");
        outS.append("    dc.w    " + ((pal << 13) | tileOffset) + "\n");
        outS.append("    dc.w    " + (hflip ? offsetXFlip : offsetX) + "\n");
    }

    @Override
    public int hashCode()
    {
        return (offsetX << 0) ^ (offsetXFlip << 0) ^ (offsetY << 8) ^ (offsetYFlip << 8) ^ (wt << 16) ^ (ht << 24) ^ (pal << 26);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (obj instanceof FastVDPSprite)
        {
            final FastVDPSprite o = (FastVDPSprite) obj;
            return (offsetX == o.offsetX) && (offsetY == o.offsetY) && (wt == o.wt) && (ht == o.ht) && (offsetXFlip == o.offsetXFlip)
                    && (offsetYFlip == o.offsetYFlip) && (pal == o.pal);
        }

        return false;
    }
}
