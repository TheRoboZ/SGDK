package sgdk.rescomp.extension.spriteengine;

import java.awt.Dimension;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import sgdk.rescomp.Resource;
import sgdk.rescomp.resource.Bin;
import sgdk.rescomp.tool.Util;
import sgdk.rescomp.type.Basics.Compression;
import sgdk.rescomp.type.SpriteCell.OptimizationLevel;
import sgdk.rescomp.type.SpriteCell.OptimizationType;

/**
 * Single animation frame for the fast sprite engine. Unlike the classic engine's SpriteFrame, this has no
 * collision structure and stores VDP sprite info as pre-packed SAT entry templates (4 streams, one per flip
 * combination) instead of a plain VDPSprite array, and supports source images spanning up to 4 palette rows
 * (each cell tagged with its palette delta relative to the sprite base palette).
 */
public class FastSpriteFrame extends Resource
{
    public final List<FastVDPSprite> vdpSprites;
    private final FastTileset tileset;
    public final int timer;

    // just for pre-equal test (duplicate frame mask detection in FastSpriteAnimation)
    final byte[] frameImage;
    final Dimension frameDim;

    final int hc;

    public FastSpriteFrame(String id, byte[] frameImage8bpp, int wf, int hf, int timer, Compression compression, int basePal,
            List<SpriteCellWithPalette> sprites)
    {
        super(id);

        vdpSprites = new ArrayList<>();
        this.timer = timer;
        this.frameImage = frameImage8bpp;
        this.frameDim = new Dimension(wf * 8, hf * 8);

        if (sprites.isEmpty())
            System.out.println("Sprite frame '" + id + "' is empty");
        else
        {
            int optNumTile = 0;
            for (SpriteCellWithPalette spr : sprites)
                optNumTile += (spr.cell.width / 8) * (spr.cell.height / 8);

            System.out.println("Sprite frame '" + id + "' - " + sprites.size() + " VDP sprites and " + optNumTile + " tiles");
        }

        // build each cell's tile source image, masked to the cell's own palette row so tile data never mixes
        // palettes (a no-op when the frame only uses a single palette row, since then every opaque pixel already
        // belongs to that row)
        final byte[][] palImages = new byte[4][];
        final byte[][] cellImages = new byte[sprites.size()][];
        int ind = 0;
        for (SpriteCellWithPalette sprite : sprites)
        {
            final int pal = (sprite.pal + basePal) & 3;

            if (palImages[pal] == null)
                palImages[pal] = SpriteEngineUtil.getPaletteMaskedImage(frameImage8bpp, pal);
            cellImages[ind++] = palImages[pal];
        }

        tileset = (FastTileset) addInternalResource(new FastTileset(id + "_tileset", cellImages, wf * 8, hf * 8, sprites, compression));

        ind = 0;
        for (SpriteCellWithPalette sprite : sprites)
            vdpSprites.add(new FastVDPSprite(sprite, wf, hf, id + "_sprite" + ind++));

        hc = (timer << 16) ^ tileset.hashCode() ^ vdpSprites.hashCode() ^ 0x55AA55AA;
    }

    public FastSpriteFrame(String id, byte[] frameImage8bpp, int wf, int hf, int timer, Compression compression, int basePal, OptimizationType optType,
            OptimizationLevel optLevel)
    {
        this(id, frameImage8bpp, wf, hf, timer, compression, basePal,
                SpriteEngineUtil.computeSpriteCuttingFast(id, frameImage8bpp, wf, hf, optType, optLevel, basePal));
    }

    public List<SpriteCellWithPalette> getSprites()
    {
        final List<SpriteCellWithPalette> result = new ArrayList<>();

        for (FastVDPSprite sprite : vdpSprites)
            result.add(new SpriteCellWithPalette(
                    new sgdk.rescomp.type.SpriteCell(sprite.offsetX, sprite.offsetY, sprite.wt * 8, sprite.ht * 8, OptimizationType.BALANCED), sprite.pal));

        return result;
    }

    public int getNumSprite()
    {
        return isEmpty() ? 0 : vdpSprites.size();
    }

    public boolean isEmpty()
    {
        return tileset.isEmpty();
    }

    public boolean isOptimisable()
    {
        if (vdpSprites.size() == 1)
        {
            final FastVDPSprite vdpSprite = vdpSprites.get(0);
            return ((vdpSprite.wt * 8) == frameDim.width) && ((vdpSprite.ht * 8) == frameDim.height) && (vdpSprite.offsetX == 0)
                    && (vdpSprite.offsetY == 0);
        }

        return false;
    }

    public int getNumTile()
    {
        return isEmpty() ? 0 : tileset.getNumTile();
    }

    @Override
    public int internalHashCode()
    {
        return hc;
    }

    @Override
    public boolean internalEquals(Object obj)
    {
        if (obj instanceof FastSpriteFrame)
        {
            final FastSpriteFrame spriteFrame = (FastSpriteFrame) obj;
            return (timer == spriteFrame.timer) && tileset.equals(spriteFrame.tileset) && vdpSprites.equals(spriteFrame.vdpSprites);
        }

        return false;
    }

    @Override
    public List<Bin> getInternalBinResources()
    {
        return new ArrayList<>();
    }

    @Override
    public String toString()
    {
        return id + ": numTile=" + getNumTile() + " numSprite=" + getNumSprite();
    }

    @Override
    public int shallowSize()
    {
        // pre-packed templates: a single entry for the optimized single sprite case, 4 flip variant streams
        // otherwise
        final int numTemplate = isOptimisable() ? 1 : (vdpSprites.size() * 4);
        return (numTemplate * 8) + 1 + 1 + 4;
    }

    @Override
    public int totalSize()
    {
        if (isEmpty())
            return shallowSize();

        return tileset.totalSize() + shallowSize();
    }

    @Override
    public void out(ByteArrayOutputStream outB, StringBuilder outS, StringBuilder outH) throws IOException
    {
        // can't store pointer so we just reset binary stream here (used for compression only)
        outB.reset();

        // AnimationFrame structure
        Util.decl(outS, outH, "AnimationFrame", id, 2, global);

        // number of sprite / timer info
        int numSprite = isOptimisable() ? 0x81 : getNumSprite();
        // mark frame using palette deltas (bit 6) so the sprite engine takes the palette aware path
        for (FastVDPSprite sprite : vdpSprites)
        {
            if (sprite.pal != 0)
            {
                numSprite |= 0x40;
                break;
            }
        }
        outS.append("    dc.w    " + (((numSprite << 8) & 0xFF00) | ((timer << 0) & 0xFF)) + "\n");
        // set tileset pointer
        outS.append("    dc.l    " + tileset.id + "\n");

        // no collision pointer, pre-packed VDP sprite templates
        if (isOptimisable())
            // optimized single sprite (no offset) --> flip has no effect on position, a single template entry is
            // enough
            vdpSprites.get(0).writeTemplate(outS, 0, false, false);
        else
        {
            // 4 template streams, one per flip combination in TILE_ATTR flip bits order: normal, H, V, HV
            for (int variant = 0; variant < 4; variant++)
            {
                final boolean hflip = (variant & 1) != 0;
                final boolean vflip = (variant & 2) != 0;

                int tileOffset = 0;
                for (FastVDPSprite sprite : vdpSprites)
                {
                    sprite.writeTemplate(outS, tileOffset, hflip, vflip);
                    tileOffset += sprite.wt * sprite.ht;
                }
            }
        }

        outS.append("\n");
    }
}
