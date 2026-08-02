package sgdk.rescomp.extension.spriteengine;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import sgdk.rescomp.Compiler;
import sgdk.rescomp.Resource;
import sgdk.rescomp.resource.Bin;
import sgdk.rescomp.tool.Util;
import sgdk.rescomp.type.Basics.Compression;
import sgdk.rescomp.type.SpriteCell.OptimizationLevel;
import sgdk.rescomp.type.SpriteCell.OptimizationType;
import sgdk.tool.ImageUtil;

/**
 * One animation row for the fast sprite engine (mirrors core {@code SpriteAnimation}). The "reuse a matching
 * frame's sprite cutting" duplicate-mask optimization is reimplemented here against {@link FastSpriteFrame}'s own
 * package-visible {@code frameImage}/{@code frameDim} fields, since core's equivalent
 * ({@code SpriteAnimation.findMatchingSpriteFrameMask}/{@code checkMaskEqual}) is {@code private} and depends on
 * core {@code SpriteFrame}'s package-private fields, neither reachable from this extension package.
 */
public class FastSpriteAnimation extends Resource
{
    public final List<FastSpriteFrame> frames;
    public final Set<FastSpriteFrame> frameSet;
    public int loopIndex;

    final int hc;

    public FastSpriteAnimation(String id, byte[] image8bpp, int w, int h, int animIndex, int wf, int hf, int[] time, Compression compression, int basePal,
            OptimizationType optType, OptimizationLevel optLevel, boolean optDuplicate)
    {
        super(id);

        frames = new ArrayList<>();
        frameSet = new HashSet<>();
        loopIndex = 0;

        final Dimension imageDim = new Dimension(w * 8, h * 8);
        final int maxFrame = w / wf;

        // find last non transparent frame
        int f = maxFrame - 1;
        while (f >= 0)
        {
            final Rectangle frameBounds = new Rectangle((f * wf) * 8, (animIndex * hf) * 8, wf * 8, hf * 8);
            if (!ImageUtil.isTransparent(image8bpp, imageDim, frameBounds))
                break;

            f--;
        }

        final int numFrame = f + 1;

        for (int i = 0; i < numFrame; i++)
        {
            final Rectangle frameBounds = new Rectangle((i * wf) * 8, (animIndex * hf) * 8, wf * 8, hf * 8);
            final byte[] frameImage = ImageUtil.getSubImage(image8bpp, new Dimension(w * 8, h * 8), frameBounds);

            int duplicate = 0;
            if (optDuplicate)
            {
                for (int j = i + 1; j < numFrame; j++)
                {
                    final Rectangle nextBounds = new Rectangle((j * wf) * 8, (animIndex * hf) * 8, wf * 8, hf * 8);
                    final byte[] nextImage = ImageUtil.getSubImage(image8bpp, new Dimension(w * 8, h * 8), nextBounds);

                    if (!Arrays.equals(frameImage, nextImage))
                        break;

                    duplicate++;
                }
            }

            // palette usage for this frame (mask based sprite cutting re-use is palette dependent)
            final int framePalMask = SpriteEngineUtil.getUsedSpritePalettes(frameImage);
            final boolean multiPalFrame = (framePalMask & (framePalMask - 1)) != 0;

            // try to search for a duplicated sprite mask so we can re-use the previous sprite cutting
            // (not for multi palette frame as its cutting depends on the palette regions, not only on the opacity
            // mask)
            FastSpriteFrame frame = multiPalFrame ? null : findMatchingSpriteFrameMask(frameImage, frameBounds.getSize());
            if (frame != null)
            {
                // re-use previous sprite cutting
                final List<SpriteCellWithPalette> cells = frame.getSprites();

                // re-tag the palette delta for the current frame (the donor frame may use a different palette row)
                if (framePalMask != 0)
                {
                    final int delta = Integer.numberOfTrailingZeros(framePalMask) - basePal;
                    for (SpriteCellWithPalette cell : cells)
                        cell.pal = delta;
                }

                frame = new FastSpriteFrame(id + "_frame" + i, frameImage, wf, hf, time[Math.min(time.length - 1, i)] * (duplicate + 1), compression,
                        basePal, cells);
            }
            else
            {
                frame = new FastSpriteFrame(id + "_frame" + i, frameImage, wf, hf, time[Math.min(time.length - 1, i)] * (duplicate + 1), compression,
                        basePal, optType, optLevel);
            }

            frame = (FastSpriteFrame) addInternalResource(frame);
            i += duplicate;

            frames.add(frame);
            frameSet.add(frame);
        }

        if (frames.size() > 255)
            throw new IllegalArgumentException("Sprite animation '" + id + "' has " + frames.size() + " frames (max = 255)");

        hc = loopIndex ^ frames.hashCode();
    }

    private FastSpriteFrame findMatchingSpriteFrameMask(byte[] frameImage, Dimension dimension)
    {
        for (Resource res : Compiler.getResources(FastSpriteFrame.class))
        {
            final FastSpriteFrame spriteFrame = (FastSpriteFrame) res;

            if (checkMaskEqual(spriteFrame, frameImage, dimension))
                return spriteFrame;
        }

        return null;
    }

    private boolean checkMaskEqual(FastSpriteFrame spriteFrame, byte[] frameImage, Dimension dimension)
    {
        if (!spriteFrame.frameDim.equals(dimension))
            return false;

        final byte[] frame1 = spriteFrame.frameImage;
        final byte[] frame2 = frameImage;

        if (frame1.length != frame2.length)
            return false;

        for (int i = 0; i < frame1.length; i++)
        {
            final boolean p1 = frame1[i] != 0;
            final boolean p2 = frame2[i] != 0;
            if (p1 != p2)
                return false;
        }

        return true;
    }

    public boolean isEmpty()
    {
        return frames.isEmpty();
    }

    public int getNumFrame()
    {
        return frames.size();
    }

    public int getMaxNumTile()
    {
        int result = 0;

        for (FastSpriteFrame frame : frames)
            result = Math.max(result, frame.getNumTile());

        return result;
    }

    public int getMaxNumSprite()
    {
        int result = 0;

        for (FastSpriteFrame frame : frames)
            result = Math.max(result, frame.getNumSprite());

        return result;
    }

    @Override
    public int internalHashCode()
    {
        return hc;
    }

    @Override
    public boolean internalEquals(Object obj)
    {
        if (obj instanceof FastSpriteAnimation)
        {
            final FastSpriteAnimation spriteAnim = (FastSpriteAnimation) obj;
            return (loopIndex == spriteAnim.loopIndex) && frames.equals(spriteAnim.frames);
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
        return id + ": numFrame=" + frames.size() + " maxNumTile=" + getMaxNumTile() + " maxNumSprite=" + getMaxNumSprite();
    }

    @Override
    public int shallowSize()
    {
        return (frames.size() * 4) + 1 + 1 + 4;
    }

    @Override
    public int totalSize()
    {
        int result = 0;

        for (FastSpriteFrame frame : frameSet)
            result += frame.totalSize();

        return result + shallowSize();
    }

    @Override
    public void out(ByteArrayOutputStream outB, StringBuilder outS, StringBuilder outH) throws IOException
    {
        outB.reset();

        // frames pointer table
        Util.decl(outS, outH, null, id + "_frames", 2, false);
        for (FastSpriteFrame frame : frames)
            outS.append("    dc.l    " + frame.id + "\n");

        outS.append("\n");

        // Animation structure
        Util.decl(outS, outH, "Animation", id, 2, global);
        outS.append("    dc.w    " + ((frames.size() << 8) | ((loopIndex << 0) & 0xFF)) + "\n");
        outS.append("    dc.l    " + id + "_frames\n");

        outS.append("\n");
    }
}
