package sgdk.rescomp.extension.spriteengine;

import sgdk.rescomp.Compiler;
import sgdk.rescomp.Processor;
import sgdk.rescomp.Resource;
import sgdk.rescomp.tool.Util;
import sgdk.rescomp.type.Basics.Compression;
import sgdk.rescomp.type.SpriteCell.OptimizationLevel;
import sgdk.rescomp.type.SpriteCell.OptimizationType;
import sgdk.tool.FileUtil;
import sgdk.tool.ImageUtil;
import sgdk.tool.ImageUtil.BasicImageInfo;
import sgdk.tool.StringUtil;

/**
 * SPRITE resource variant for the fast sprite engine (FAST_SPRITE_ENGINE in SGDK config.h).<br>
 * Same syntax as the SPRITE resource but without the 'collision' argument:<br>
 * FASTSPRITE name "file" width height [compression [time [opt_type [opt_level [opt_duplicate]]]]]<br>
 * <br>
 * The generated AnimationFrame data has no collision pointer and stores VDP sprite info as pre-packed
 * SAT entry templates (4 streams, one per flip combination), so it's only compatible with the fast
 * sprite engine (and the classic SPRITE resource is not).<br>
 * <br>
 * Unlike the classic SPRITE resource, the source image can use up to 4 palettes (64 colors), each
 * VDP sprite cell then gets tagged with the delta between its palette row and the sprite base palette.
 */
public class FastSpriteProcessor implements Processor
{
    @Override
    public String getId()
    {
        return "FASTSPRITE";
    }

    @Override
    public Resource execute(String[] fields) throws Exception
    {
        if (fields.length < 5)
        {
            System.out.println("Wrong FASTSPRITE definition");
            System.out.println("FASTSPRITE name \"file\" width height [compression [time [opt_type [opt_level [opt_duplicate]]]]]");
            System.out.println("  name          Sprite variable name");
            System.out.println("  file          image file to convert to a fast sprite resource");
            System.out.println("  width         width of a single sprite frame in tile or pixels (same rules as SPRITE)");
            System.out.println("  height        height of a single sprite frame in tile or pixels (same rules as SPRITE)");
            return null;
        }

        final String id = fields[1];
        final String fileIn = FileUtil.adjustPath(Compiler.resDir, fields[2]);
        final String wArg = fields[3];
        final String hArg = fields[4];

        final BasicImageInfo imgInfo = ImageUtil.getBasicInfo(fileIn);

        final int wf;
        final int hf;

        if (StringUtil.isTaggedNumber(wArg.toUpperCase(), "P"))
        {
            final int wfPix = StringUtil.parseTaggedInt(wArg.toUpperCase(), "P", 0);
            if ((wfPix % 8) != 0)
                throw new IllegalArgumentException("Error: the Sprite '" + id + "' width parameter (" + wfPix + " pixels), is not a multiple of 8");
            wf = wfPix / 8;
        }
        else if (StringUtil.isTaggedNumber(wArg.toUpperCase(), "F"))
        {
            final int fc = StringUtil.parseTaggedInt(wArg.toUpperCase(), "F", 0);
            final int w = imgInfo.w;
            if ((w % fc) != 0)
                throw new IllegalArgumentException("Error: '" + fileIn + "' width (" + w + ") is not a multiple of frame count (" + fc + ")");
            wf = w / fc / 8;
        }
        else
        {
            wf = StringUtil.parseInt(wArg, 0);
        }

        if (StringUtil.isTaggedNumber(hArg.toUpperCase(), "P"))
        {
            final int hfPix = StringUtil.parseTaggedInt(hArg.toUpperCase(), "P", 0);
            if ((hfPix % 8) != 0)
                throw new IllegalArgumentException("Error: the Sprite '" + id + "' height parameter (" + hfPix + " pixels), is not a multiple of 8");
            hf = hfPix / 8;
        }
        else if (StringUtil.isTaggedNumber(hArg.toUpperCase(), "F"))
        {
            final int fc = StringUtil.parseTaggedInt(hArg.toUpperCase(), "F", 0);
            final int h = imgInfo.h;
            if ((h % fc) != 0)
                throw new IllegalArgumentException("Error: '" + fileIn + "' height (" + h + ") is not a multiple of frame count (" + fc + ")");
            hf = h / fc / 8;
        }
        else
        {
            hf = StringUtil.parseInt(hArg, 0);
        }

        if ((wf < 1) || (hf < 1))
            throw new IllegalArgumentException("Wrong FASTSPRITE definition: width and height should be > 0");
        if ((wf >= 32) || (hf >= 32))
            throw new IllegalArgumentException("Wrong FASTSPRITE definition: width and height should be < 32");

        Compression compression = Compression.NONE;
        if (fields.length >= 6)
            compression = Util.getCompression(fields[5]);

        int[][] time = new int[][] {{0}};
        if (fields.length >= 7)
            time = StringUtil.parseIntArray2D(fields[6], new int[][] {{0}});

        OptimizationType opt = OptimizationType.BALANCED;
        int fieldInd = 7;
        if (fields.length > fieldInd)
            opt = Util.getSpriteOptType(fields[fieldInd]);
        fieldInd++;

        OptimizationLevel optLevel = OptimizationLevel.FAST;
        boolean showCut = false;
        if (fields.length > fieldInd)
        {
            optLevel = Util.getSpriteOptLevel(fields[fieldInd]);
            showCut = true;
        }
        fieldInd++;

        boolean optDuplicate = false;
        if (fields.length > fieldInd)
            optDuplicate = Boolean.parseBoolean(fields[fieldInd]);

        // add resource file (used for deps generation)
        Compiler.addResourceFile(fileIn);

        return new FastSprite(id, fileIn, wf, hf, compression, time, opt, optLevel, showCut, optDuplicate);
    }
}
