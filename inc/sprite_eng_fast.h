/**
 *  \file sprite_eng_fast.h
 *  \brief Fast / streamlined Sprite engine
 *  \author Stephane Dallongeville
 *  \author TheRoboZ
 *  \date 07/2026
 *
 * Streamlined sprite engine focused on performance (enabled with FAST_SPRITE_ENGINE in config.h).<br>
 * It's based on the default sprite engine (full sprite table rebuild each frame) with the following differences:<br>
 * - no collision structures (the unfinished collision detection has been removed, including from resource data)<br>
 * - visibility is computed for the whole (meta) sprite only (no per-hardware-sprite visibility)<br>
 * - no delayed frame update (frame changes are always applied immediately, DMA queue capacity is not tested)<br>
 * - animation frame VDP sprite data is pre-packed by rescomp in Sprite Attribute Table field order,
 *   with dedicated streams for each flip combination, so the sprite table update loop requires no
 *   flip computation and a minimal amount of work per hardware sprite<br>
 * <br>
 * IMPORTANT: this engine requires resources exported with the rescomp <b>FASTSPRITE</b> resource type,
 * the classic SPRITE resource data layout is NOT compatible (and vice-versa).<br>
 * <br>
 * Performance note: a compressed tileset requires a full CPU unpack on <i>every animation frame change</i>
 * (by far the biggest per-frame cost when it happens), prefer compression NONE for frequently animated sprites
 * and keep compression for static / rarely changing ones.
 */

#if     FAST_SPRITE_ENGINE

#ifndef _SPRITE_ENG_FAST_H_
#define _SPRITE_ENG_FAST_H_

#include "vdp_tile.h"
#include "vdp_spr.h"
#include "pal.h"
#include "pool.h"


/**
 *  \brief
 *      Special flag to indicate that we want to add the sprite at position 0 (head / top) in the list<br>
 *      instead of adding it in last / bottom position (default)
 */
#define SPR_FLAG_INSERT_HEAD                    0x4000
/**
 *  \brief
 *      Disable animation auto loop.<br>
 *      By default animation always restart after the last frame has been played.
 *      This flag prevent the animation to restart and so the animation end on the last frame forever (see #SPR_isAnimationDone(..))
 */
#define SPR_FLAG_DISABLE_ANIMATION_LOOP         0x2000
/**
 *  \brief
 *      Enable automatic VRAM allocation
 */
#define SPR_FLAG_AUTO_VRAM_ALLOC                0x0800
/**
 *  \brief
 *      Enable automatic upload of sprite tiles data into VRAM
 */
#define SPR_FLAG_AUTO_TILE_UPLOAD               0x0400
/**
 *  \brief
 *      Enable automatic visibility calculation (whole meta sprite visibility)
 */
#define SPR_FLAG_AUTO_VISIBILITY                0x0200

/**
 *  \brief
 *      Mask for sprite flag
 */
#define SPR_FLAG_MASK                           (SPR_FLAG_INSERT_HEAD | SPR_FLAG_DISABLE_ANIMATION_LOOP | SPR_FLAG_AUTO_VRAM_ALLOC | SPR_FLAG_AUTO_TILE_UPLOAD | SPR_FLAG_AUTO_VISIBILITY)

/**
 *  \brief
 *      Minimum depth for a sprite (always above others sprites)
 */
#define SPR_MIN_DEPTH       (-0x8000)
/**
 *  \brief
 *      Maximum depth for a sprite (always below others sprites)
 */
#define SPR_MAX_DEPTH       0x7FFF

/**
 *  \brief
 *      Sprite visibility enumeration
 */
typedef enum
{
    VISIBLE,        /**< Sprite is visible (no computation needed) */
    HIDDEN,         /**< Sprite is hidden (no computation needed) */
    AUTO_VISIBILITY /**< Automatic visibility calculation (whole meta sprite) */
} SpriteVisibility;

/**
 *  \brief
 *      Pre-packed VDP sprite info for sprite animation frame (fast sprite engine format).
 *
 *      Fields are stored in hardware Sprite Attribute Table entry order with all static parts pre-composed
 *      by rescomp, so building a SAT entry at runtime only requires one add per field:<br>
 *      SAT.y    = y + sprite->y<br>
 *      SAT.size_link = sizeAndLink + link<br>
 *      SAT.attr = attrOffset + sprite->attribut<br>
 *      SAT.x    = x + sprite->x<br>
 *
 *  \param y
 *      Y offset for this VDP sprite relative to global Sprite position (flip-adjusted per stream)
 *  \param sizeAndLink
 *      sprite size (see SPRITE_SIZE macro) pre-shifted in the high byte, link (low byte) is added at runtime
 *  \param attrOffset
 *      cumulated tile index offset for this VDP sprite (added to the sprite base attribute at runtime)
 *  \param x
 *      X offset for this VDP sprite relative to global Sprite position (flip-adjusted per stream)
 */
typedef struct
{
    u16 y;
    u16 sizeAndLink;
    u16 attrOffset;
    u16 x;
} FrameVDPSprite;

/**
 *  \brief
 *      Sprite animation frame structure (fast sprite engine format).
 *
 *  \param numSprite
 *      number of VDP sprite which compose this frame.
 *      bit 7 is used as a special flag for the sprite engine so always use 'numSprite & 0x7F' to just retrieve the number of sprite
 *  \param timer
 *      active time for this frame (in 1/60 of second)
 *  \param tileset
 *      tileset containing tiles for this animation frame (ordered for sprite)
 *  \param frameVDPSprites
 *      pre-packed VDP sprites info composing the frame.<br>
 *      4 consecutive streams of <i>numSprite</i> entries, one per flip combination in TILE_ATTR flip bits order:
 *      [normal][H flip][V flip][HV flip].<br>
 *      Special case: when numSprite is negative (single VDP sprite perfectly covering the frame) a single entry is stored.
 */
typedef struct
{
    s8 numSprite;
    u8 timer;
    TileSet* tileset;
    FrameVDPSprite frameVDPSprites[];
} AnimationFrame;

/**
 *  \brief
 *      Sprite animation structure.
 *
 *  \param numFrame
 *      number of different frame for this animation (max = 255)
 *  \param loop
 *      frame index for loop (last index if no loop)
 *  \param frames
 *      frames composing the animation
 */
typedef struct
{
    u8 numFrame;
    u8 loop;
    AnimationFrame** frames;
} Animation;

/**
 *  \brief
 *      Sprite definition structure.
 *
 *  \param w
 *      frame cell width in pixel
 *  \param h
 *      frame cell height in pixel
 *  \param palette
 *      default palette data
 *  \param numAnimation
 *      number of animation for this sprite
 *  \param animations
 *      animation definitions
 *  \param maxNumTile
 *      maximum number of tile used by a single animation frame (used for VRAM tile space allocation)
 *  \param maxNumSprite
 *      maximum number of VDP sprite used by a single animation frame (used for VDP sprite allocation)
 *
 *  Contains all animations for a Sprite and internal informations.
 */
typedef struct
{
    u16 w;
    u16 h;
    Palette* palette;
    u16 numAnimation;
    Animation** animations;
    u16 maxNumTile;
    u16 maxNumSprite;
} SpriteDefinition;

/**
 *  \brief
 *      Sprite structure used by the Sprite Engine to store state for a sprite.<br>
 *      WARNING: always use the #SPR_addSprite(..) method to allocate Sprite object.<br>
 *
 *  \param status
 *      Internal state and automatic allocation information (internal)
 *  \param visibility
 *      visibility information of current frame: 0 = hidden, anything else = visible (internal)
 *  \param definition
 *      Sprite definition pointer
 *  \param onFrameChange
 *      Custom callback on frame change event (see #SPR_setFrameChangeCallback(..) method)
 *  \param animation
 *      Animation pointer cache (internal)
 *  \param frame
 *      AnimationFrame pointer cache (internal)
 *  \param animInd
 *      current animation index (internal)
 *  \param frameInd
 *      current frame animation index (internal)
 *  \param timer
 *      timer for current frame (internal)
 *  \param x
 *      current sprite X position on screen offseted by 0x80 (internal VDP position)
 *  \param y
 *      current sprite Y position on screen offseted by 0x80 (internal VDP position)
 *  \param depth
 *      current sprite depth (Z) position used for Z sorting
 *  \param attribut
 *      sprite specific attribut and allocated VRAM tile index (see TILE_ATTR_FULL() macro)
 *  \param data
 *      this is a free field for user data, use it for whatever you want (flags, pointer...)
 *  \param prev
 *      pointer on previous Sprite in list
 *  \param next
 *      pointer on next Sprite in list
 *
 *  Used to manage an active sprite in game condition.
 */
typedef struct Sprite
{
    u16 status;
    u16 visibility;
    const SpriteDefinition* definition;
    void (*onFrameChange)(struct Sprite* sprite);
    Animation* animation;
    AnimationFrame* frame;
    s16 animInd;
    s16 frameInd;
    s16 timer;
    s16 x;
    s16 y;
    s16 depth;
    u16 attribut;
    u32 data;
    struct Sprite* prev;
    struct Sprite* next;
} Sprite;

/**
 *  \brief
 *      Sprite frame change event callback.<br>
 *
 *  \param sprite
 *      The sprite for which frame just changed.
 *
 *      This event occurs on frame change process during #SPR_update() call (CAUTION: sprite->status field is not up to date at this point).<br>
 *      It let opportunity to the developer to apply special behavior or process when sprite frame just changed:<br>
 *      for instance we can disable animation looping by setting sprite->timer to 0 when we meet the last animation frame.
 */
typedef void FrameChangeCallback(Sprite* sprite);

/**
 * Sprites object pool for the sprite engine
 */
extern Pool* spritesPool;
/**
 * First allocated sprite (NULL if no sprite allocated)
 */
extern Sprite* firstSprite;
/**
 * Last allocated sprite (NULL if no sprite allocated)
 */
extern Sprite* lastSprite;
/**
 * Allocated VRAM (in tile) for Sprite Engine
 */
extern u16 spriteVramSize;


/**
 *  \brief
 *      Initialize the Sprite engine with default parameters (420 reserved tiles in VRAM).
 *
 *  \see SPR_initEx(u16)
 *  \see SPR_end(void)
 */
void SPR_init(void);
/**
 *  \brief
 *      Init the Sprite engine with specified VRAM allocation size.
 *
 *  \param vramSize
 *      size (in tile) of the VRAM region for the automatic VRAM tile allocation.<br>
 *      If set to 0 the default size is used (420 tiles)
 *
 *  \see SPR_init(void)
 *  \see SPR_end(void)
 */
void SPR_initEx(u16 vramSize);
/**
 *  \brief
 *      End the Sprite engine and release attached resources.
 */
void SPR_end(void);
/**
 *  \brief
 *      FALSE if sprite cache engine is not initialized, TRUE otherwise.
 */
bool SPR_isInitialized(void);

/**
 *  \brief
 *      Reset the Sprite engine (release all allocated sprites and their resources).
 */
void SPR_reset(void);

/**
 *  \brief
 *      Adds a new sprite with specified parameters and returns it.
 *
 *  \param spriteDef
 *      the SpriteDefinition data to assign to this sprite.
 *  \param x
 *      default X position.
 *  \param y
 *      default Y position.
 *  \param attribut
 *      sprite attribut (see TILE_ATTR() macro).
 *  \param flag
 *      specific settings for this sprite:<br>
 *      #SPR_FLAG_AUTO_VISIBILITY = Enable automatic sprite visibility calculation (whole meta sprite).<br>
 *      #SPR_FLAG_AUTO_VRAM_ALLOC = Enable automatic VRAM allocation (enabled by default)<br>
 *      #SPR_FLAG_AUTO_TILE_UPLOAD = Enable automatic upload of sprite tiles data into VRAM (enabled by default)<br>
 *      #SPR_FLAG_INSERT_HEAD = Allow to insert the sprite at the start/head of the list (top most).<br>
 *  \return the new sprite or <i>NULL</i> if the operation failed (some logs can be generated in the KMod console in this case)
 *
 *      IMPORTANT NOTE: sprite allocation can fail (return NULL) when you are using auto VRAM allocation (SPR_FLAG_AUTO_VRAM_ALLOC) even if there is enough VRAM available,<br>
 *      this can happen because of the VRAM fragmentation. You can use #SPR_addSpriteExSafe(..) method instead so it take care about VRAM fragmentation.
 *
 *  \see SPR_addSprite(..)
 *  \see SPR_addSpriteExSafe(..)
 *  \see SPR_releaseSprite(..)
 */
Sprite* SPR_addSpriteEx(const SpriteDefinition* spriteDef, s16 x, s16 y, u16 attribut, u16 flag);
/**
 *  \brief
 *      Adds a new sprite with auto resource allocation enabled and returns it.
 *
 *  \param spriteDef
 *      the SpriteDefinition data to assign to this sprite.
 *  \param x
 *      default X position.
 *  \param y
 *      default Y position.
 *  \param attribut
 *      sprite attribut (see TILE_ATTR() macro).
 *  \return the new sprite or <i>NULL</i> if the operation failed
 *
 *  \see SPR_addSpriteEx(..)
 *  \see SPR_addSpriteSafe(..)
 *  \see SPR_releaseSprite(..)
 */
Sprite* SPR_addSprite(const SpriteDefinition* spriteDef, s16 x, s16 y, u16 attribut);
/**
 *  \brief
 *      Same as #SPR_addSpriteEx(..) but takes care of VRAM fragmentation (may trigger a #SPR_defragVRAM() call).
 */
Sprite* SPR_addSpriteExSafe(const SpriteDefinition* spriteDef, s16 x, s16 y, u16 attribut, u16 flag);
/**
 *  \brief
 *      Same as #SPR_addSprite(..) but takes care of VRAM fragmentation (may trigger a #SPR_defragVRAM() call).
 */
Sprite* SPR_addSpriteSafe(const SpriteDefinition* spriteDef, s16 x, s16 y, u16 attribut);

/**
 *  \brief
 *      Release the specified sprite (no more visible and release its resources).
 *
 *  \param sprite
 *      Sprite to release
 */
void SPR_releaseSprite(Sprite* sprite);
/**
 *  \brief
 *      Returns the number of active sprite (number of sprite added with SPR_addSprite(..) or SPR_addSpriteEx(..) methods).
 */
u16 SPR_getNumActiveSprite(void);
/**
 *  \brief
 *      Returns the (maximum) number of used VDP sprite from current active sprites (sum of maximum hardware sprite usage from all active sprites).
 */
u16 SPR_getUsedVDPSprite(void);
/**
 *  \brief
 *      Returns the current remaining free VRAM (in tile) for the sprite engine.
 */
u16 SPR_getFreeVRAM(void);
/**
 *  \brief
 *      Return the current largest free VRAM block size (in tile) for the sprite engine.
 */
u16 SPR_getLargestFreeVRAMBlock(void);

/**
 *  \brief
 *      Prevent adding a new sprite if there is possibly not enough hardware sprite to display it.
 *
 *  \see SPR_disableVDPSpriteChecking(..)
 */
void SPR_enableVDPSpriteChecking(void);
/**
 *  \brief
 *      Allow the sprite engine to add a new sprite even if we may run out of hardware sprite to display all of them (default behavior).
 *
 *  \see SPR_enableVDPSpriteChecking(..)
 */
void SPR_disableVDPSpriteChecking(void);
/**
 *  \brief
 *      Defragment allocated VRAM for sprites, that can help when sprite allocation fail (SPR_addSprite(..) or SPR_addSpriteEx(..) return <i>NULL</i>).
 */
void SPR_defragVRAM(void);

/**
 *  \brief
 *      Load all frames of SpriteDefinition at specified VRAM tile index and return the indexes table.<br>
 *      <b>WARNING: This function should be call at init/loading time as it can be quite long (several frames)</b>
 *
 *  \param sprDef
 *      the SpriteDefinition we want to load frame data in VRAM.
 *  \param index
 *      the tile position in VRAM where we will upload all sprite frame tiles data.
 *  \param totalNumTile
 *      if not NULL then the function will store here the total number of tile used to load all animation frames.
 *  \param tm
 *      Transfer method to upload sprite frame data (CPU, DMA, DMA_QUEUE or DMA_QUEUE_COPY)
 *
 *   The returned index table is a dynamically allocated 2D table[anim][frame] so you need to release it using #MEM_free(..)
 *   when you don't need the table anymore.<br>
 *   You can use the frame change callback (see #SPR_setFrameChangeCallback(..)) to automatically update the VRAM index using the indexes table:<br>
 *   <code>frameIndexes = SPR_loadAllFrames(sprite->definition, ind);<br>
 *   SPR_setFrameChangeCallback(sprite, &frameChanged);<br>
 *   ....<br>
 *   void frameChanged(Sprite* sprite)<br>
 *   {<br>
 *       u16 tileIndex = frameIndexes[sprite->animInd][sprite->frameInd];<br>
 *       SPR_setVRAMTileIndex(sprite, tileIndex);<br>
 *   }</code>
 *
 *  \return the 2D indexes table or NULL if there is not enough memory to allocate the table.
 *  \see SPR_setFrameChangeCallback(...);
 */
u16** SPR_loadAllFramesEx(const SpriteDefinition* sprDef, u16 index, u16* totalNumTile, TransferMethod tm);
/**
 *  \brief
 *      Same as #SPR_loadAllFramesEx(..) using DMA transfer method.
 *
 *  \see SPR_loadAllFramesEx(...)
 */
u16** SPR_loadAllFrames(const SpriteDefinition* sprDef, u16 index, u16* totalNumTile);
/**
 *  \brief
 *      Same as #SPR_loadAllFrames(..) but only computes the indexes table without actually loading the Sprite frame data to VRAM (see #SPR_loadAllTiles(..) for that).
 *
 *  \see SPR_loadAllFrames(...)
 *  \see SPR_loadAllTiles(...)
 */
u16** SPR_loadAllIndexes(const SpriteDefinition* sprDef, u16 index, u16* totalNumTile);
/**
 *  \brief
 *      Same as #SPR_loadAllFrames(..) but only perform the Sprite tile data upload process, SPR_loadAllIndexes(..) should be called first to compute the indexes table.
 *
 *  \see SPR_loadAllFrames(...)
 *  \see SPR_loadAllIndexes(...)
 */
u16 SPR_loadAllTiles(const SpriteDefinition* sprDef, u16 index, u16** indexes, const TransferMethod tm);

/**
 *  \brief
 *      Set the Sprite Definition.
 *
 *  \param sprite
 *      Sprite to set definition for.
 *  \param spriteDef
 *      the SpriteDefinition data to assign to this sprite.
 *
 *  \return FALSE if auto resource allocation failed, TRUE otherwise.
 */
bool SPR_setDefinition(Sprite* sprite, const SpriteDefinition* spriteDef);
/**
 *  \brief
 *      Get sprite position X.
 */
s16 SPR_getPositionX(Sprite* sprite);
/**
 *  \brief
 *      Get sprite position Y.
 */
s16 SPR_getPositionY(Sprite* sprite);
/**
 *  \brief
 *      Set sprite position.
 *
 *  \param sprite
 *      Sprite to set position for
 *  \param x
 *      X position
 *  \param y
 *      Y position
 */
void SPR_setPosition(Sprite* sprite, s16 x, s16 y);
/**
 *  \brief
 *      Set sprite Horizontal Flip attribut.
 *
 *  \param sprite
 *      Sprite to set attribut for
 *  \param value
 *      The horizontal flip attribut value (TRUE or FALSE)
 */
void SPR_setHFlip(Sprite* sprite, bool value);
/**
 *  \brief
 *      Set sprite Vertical Flip attribut.
 *
 *  \param sprite
 *      Sprite to set attribut for
 *  \param value
 *      The vertical flip attribut value (TRUE or FALSE)
 */
void SPR_setVFlip(Sprite* sprite, bool value);
/**
 *  \brief
 *      Set sprite Palette index to use.
 *
 *  \param sprite
 *      Sprite to set attribut for
 *  \param value
 *      The palette index to use for this sprite (PAL0, PAL1, PAL2 or PAL3)
 */
void SPR_setPalette(Sprite* sprite, u16 value);
/**
 *  \brief
 *      Set sprite Priority attribut.
 *
 *  \param sprite
 *      Sprite to set attribut for
 *  \param value
 *      The priority attribut value (TRUE or FALSE)
 */
void SPR_setPriority(Sprite* sprite, bool value);
/**
 *  \brief
 *      Set sprite depth (for sprite display ordering)
 *
 *  \param sprite
 *      Sprite to set depth for
 *  \param value
 *      The depth value (SPR_MIN_DEPTH to set always on top)
 *
 *  Sprite having lower depth are display in front of sprite with higher depth.<br>
 *  The sprite is *immediately* sorted when its depth value is changed.
 */
void SPR_setDepth(Sprite* sprite, s16 value);
/**
 *  \brief
 *      Same as #SPR_setDepth(..)
 */
void SPR_setZ(Sprite* sprite, s16 value);
/**
 *  \brief
 *      Set sprite depth so it remains above others sprite - same as SPR_setDepth(SPR_MIN_DEPTH)
 */
void SPR_setAlwaysOnTop(Sprite* sprite);
/**
 *  \brief
 *      Set sprite depth so it remains behind others sprite - same as SPR_setDepth(SPR_MAX_DEPTH)
 */
void SPR_setAlwaysAtBottom(Sprite* sprite);
/**
 *  \brief
 *      Set current sprite animation and frame.
 *
 *  \param sprite
 *      Sprite to set animation and frame for
 *  \param anim
 *      animation index to set
 *  \param frame
 *      frame index to set
 */
void SPR_setAnimAndFrame(Sprite* sprite, s16 anim, s16 frame);
/**
 *  \brief
 *      Set current sprite animation.
 *
 *  \param sprite
 *      Sprite to set animation for
 *  \param anim
 *      animation index to set.
 */
void SPR_setAnim(Sprite* sprite, s16 anim);
/**
 *  \brief
 *      Set current sprite frame.
 *
 *  \param sprite
 *      Sprite to set frame for
 *  \param frame
 *      frame index to set.
 */
void SPR_setFrame(Sprite* sprite, s16 frame);
/**
 *  \brief
 *      Pass to the next sprite frame.
 *
 *  \param sprite
 *      Sprite to pass to next frame for
 */
void SPR_nextFrame(Sprite* sprite);
/**
 *  \brief
 *      Enable/disable auto animation for the current animation (default is on).
 *
 *  \param sprite
 *      Sprite we want to enable/disable auto animation.
 *  \param value
 *      TRUE to enable auto animation (default), FALSE otherwise
 *
 *  \see #SPR_getAutoAnimation(Sprite*)
 */
void SPR_setAutoAnimation(Sprite* sprite, bool value);
/**
 *  \brief
 *      Return TRUE if auto animation is enabled, FALSE otherwise.
 *
 *  \see #SPR_setAutoAnimation(Sprite*, bool)
 */
bool SPR_getAutoAnimation(Sprite* sprite);
/**
 *  \brief
 *      Enable/disable animation loop (default is on).<br>
 *      When disable the sprite will stay on the last animation frame when animation ended instead of restarting it.
 *
 *  \see SPR_FLAG_DISABLE_ANIMATION_LOOP
 *  \see #SPR_isAnimationDone(Sprite*)
 */
void SPR_setAnimationLoop(Sprite* sprite, bool value);
/**
 *  \brief
 *      Returns TRUE if the sprite reached the end of the current animation.<br>
 *      When auto animation is enabled (see SPR_setAutoAnimation(..)) the function returns TRUE only when we reached
 *      the last *tick* of the last animation frame.<br>
 *      When auto animation is disabled the function returns TRUE as soon we are on last animation frame.
 *
 *  \see #SPR_setAutoAnimation(Sprite*, bool)
 */
bool SPR_isAnimationDone(Sprite* sprite);
/**
 *  \brief
 *      Set the VRAM tile position reserved for this sprite.
 *
 *  \param sprite
 *      Sprite to set the VRAM tile position for
 *  \param value
 *      the tile position in VRAM where we will upload the sprite tiles data.<br>
 *      Use <b>-1</b> for auto allocation.<br>
 *  \return FALSE if auto allocation failed (can happen only if sprite is currently active), TRUE otherwise
 */
bool SPR_setVRAMTileIndex(Sprite* sprite, s16 value);
/**
 *  \brief
 *      Enable/disable the automatic upload of sprite tiles data into VRAM.
 *
 *  \param sprite
 *      Sprite we want to enable/disable auto tile upload for
 *  \param value
 *      TRUE to enable the automatic upload of sprite tiles data into VRAM.<br>
 *      FALSE to disable it (mean you have to handle that on your own).<br>
 */
void SPR_setAutoTileUpload(Sprite* sprite, bool value);
/**
 *  \brief
 *      Set the frame change event callback for this sprite.
 *
 *  \param sprite
 *      Sprite we want to set the frame change callback
 *  \param callback
 *      the callback (function pointer) to call when we just changed the animation frame for this sprite.
 *
 *  \see #FrameChangeCallback
 */
void SPR_setFrameChangeCallback(Sprite* sprite, FrameChangeCallback* callback);

/**
 *  \brief
 *      Return the <i>visibility</i> state for this sprite.<br>
 *      WARNING: this is different from SPR_isVisible(..) method, possible value are:<br>
 *      SpriteVisibility.VISIBLE            = sprite is visible<br>
 *      SpriteVisibility.HIDDEN             = sprite is not visible<br>
 *      SpriteVisibility.AUTO_VISIBILITY    = visibility is automatically computed (whole meta sprite)<br>
 *
 *  \see SPR_isVisible(...)
 *  \see SPR_setVisibility(...)
 */
SpriteVisibility SPR_getVisibility(Sprite* sprite);
/**
 *  \brief
 *      Return the visible state for this sprite (meaningful only if AUTO visibility is enabled, see #SPR_setVisibility(..) method).
 *
 *  \param sprite
 *      Sprite to return <i>visible</i> state
 *  \param recompute
 *      Force visibility computation.<br>
 *      Only required if SPR_update() wasn't called since last sprite position change (note that can force the frame update processing).
 *
 *  \see SPR_setVisibility(...)
 */
bool SPR_isVisible(Sprite* sprite, bool recompute);
/**
 *  \brief
 *      Set the <i>visibility</i> state for this sprite.
 *
 *  \param sprite
 *      Sprite to set the <i>visibility</i> information
 *  \param value
 *      Visibility value to set (VISIBLE, HIDDEN or AUTO_VISIBILITY)
 *
 *      Hidden sprites don't consume any hardware sprite nor scanline sprite budget:<br>
 *      the VDP is limited to a maximum of 20 sprites or 320 pixels of sprite per scanline (16 sprites/256 px in H32 mode).
 *
 *  \see SPR_getVisibility(...)
 *  \see SPR_isVisible(...)
 */
void SPR_setVisibility(Sprite* sprite, SpriteVisibility value);

/**
 *  \brief
 *      Clear all displayed sprites (without releasing their resources).<br>
 *      Sprites can be displayed again just by calling SPR_update().
 */
void SPR_clear(void);
/**
 *  \brief
 *      Update and display the active list of sprite.
 *
 *  This actually updates all internal active sprites states and prepare the sprite list
 *  cache to send it to the hardware (VDP) at Vint.
 *
 *  \see #SPR_addSprite(..)
 */
void SPR_update(void);

/**
 *  \brief
 *      Log the profil informations (when enabled) in the KMod message window.
 */
void SPR_logProfil(void);
/**
 *  \brief
 *      Log the sprites informations (when enabled) in the KMod message window.
 */
void SPR_logSprites(void);


#endif // _SPRITE_ENG_FAST_H_

#endif // FAST_SPRITE_ENGINE
