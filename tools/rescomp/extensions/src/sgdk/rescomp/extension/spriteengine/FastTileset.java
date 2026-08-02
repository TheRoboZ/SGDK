package sgdk.rescomp.extension.spriteengine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import sgdk.rescomp.Resource;
import sgdk.rescomp.resource.Bin;
import sgdk.rescomp.tool.Util;
import sgdk.rescomp.type.Basics.Compression;
import sgdk.rescomp.type.Tile;

/**
 * Sprite frame tileset with a specific source image per sprite cell (fast sprite engine multi palette support:
 * each cell uses the image masked to its own palette so tile data never mixes palettes).<br>
 * Replicates core {@code Tileset}'s {@code TileSet} struct output format exactly (word compression flag, word tile
 * count, long bin pointer) since core {@code Tileset} has no constructor accepting a per-cell source image and its
 * tile-tracking internals are private.<br>
 * Unlike core {@code Tileset}, tiles are not de-duplicated across cells (palette-masked cell images rarely share
 * identical tile data anyway, and de-duplication would require reaching into core's private tile-tracking maps).
 */
public class FastTileset extends Resource
{
    private final List<Tile> tiles;
    public final Bin bin;
    final int hc;

    public FastTileset(String id, byte[][] cellImages8bpp, int imageWidth, int imageHeight, List<SpriteCellWithPalette> sprites, Compression compression)
    {
        super(id);

        tiles = new ArrayList<>();

        int ind = 0;
        for (SpriteCellWithPalette sprite : sprites)
        {
            final byte[] image8bpp = cellImages8bpp[ind++];
            final int widthTile = sprite.cell.width / 8;
            final int heightTile = sprite.cell.height / 8;

            // important to respect sprite tile ordering (vertical)
            for (int i = 0; i < widthTile; i++)
                for (int j = 0; j < heightTile; j++)
                    tiles.add(Tile.getTile(image8bpp, imageWidth, imageHeight, sprite.cell.x + (i * 8), sprite.cell.y + (j * 8), 8));
        }

        // build the binary bloc
        final int[] data = new int[tiles.size() * 8];

        int offset = 0;
        for (Tile t : tiles)
        {
            System.arraycopy(t.data, 0, data, offset, 8);
            offset += 8;
        }

        // build BIN (tiles data) with wanted compression, register as internal resource (avoid duplicate)
        final Bin binResource = new Bin(id + "_data", data, compression);
        binResource.global = false;
        bin = (Bin) addInternalResource(binResource);

        // compute hash code
        hc = bin.hashCode();
    }

    public int getNumTile()
    {
        return tiles.size();
    }

    public boolean isEmpty()
    {
        return tiles.isEmpty();
    }

    @Override
    public int internalHashCode()
    {
        return hc;
    }

    @Override
    public boolean internalEquals(Object obj)
    {
        if (obj instanceof FastTileset)
        {
            final FastTileset tileset = (FastTileset) obj;
            return bin.equals(tileset.bin);
        }

        return false;
    }

    @Override
    public List<Bin> getInternalBinResources()
    {
        return Arrays.asList(bin);
    }

    @Override
    public int shallowSize()
    {
        return 2 + 2 + 4;
    }

    @Override
    public int totalSize()
    {
        return bin.totalSize() + shallowSize();
    }

    @Override
    public void out(ByteArrayOutputStream outB, StringBuilder outS, StringBuilder outH) throws IOException
    {
        // can't store pointer so we just reset binary stream here (used for compression only)
        outB.reset();

        // output TileSet structure
        Util.decl(outS, outH, "TileSet", id, 2, global);
        // set compression info (very important that binary data had already been exported at this point)
        outS.append("    dc.w    " + (bin.doneCompression.ordinal() - 1) + "\n");
        // set number of tile
        outS.append("    dc.w    " + getNumTile() + "\n");
        // set data pointer
        outS.append("    dc.l    " + bin.id + "\n");
        outS.append("\n");
    }
}
