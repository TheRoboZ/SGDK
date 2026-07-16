package sgdk.rescomp.processor;

/**
 * SPRITE resource variant for the fast sprite engine (FAST_SPRITE_ENGINE in SGDK config.h).<br>
 * Same syntax as the SPRITE resource but without the 'collision' argument:<br>
 * FASTSPRITE name "file" width height [compression [time [opt_type [opt_level [opt_duplicate]]]]]<br>
 * <br>
 * The generated AnimationFrame data has no collision pointer and stores VDP sprite info as pre-packed
 * SAT entry templates (4 streams, one per flip combination), so it's only compatible with the fast
 * sprite engine (and the classic SPRITE resource is not).
 */
public class FastSpriteProcessor extends SpriteProcessor
{
    @Override
    public String getId()
    {
        return "FASTSPRITE";
    }

    @Override
    protected boolean isFastFormat()
    {
        return true;
    }
}
