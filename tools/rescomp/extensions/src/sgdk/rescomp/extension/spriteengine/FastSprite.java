package sgdk.rescomp.extension.spriteengine;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import sgdk.rescomp.Resource;
import sgdk.rescomp.resource.Bin;
import sgdk.rescomp.resource.Palette;
import sgdk.rescomp.tool.Util;
import sgdk.rescomp.type.Basics.Compression;
import sgdk.rescomp.type.SpriteCell.OptimizationLevel;
import sgdk.rescomp.type.SpriteCell.OptimizationType;
import sgdk.tool.ArrayMath;
import sgdk.tool.FileUtil;
import sgdk.tool.ImageUtil;
import sgdk.tool.ImageUtil.BasicImageInfo;

/**
 * Top level sprite resource for the fast sprite engine (mirrors core {@code Sprite}), except the source image may
 * span up to 4 palette rows (64 colors) instead of a single one: the used palette row span is auto-detected and
 * the generated {@link Palette} resource is sized to cover it, with each cut sprite cell tagged with its delta
 * relative to the base (lowest used) palette row.
 */
public class FastSprite extends Resource
{
    public final int wf; // width of frame cell in tile
    public final int hf; // height of frame cell in tile
    public final List<FastSpriteAnimation> animations;
    public int maxNumTile;
    public int maxNumSprite;

    final int hc;

    public final Palette palette;

    public FastSprite(String id, String imgFile, int wf, int hf, Compression compression, int[][] time, OptimizationType optType, OptimizationLevel optLevel,
            boolean showCut, boolean optDuplicate) throws Exception
    {
        super(id);

        maxNumTile = 0;
        maxNumSprite = 0;
        animations = new ArrayList<>();

        // frame size over limit (we need VDP sprite offset to fit into u8 type)
        if ((wf >= 32) || (hf >= 32))
            throw new IllegalArgumentException("FASTSPRITE '" + id + "' has frame width or frame height >= 32 tiles (not supported)");

        this.wf = wf;
        this.hf = hf;

        // get 8bpp pixels and also check image dimension is aligned to tile
        final byte[] image = ImageUtil.getImageAs8bpp(imgFile, true, true);

        if (image == null)
            throw new IllegalArgumentException(
                    "RGB image '" + imgFile + "' does not contains palette data (see 'Important note about image format' in the rescomp.txt file");

        // find max color index
        final int maxIndex = ArrayMath.max(image, false);
        if (maxIndex >= 64)
            throw new IllegalArgumentException("'" + imgFile
                    + "' uses color index >= 64, FASTSPRITE resource requires image with a maximum of 64 colors, use 4bpp indexed colors image instead if you are unsure.");

        final BasicImageInfo imgInfo = ImageUtil.getBasicInfo(imgFile);
        final int w = imgInfo.w;
        final int h = image.length / w;

        // auto-detect the used palette rows from the pixel data (up to 4 palettes supported)
        final int palMask = SpriteEngineUtil.getUsedSpritePalettes(image);
        final int minPal = (palMask != 0) ? Integer.numberOfTrailingZeros(palMask) : 0;
        final int maxPal = (palMask != 0) ? (31 - Integer.numberOfLeadingZeros(palMask)) : 0;

        final int palIndex = minPal;
        final int numPal = (maxPal - minPal) + 1;

        if (numPal > 1)
            System.out.println("FASTSPRITE '" + id + "' uses " + numPal + " palettes (rows " + minPal + " to " + maxPal
                    + "), palette deltas are relative to base palette / row " + minPal);

        // get size in tile
        final int wt = w / 8;
        final int ht = h / 8;

        if ((wt % wf) != 0)
            throw new IllegalArgumentException("Error: '" + imgFile + "' width (" + w + ") is not a multiple of cell width (" + (wf * 8) + ").");
        if ((ht % hf) != 0)
            throw new IllegalArgumentException("Error: '" + imgFile + "' height (" + h + ") is not a multiple of cell height (" + (hf * 8) + ").");

        // build PALETTE (span all used palette rows for multi palette sprite)
        palette = (Palette) addInternalResource(new Palette(id + "_palette", imgFile, palIndex * 16, numPal * 16, true));

        // for debug purpose (scale image x2 so it's easier to see bounding boxes)
        final BufferedImage bufImg = ImageUtil.scale(ImageUtil.load(imgFile), w * 2, h * 2, false);
        final Graphics2D g2 = bufImg.createGraphics();
        g2.setColor(Color.pink);

        final int numAnim = ht / hf;

        int yOff = 0;
        for (int i = 0; i < numAnim; i++)
        {
            FastSpriteAnimation animation = new FastSpriteAnimation(id + "_animation" + i, image, wt, ht, i, wf, hf, time[Math.min(time.length - 1, i)],
                    compression, palIndex, optType, optLevel, optDuplicate);

            if (!animation.isEmpty())
            {
                animation = (FastSpriteAnimation) addInternalResource(animation);

                if (showCut)
                {
                    int xOff = 0;
                    int yMargin = (imgInfo.bpp > 8) ? 32 : 0;
                    for (FastSpriteFrame frame : animation.frames)
                    {
                        for (FastVDPSprite spr : frame.vdpSprites)
                            g2.drawRect((xOff + spr.offsetX) * 2, (yMargin + yOff + spr.offsetY) * 2, ((spr.wt * 8) * 2) - 1, ((spr.ht * 8) * 2) - 1);

                        xOff += wf * 8;
                    }
                }

                maxNumTile = Math.max(maxNumTile, animation.getMaxNumTile());
                maxNumSprite = Math.max(maxNumSprite, animation.getMaxNumSprite());

                animations.add(animation);
            }

            yOff += hf * 8;
        }

        g2.dispose();

        if (showCut)
            ImageUtil.save(bufImg, "png", FileUtil.setExtension(imgFile, "") + "_opt.png");

        hc = (wf << 0) ^ (hf << 8) ^ (maxNumTile << 16) ^ (maxNumSprite << 24) ^ animations.hashCode() ^ palette.hashCode();
    }

    @Override
    public int internalHashCode()
    {
        return hc;
    }

    @Override
    public boolean internalEquals(Object obj)
    {
        if (obj instanceof FastSprite)
        {
            final FastSprite sprite = (FastSprite) obj;
            return (wf == sprite.wf) && (hf == sprite.hf) && (maxNumTile == sprite.maxNumTile) && (maxNumSprite == sprite.maxNumSprite)
                    && animations.equals(sprite.animations) && palette.equals(sprite.palette);
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
        return id + ": wf=" + wf + " hf=" + hf + " numAnim=" + animations.size() + " maxNumTile=" + maxNumTile + " maxNumSprite=" + maxNumSprite;
    }

    @Override
    public int shallowSize()
    {
        return (animations.size() * 4) + 2 + 2 + 4 + 2 + 4 + 2 + 2;
    }

    @Override
    public int totalSize()
    {
        int result = 0;

        for (FastSpriteAnimation animation : animations)
            result += animation.totalSize();

        return result + palette.totalSize() + shallowSize();
    }

    @Override
    public void out(ByteArrayOutputStream outB, StringBuilder outS, StringBuilder outH) throws IOException
    {
        outB.reset();

        // animations pointer table
        Util.decl(outS, outH, null, id + "_animations", 2, false);
        for (FastSpriteAnimation animation : animations)
            outS.append("    dc.l    " + animation.id + "\n");

        outS.append("\n");

        // SpriteDefinition structure
        Util.decl(outS, outH, "SpriteDefinition", id, 2, global);
        outS.append("    dc.w    " + (wf * 8) + "\n");
        outS.append("    dc.w    " + (hf * 8) + "\n");
        outS.append("    dc.l    " + palette.id + "\n");
        outS.append("    dc.w    " + animations.size() + "\n");
        outS.append("    dc.l    " + id + "_animations" + "\n");
        outS.append("    dc.w    " + maxNumTile + "\n");
        outS.append("    dc.w    " + maxNumSprite + "\n");

        outS.append("\n");
    }
}
