package com.soloironman;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.events.ItemContainerChanged;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Listens for BANK and EQUIPMENT container changes and caches item counts.
 *
 * <p><b>Performance contract:</b> never scans on every game tick.
 * Only re-scans when the bank {@link ItemContainer} actually changes.
 *
 * <p><b>Skill coverage:</b>
 * <ul>
 *   <li>Herblore  — 10 potion types: Strength/Super Attack/Prayer/Super Energy/Super Strength/
 *                   Super Restore/Super Defence/Antifire/Ranging Potion/Saradomin Brew</li>
 *   <li>Cooking   — Raw fish (Trout → Anglerfish/Dark Crab) + Jug of Wine</li>
 *   <li>Fletching — Broad arrows + broad bolts + standard arrows (7 tiers) +
 *                   gem bolt tipping (6 types) + logs → longbow (u) (7 tiers)</li>
 *   <li>Crafting  — Dragonhide bodies + battlestaves + gem cutting + glass blowing +
 *                   Superglass Make potential (Giant Seaweed + sand) + flax → bowstrings</li>
 *   <li>Construction — Plank tier breakdown (regular / oak / teak / mahogany)</li>
 *   <li>Smithing  — Steel → cannonballs + Mithril/Adamant/Rune bars</li>
 *   <li>Farming   — 9 herb seeds with estimated run XP</li>
 *   <li>Prayer    — Dragon/wyrm/hydra/superior dragon bones + ensouled heads</li>
 *   <li>Sailing   — Shipyard upgrade materials (Sloop / Caravel / Galleon)</li>
 * </ul>
 */
@Slf4j
@Singleton
public class BankScanner
{
    // ── Herblore XP constants ─────────────────────────────────────────────────
    private static final double HERB_XP_STRENGTH_POTION  =  50.0;   // lvl 12  Tarromin + Limpwurt Root
    private static final double HERB_XP_SUPER_ATTACK     = 100.0;   // lvl 45  Irit + Eye of Newt
    private static final double HERB_XP_SUPER_ENERGY     = 117.5;   // lvl 52  Avantoe + Mort Myre Fungus
    private static final double HERB_XP_SUPER_STRENGTH   = 125.0;   // lvl 55  Kwuarm + Limpwurt Root
    private static final double HERB_XP_PRAYER_POTION    =  87.5;   // lvl 38  Ranarr + Snape Grass
    private static final double HERB_XP_SUPER_RESTORE    = 142.5;   // lvl 63  Snapdragon + Red Spider Eggs
    private static final double HERB_XP_SUPER_DEFENCE    = 150.0;   // lvl 66  Cadantine + White Berries
    private static final double HERB_XP_ANTIFIRE         = 157.5;   // lvl 69  Lantadyme + Dragon Scale Dust
    private static final double HERB_XP_RANGING_POTION   = 162.5;   // lvl 72  Dwarf Weed + Wine of Zamorak
    private static final double HERB_XP_SARADOMIN_BREW   = 180.0;   // lvl 81  Toadflax + Crushed Nest

    // ── Cooking XP constants ──────────────────────────────────────────────────
    private static final int COOK_XP_TROUT      =  70;
    private static final int COOK_XP_SALMON     =  90;
    private static final int COOK_XP_TUNA       = 100;
    private static final int COOK_XP_BASS       = 130;
    private static final int COOK_XP_LOBSTER    = 120;
    private static final int COOK_XP_SWORDFISH  = 140;
    private static final int COOK_XP_MONKFISH   = 150;
    private static final int COOK_XP_KARAMBWAN  = 190;
    private static final int COOK_XP_WINE       = 200;   // Jug of Wine: Grapes + Jug of Water, lvl 35 (XP granted on ferment)
    private static final int COOK_XP_SHARK      = 210;
    private static final int COOK_XP_ANGLERFISH = 230;
    private static final int COOK_XP_DARK_CRAB  = 215;

    // ── Fletching XP constants ────────────────────────────────────────────────
    private static final int FLETCH_XP_BROAD_ARROW = 15;   // per arrow
    private static final int FLETCH_XP_BROAD_BOLT  = 3;    // per bolt
    private static final int BROAD_ARROWS_PER_ACTION = 15;

    // Standard arrows: arrowhead + arrow shaft + feather = 1 arrow
    // XP values per arrow from the OSRS wiki
    private static final double FLETCH_XP_BRONZE_ARROW  =  1.3;   // lvl  1
    private static final double FLETCH_XP_IRON_ARROW    =  2.5;   // lvl 15
    private static final double FLETCH_XP_STEEL_ARROW   =  5.0;   // lvl 30
    private static final double FLETCH_XP_MITHRIL_ARROW =  7.5;   // lvl 45
    private static final double FLETCH_XP_ADAMANT_ARROW = 10.0;   // lvl 60
    private static final double FLETCH_XP_RUNE_ARROW    = 12.5;   // lvl 75
    private static final double FLETCH_XP_DRAGON_ARROW  = 15.0;   // lvl 90

    // Gem bolt tipping: gem tips applied to base bolts
    // XP values per bolt tip applied (from OSRS wiki Fletching training page)
    // Base bolts: Mithril (9142), Adamant (9143), Runite (9144)
    private static final double FLETCH_XP_SAPPHIRE_BOLT     =  4.0;   // lvl 56  Sapphire tips  + Mithril bolts
    private static final double FLETCH_XP_EMERALD_BOLT      =  5.5;   // lvl 58  Emerald tips   + Mithril bolts
    private static final double FLETCH_XP_RUBY_BOLT         =  6.3;   // lvl 63  Ruby tips      + Adamant bolts
    private static final double FLETCH_XP_DIAMOND_BOLT      =  7.0;   // lvl 65  Diamond tips   + Adamant bolts
    private static final double FLETCH_XP_DRAGONSTONE_BOLT  =  8.2;   // lvl 71  Dragonstone tips + Runite bolts
    private static final double FLETCH_XP_ONYX_BOLT         =  9.4;   // lvl 73  Onyx tips      + Runite bolts

    // Longbow (u) XP per log — standard method, no knife requirement beyond level
    private static final double FLETCH_XP_NORMAL_LONGBOW  =  10.0;   // lvl  5  logs
    private static final double FLETCH_XP_OAK_LONGBOW     =  25.0;   // lvl 20  oak
    private static final double FLETCH_XP_WILLOW_LONGBOW  =  41.5;   // lvl 35  willow
    private static final double FLETCH_XP_MAPLE_LONGBOW   =  58.3;   // lvl 55  maple
    private static final double FLETCH_XP_YEW_LONGBOW     =  75.0;   // lvl 70  yew
    private static final double FLETCH_XP_MAGIC_LONGBOW   =  91.5;   // lvl 85  magic
    private static final double FLETCH_XP_REDWOOD_LONGBOW = 106.0;   // lvl 90  redwood

    // ── Crafting (gem cutting) XP per gem ─────────────────────────────────────
    private static final double CRAFT_XP_CUT_SAPPHIRE    =  50.0;
    private static final double CRAFT_XP_CUT_EMERALD     =  67.5;
    private static final double CRAFT_XP_CUT_RUBY        =  85.0;
    private static final double CRAFT_XP_CUT_DIAMOND     = 107.5;
    private static final double CRAFT_XP_CUT_DRAGONSTONE = 137.5;
    private static final double CRAFT_XP_CUT_ZENYTE      = 200.0;

    // ── Crafting (glass blowing) — Unpowered Orb = best XP per glass ─────────
    private static final double CRAFT_XP_GLASS_ORB = 52.5;

    // ── Crafting (dragonhide bodies) — 3 tanned leathers per body ───────────────
    // Source: wiki.net/Crafting  XP = per body (3 leathers); level gates apply.
    // Tanned leathers drop from dragons on Slayer tasks — the Ironman's primary
    // high-XP Crafting source alongside battlestaves.
    private static final int    CRAFT_XP_GREEN_DHIDE_BODY  = 186;   // lvl 63  (62 xp/leather)
    private static final int    CRAFT_XP_BLUE_DHIDE_BODY   = 210;   // lvl 71  (70 xp/leather)
    private static final int    CRAFT_XP_RED_DHIDE_BODY    = 234;   // lvl 77  (78 xp/leather)
    private static final int    CRAFT_XP_BLACK_DHIDE_BODY  = 258;   // lvl 84  (86 xp/leather)

    // ── Crafting (battlestaves) — orb + battlestaff, ordered by level ─────────
    // Source: wiki.net/Battlestaff  Crafting level 54/58/62/66 respectively
    private static final double CRAFT_XP_WATER_BATTLESTAFF = 100.0;   // lvl 54
    private static final double CRAFT_XP_EARTH_BATTLESTAFF = 112.5;   // lvl 58
    private static final double CRAFT_XP_FIRE_BATTLESTAFF  = 125.0;   // lvl 62
    private static final double CRAFT_XP_AIR_BATTLESTAFF   = 137.5;   // lvl 66

    // ── Prayer XP at the Chaos Altar (3.5× base XP) ─────────────────────────��
    // Source: OSRS wiki — Chaos Altar in level 38 Wilderness gives 3.5× base.
    // 50% chance bone is NOT consumed (effective XP per trip scales with survival).
    // These are the full 3.5× values (what you get per bone used on the altar).
    private static final double PRAY_XP_BABY_DRAGON_BONES    = 105.0;   // base 30  × 3.5
    private static final double PRAY_XP_WYRM_BONES           = 175.0;   // base 50  × 3.5
    private static final double PRAY_XP_DRAGON_BONES         = 252.0;   // base 72  × 3.5
    private static final double PRAY_XP_LAVA_DRAGON_BONES    = 297.5;   // base 85  × 3.5
    private static final double PRAY_XP_DRAKE_BONES          = 280.0;   // base 80  × 3.5
    private static final double PRAY_XP_WYVERN_BONES         = 252.0;   // base 72  × 3.5
    private static final double PRAY_XP_HYDRA_BONES          = 385.0;   // base 110 × 3.5
    private static final double PRAY_XP_SUPERIOR_DRAGON_BONES = 525.0;  // base 150 × 3.5

    // ── Construction XP per plank (efficient building method) ────────────────
    // Normal plank: oak dungeon wall (2 planks, 58 xp) ≈ 29 xp/plank
    // Oak plank:    oak dungeon door (4 planks, 240 xp) = 60 xp/plank
    // Teak plank:   teak garden bench (8 planks, 720 xp) = 90 xp/plank
    // Mahogany plank: mahogany bookcase (3 planks, 420 xp) = 140 xp/plank
    private static final double CONST_XP_PLANK          =  29.0;
    private static final double CONST_XP_OAK_PLANK      =  60.0;
    private static final double CONST_XP_TEAK_PLANK     =  90.0;
    private static final double CONST_XP_MAHOGANY_PLANK = 140.0;

    // ── Farming — estimated XP per seed (planting + avg 5 herb picks) ─────────
    // Formula: planting_xp + (5 × harvest_xp_per_herb)
    // Used as a rough "banked run XP" indicator; actual yield varies 3–8 herbs.
    private static final double FARM_XP_TOADFLAX_SEED   = 204.0;  // 34  + 5×34
    private static final double FARM_XP_AVANTOE_SEED    = 285.0;  // 47.5+ 5×47.5
    private static final double FARM_XP_KWUARM_SEED     = 324.0;  // 54  + 5×54
    private static final double FARM_XP_RANARR_SEED     = 180.0;  // 27  + 5×30.5
    private static final double FARM_XP_SNAPDRAGON_SEED = 323.0;  // 35.5+ 5×57.5
    private static final double FARM_XP_CADANTINE_SEED  = 387.0;  // 64.5+ 5×64.5
    private static final double FARM_XP_LANTADYME_SEED  = 411.0;  // 68.5+ 5×68.5
    private static final double FARM_XP_DWARF_WEED_SEED = 435.0;  // 72.5+ 5×72.5
    private static final double FARM_XP_TORSTOL_SEED    = 459.0;  // 76.5+ 5×76.5

    // ── Numeric item IDs ──────────────────────────────────────────────────────
    // Using numeric IDs avoids dependency on ItemID constants that may not exist
    // in every RuneLite version, consistent with the existing codebase pattern.

    // Herblore — herbs (clean + grimy)
    // All use ItemID.* constants to guarantee correct IDs against RuneLite 1.12.22.
    // Previous numeric constants (211/213, 207/209, 219/221, 3049/3000) were WRONG —
    // they pointed to neighbouring herbs or unrelated items in the OSRS item table.
    private static final int ITEM_SNAPDRAGON        = ItemID.SNAPDRAGON;        // 3000
    private static final int ITEM_GRIMY_SNAPDRAGON  = ItemID.GRIMY_SNAPDRAGON;  // 3051
    private static final int ITEM_AVANTOE           = ItemID.AVANTOE;           // 261
    private static final int ITEM_GRIMY_AVANTOE     = ItemID.GRIMY_AVANTOE;     // 211
    private static final int ITEM_TARROMIN          = ItemID.TARROMIN;          // 253
    private static final int ITEM_GRIMY_TARROMIN    = ItemID.GRIMY_TARROMIN;    // 203
    private static final int ITEM_KWUARM            = ItemID.KWUARM;            // 263
    private static final int ITEM_GRIMY_KWUARM      = ItemID.GRIMY_KWUARM;      // 213
    private static final int ITEM_TOADFLAX          = ItemID.TOADFLAX;          // 2998
    private static final int ITEM_GRIMY_TOADFLAX    = ItemID.GRIMY_TOADFLAX;    // 3049

    // Herblore — extended herbs (Cadantine / Lantadyme / Dwarf Weed)
    // Added for Super Defence (66), Antifire (69), and Ranging Potion (72) tracking.
    private static final int ITEM_CADANTINE         = ItemID.CADANTINE;        // 265
    private static final int ITEM_GRIMY_CADANTINE   = ItemID.GRIMY_CADANTINE;  // 215
    private static final int ITEM_LANTADYME         = ItemID.LANTADYME;        // 2481
    private static final int ITEM_GRIMY_LANTADYME   = ItemID.GRIMY_LANTADYME;  // 2485
    private static final int ITEM_DWARF_WEED        = ItemID.DWARF_WEED;       // 267
    private static final int ITEM_GRIMY_DWARF_WEED  = ItemID.GRIMY_DWARF_WEED; // 217

    // Herblore — secondaries
    private static final int ITEM_RED_SPIDERS_EGGS  = 223;   // ItemID.RED_SPIDERS_EGGS = 223 ✓
    private static final int ITEM_MORT_MYRE_FUNGUS  = ItemID.MORT_MYRE_FUNGUS;  // 2970 (was 464 — WRONG)
    private static final int ITEM_LIMPWURT_ROOT     = 225;   // ItemID.LIMPWURT_ROOT = 225 ✓
    private static final int ITEM_CRUSHED_NEST      = 6693;  // ItemID.CRUSHED_NEST = 6693 ✓
    private static final int ITEM_WHITE_BERRIES     = ItemID.WHITE_BERRIES;     // 239
    private static final int ITEM_DRAGON_SCALE_DUST = ItemID.DRAGON_SCALE_DUST; // 241
    private static final int ITEM_WINE_OF_ZAMORAK   = ItemID.WINE_OF_ZAMORAK;   // 245

    // Fletching — broad arrows/bolts (Slayer shop)
    // Broad arrows:  ItemID.BROAD_ARROWHEADS (11874) + ARROW_SHAFT (52) + FEATHER (314) → broad arrow
    // Broad bolts:   ItemID.UNFINISHED_BROAD_BOLTS (11876) + FEATHER (314) → broad bolt (11875)
    // Previous constants 11875 (=BROAD_BOLTS finished) and 9375 (=BRONZE_BOLTS_UNF) were WRONG.

    // Fletching — standard arrow arrowheads (tip + shaft + feather → arrow)
    // OSRS item IDs for arrowheads/tips (NOT finished arrows).
    // Verified from RuneLite ItemID: BRONZE_ARROWTIPS=39, IRON=40, STEEL=41,
    // MITHRIL=42, ADAMANT=43, RUNE=44, DRAGON=11237.
    // WARNING: finished arrows (bronze=882, iron=884…) are DIFFERENT items —
    // arrowheads are the raw tips smithed or purchased before fletching.
    private static final int ITEM_BRONZE_ARROWHEADS  = ItemID.BRONZE_ARROWTIPS;   // 39
    private static final int ITEM_IRON_ARROWHEADS    = ItemID.IRON_ARROWTIPS;     // 40
    private static final int ITEM_STEEL_ARROWHEADS   = ItemID.STEEL_ARROWTIPS;    // 41
    private static final int ITEM_MITHRIL_ARROWHEADS = ItemID.MITHRIL_ARROWTIPS;  // 42
    private static final int ITEM_ADAMANT_ARROWHEADS = ItemID.ADAMANT_ARROWTIPS;  // 43
    private static final int ITEM_RUNE_ARROWHEADS    = ItemID.RUNE_ARROWTIPS;     // 44
    private static final int ITEM_DRAGON_ARROWHEADS  = ItemID.DRAGON_ARROWTIPS;   // 11237

    // Fletching — logs (for longbow (u) projection)
    private static final int ITEM_LOGS         = 1511;
    private static final int ITEM_OAK_LOGS     = 1521;
    private static final int ITEM_WILLOW_LOGS  = 1519;
    private static final int ITEM_MAPLE_LOGS   = 1517;
    private static final int ITEM_YEW_LOGS     = 1515;
    private static final int ITEM_MAGIC_LOGS   = 1513;
    private static final int ITEM_REDWOOD_LOGS = 19669;

    // Cooking
    private static final int ITEM_RAW_ANGLERFISH = 13439;
    private static final int ITEM_RAW_DARK_CRAB  = 11936;

    // Cooking — Jug of Wine (Grapes + Jug of Water → ferments in inventory, Cooking 35, 200 XP)
    // Craftable = min(grapes, jugs); XP is granted when the jug finishes fermenting.
    // Verified: ItemID.GRAPES=1987, ItemID.JUG_OF_WATER=1937
    private static final int ITEM_GRAPES       = ItemID.GRAPES;        // 1987
    private static final int ITEM_JUG_OF_WATER = ItemID.JUG_OF_WATER;  // 1937

    // Crafting — Giant Seaweed (Fossil Island Farming, Superglass Make spell)
    // 1 Giant Seaweed + 3 Buckets of Sand → 18 Molten Glass via Superglass Make (Magic 77)
    // Raw materials only — the glass blowing XP comes from the Crafting section.
    // Verified: ItemID.GIANT_SEAWEED=21504, ItemID.BUCKET_OF_SAND=1783
    private static final int ITEM_GIANT_SEAWEED  = ItemID.GIANT_SEAWEED;   // 21504
    private static final int ITEM_BUCKET_OF_SAND = ItemID.BUCKET_OF_SAND;  // 1783

    // Crafting — uncut gems
    private static final int ITEM_UNCUT_SAPPHIRE    = 1623;
    private static final int ITEM_UNCUT_EMERALD     = 1621;
    private static final int ITEM_UNCUT_RUBY        = 1619;
    private static final int ITEM_UNCUT_DIAMOND     = 1617;
    private static final int ITEM_UNCUT_DRAGONSTONE = 1631;
    private static final int ITEM_UNCUT_ZENYTE      = 19529;

    // Crafting — glass
    private static final int ITEM_MOLTEN_GLASS = 1775;

    // Crafting — tanned dragonhide leathers (body = 3 leathers each)
    private static final int ITEM_GREEN_DRAGON_LEATHER = 1745;   // ItemID.GREEN_DRAGON_LEATHER
    private static final int ITEM_BLUE_DRAGON_LEATHER  = 2505;   // ItemID.BLUE_DRAGON_LEATHER
    private static final int ITEM_RED_DRAGON_LEATHER   = 2507;   // ItemID.RED_DRAGON_LEATHER
    private static final int ITEM_BLACK_DRAGON_LEATHER = 2509;   // ItemID.BLACK_DRAGON_LEATHER

    // Crafting — flax → bowstring (Crafting 10, 15 XP each at a spinning wheel)
    private static final int ITEM_FLAX = 1779;   // ItemID.FLAX

    // Crafting — battlestaves
    private static final int ITEM_BATTLESTAFF = 1391;   // ItemID.BATTLESTAFF
    private static final int ITEM_WATER_ORB   = 571;    // ItemID.WATER_ORB
    private static final int ITEM_EARTH_ORB   = 575;    // ItemID.EARTH_ORB
    private static final int ITEM_FIRE_ORB    = 569;    // ItemID.FIRE_ORB
    private static final int ITEM_AIR_ORB     = 573;    // ItemID.AIR_ORB

    // Smithing — metal bars
    // Steel bars → cannonballs (primary Ironman use, Slayer resource)
    // Mithril/Adamant/Rune → equipment → high alch or Giant's Foundry
    private static final int ITEM_STEEL_BAR   = 2353;   // ItemID.STEEL_BAR
    private static final int ITEM_MITHRIL_BAR = 2359;   // ItemID.MITHRIL_BAR
    private static final int ITEM_ADAMANT_BAR = 2361;   // ItemID.ADAMANT_BAR
    private static final int ITEM_RUNE_BAR    = 2363;   // ItemID.RUNE_BAR

    // Construction — planks
    private static final int ITEM_PLANK          = 960;
    private static final int ITEM_OAK_PLANK      = 8778;
    private static final int ITEM_TEAK_PLANK     = 8780;
    private static final int ITEM_MAHOGANY_PLANK = 8782;

    // Sailing — shipyard materials
    private static final int ITEM_BOLT_OF_CLOTH = 8790;   // sail cloth (also Construction)
    private static final int ITEM_MITHRIL_NAILS = 4822;   // OSRS wiki ID — verify in-game

    // Ship upgrade material thresholds (Port Sarim Shipwright).
    // These are beta-era estimates — update if Jagex adjusts upgrade costs at launch.
    static final int SLOOP_PLANKS_NEED   =  50;   // Regular planks
    static final int SLOOP_NAILS_NEED    = 100;   // Steel nails
    static final int SLOOP_CLOTH_NEED    =   2;   // Bolt of cloth
    static final int CARAVEL_PLANKS_NEED = 100;   // Oak planks
    static final int CARAVEL_NAILS_NEED  = 200;   // Steel nails
    static final int CARAVEL_CLOTH_NEED  =   5;   // Bolt of cloth
    static final int GALLEON_PLANKS_NEED = 200;   // Teak planks
    static final int GALLEON_NAILS_NEED  = 500;   // Mithril nails
    static final int GALLEON_CLOTH_NEED  =  10;   // Bolt of cloth

    // Farming — herb seeds
    // All use ItemID.* constants. Previous values for TOADFLAX (5298→5296),
    // AVANTOE (5299→5298), and KWUARM (5297→5299) seeds were off — the seed IDs
    // are NOT simply herb_id+3; they follow their own sequential series.
    private static final int ITEM_RANARR_SEED     = ItemID.RANARR_SEED;     // 5295
    private static final int ITEM_TOADFLAX_SEED   = ItemID.TOADFLAX_SEED;   // 5296
    private static final int ITEM_AVANTOE_SEED    = ItemID.AVANTOE_SEED;    // 5298
    private static final int ITEM_KWUARM_SEED     = ItemID.KWUARM_SEED;     // 5299
    private static final int ITEM_SNAPDRAGON_SEED = ItemID.SNAPDRAGON_SEED; // 5300
    private static final int ITEM_CADANTINE_SEED  = ItemID.CADANTINE_SEED;  // 5301
    private static final int ITEM_LANTADYME_SEED  = ItemID.LANTADYME_SEED;  // 5302
    private static final int ITEM_DWARF_WEED_SEED = ItemID.DWARF_WEED_SEED; // 5303
    private static final int ITEM_TORSTOL_SEED    = ItemID.TORSTOL_SEED;    // 5304

    // Prayer — bones offered at the Chaos Altar (level 38 Wilderness, 3.5× base XP)
    // 50% chance the bone is NOT consumed per use — effective XP per trip is higher.
    // Ordered highest XP first (superior dragon → baby dragon).
    // IDs verified via javap against RuneLite ItemID in the project JAR.
    private static final int ITEM_SUPERIOR_DRAGON_BONES  = ItemID.SUPERIOR_DRAGON_BONES;  // 22124
    private static final int ITEM_HYDRA_BONES            = ItemID.HYDRA_BONES;            // 22786
    private static final int ITEM_LAVA_DRAGON_BONES      = ItemID.LAVA_DRAGON_BONES;      // 11943
    private static final int ITEM_DRAKE_BONES            = ItemID.DRAKE_BONES;            // 22783
    private static final int ITEM_WYVERN_BONES           = ItemID.WYVERN_BONES;           // 6812
    private static final int ITEM_DRAGON_BONES           = ItemID.DRAGON_BONES;           // 536
    private static final int ITEM_WYRM_BONES             = ItemID.WYRM_BONES;             // 22780
    private static final int ITEM_BABYDRAGON_BONES       = ItemID.BABYDRAGON_BONES;       // 534

    // Prayer — ensouled heads for Arceuus reanimation (NOT Chaos Altar)
    // Reanimate with Arceuus spellbook at the Dark Altar, then kill the creature for XP.
    // Key heads tracked (highest XP first by type):
    private static final int ITEM_ENSOULED_ABYSSAL_HEAD   = ItemID.ENSOULED_ABYSSAL_HEAD;   // 13507
    private static final int ITEM_ENSOULED_DRAGON_HEAD    = ItemID.ENSOULED_DRAGON_HEAD;    // 13510
    private static final int ITEM_ENSOULED_DAGANNOTH_HEAD = ItemID.ENSOULED_DAGANNOTH_HEAD; // 13492
    private static final int ITEM_ENSOULED_DEMON_HEAD     = ItemID.ENSOULED_DEMON_HEAD;     // 13501

    // Prayer XP for ensouled heads (XP earned when the reanimated creature is killed)
    private static final int PRAY_XP_ENSOULED_ABYSSAL    = 1600;  // Arceuus 90
    private static final int PRAY_XP_ENSOULED_DRAGON     = 1560;  // Magic 93
    private static final int PRAY_XP_ENSOULED_DAGANNOTH  = 1560;  // Magic 72
    private static final int PRAY_XP_ENSOULED_DEMON      = 1560;  // Magic 84

    // ── Caches ────────────────────────────────────────────────────────────────
    private final Map<Integer, Integer> bankCache      = new HashMap<>();
    private final Map<Integer, Integer> equipmentCache = new HashMap<>();

    /** {@code true} after at least one bank scan — UI waits for this before rendering.
     *  Volatile so the EDT always sees the latest value written by the game thread. */
    @Getter
    private volatile boolean hasData = false;

    @Inject
    public BankScanner() {}

    // ─── Event handler ────────────────────────────────────────────────────────

    /**
     * Directly scan a bank {@link ItemContainer} obtained via
     * {@code client.getItemContainer(InventoryID.BANK)}.
     * Used by the game-tick poller as a reliable alternative to
     * {@link #onBankChanged} which depends on event container IDs matching.
     */
    public void scanBank(ItemContainer bank)
    {
        if (bank == null) return;
        bankCache.clear();
        for (Item item : bank.getItems())
        {
            if (item.getId() > 0)
                bankCache.merge(item.getId(), item.getQuantity(), Integer::sum);
        }
        hasData = true;
    }

    /**
     * Called from {@link SoloIronmanPlugin#onItemContainerChanged}.
     * Rebuilds the bank or equipment cache only when those containers fire.
     */
    public void onBankChanged(ItemContainerChanged event)
    {
        int id = event.getContainerId();

        if (id == InventoryID.BANK.getId())
        {
            ItemContainer bank = event.getItemContainer();
            if (bank == null) return;

            bankCache.clear();
            for (Item item : bank.getItems())
            {
                if (item.getId() > 0)
                    bankCache.merge(item.getId(), item.getQuantity(), Integer::sum);
            }
            hasData = true;
            log.debug("Bank cache refreshed: {} unique item types", bankCache.size());
        }
        else if (id == InventoryID.EQUIPMENT.getId())
        {
            ItemContainer equip = event.getItemContainer();
            if (equip == null) return;

            equipmentCache.clear();
            for (Item item : equip.getItems())
            {
                if (item.getId() > 0)
                    equipmentCache.merge(item.getId(), item.getQuantity(), Integer::sum);
            }
            log.debug("Equipment cache refreshed: {} slots", equipmentCache.size());
        }
    }

    /** Clear all caches on logout. */
    public void reset()
    {
        bankCache.clear();
        equipmentCache.clear();
        hasData = false;
    }

    // ─── Item queries ─────────────────────────────────────────────────────────

    public int  getCount(int itemId)               { return bankCache.getOrDefault(itemId, 0); }
    public boolean hasItem(int itemId)             { return getCount(itemId) > 0; }
    public boolean hasEquipped(int itemId)         { return equipmentCache.getOrDefault(itemId, 0) > 0; }
    public boolean hasInBankOrEquipped(int itemId) { return hasItem(itemId) || hasEquipped(itemId); }

    // ─── Herblore helpers ─────────────────────────────────────────────────────

    public int getIritCount()        { return getCount(ItemID.IRIT_LEAF)   + getCount(ItemID.GRIMY_IRIT_LEAF);  }
    public int getRanarCount()       { return getCount(ItemID.RANARR_WEED) + getCount(ItemID.GRIMY_RANARR_WEED); }
    public int getSnapdragonCount()  { return getCount(ITEM_SNAPDRAGON)    + getCount(ITEM_GRIMY_SNAPDRAGON);  }
    public int getAvantoeCount()     { return getCount(ITEM_AVANTOE)       + getCount(ITEM_GRIMY_AVANTOE);     }
    public int getTarrominCount()    { return getCount(ITEM_TARROMIN)      + getCount(ITEM_GRIMY_TARROMIN);    }
    public int getKwuarmCount()      { return getCount(ITEM_KWUARM)        + getCount(ITEM_GRIMY_KWUARM);      }
    public int getToadflaxCount()    { return getCount(ITEM_TOADFLAX)      + getCount(ITEM_GRIMY_TOADFLAX);    }
    public int getCadantineCount()   { return getCount(ITEM_CADANTINE)     + getCount(ITEM_GRIMY_CADANTINE);   }
    public int getLantadymeCount()   { return getCount(ITEM_LANTADYME)     + getCount(ITEM_GRIMY_LANTADYME);   }
    public int getDwarfWeedCount()   { return getCount(ITEM_DWARF_WEED)    + getCount(ITEM_GRIMY_DWARF_WEED);  }

    public boolean hasMissingEyeOfNewt()
    {
        return getIritCount() > 0 && getCount(ItemID.EYE_OF_NEWT) == 0;
    }

    /**
     * Returns {@code true} when the player has Snapdragon in the bank but no
     * Red Spider Eggs — blocking Super Restore production.
     */
    public boolean hasMissingRedSpiderEggs()
    {
        return getSnapdragonCount() > 0 && getCount(ITEM_RED_SPIDERS_EGGS) == 0;
    }

    /**
     * Returns {@code true} when the player has Avantoe in the bank but no
     * Mort Myre Fungus — blocking Super Energy production.
     */
    public boolean hasMissingMortMyreFungus()
    {
        return getAvantoeCount() > 0 && getCount(ITEM_MORT_MYRE_FUNGUS) == 0;
    }

    public int getSnapeGrassShortfall()
    {
        return Math.max(0, getRanarCount() - getCount(ItemID.SNAPE_GRASS));
    }

    public int getMortMyreFungusShortfall()
    {
        return Math.max(0, getAvantoeCount() - getCount(ITEM_MORT_MYRE_FUNGUS));
    }

    /**
     * Returns how many Feathers the player needs to fletch all their Broad Arrowheads.
     * A positive value means: "buy this many feathers to clear the backlog".
     * Returns 0 when feathers ≥ arrowheads (or no arrowheads banked).
     */
    public int getFeatherShortfall()
    {
        int arrowheads = getCount(ItemID.BROAD_ARROWHEADS);
        if (arrowheads == 0) return 0;
        return Math.max(0, arrowheads - getCount(ItemID.FEATHER));
    }

    // ─── Breakdown methods ────────────────────────────────────────────────────

    /**
     * Herblore: craftable potions from banked herbs + secondaries, ordered by XP value.
     *
     * <p>Potions tracked (with correct Herblore recipes):
     * <ul>
     *   <li>Strength Potion    (lvl 12)  Tarromin + Limpwurt Root           50.0 XP</li>
     *   <li>Super Attack       (lvl 45)  Irit Leaf + Eye of Newt            100.0 XP</li>
     *   <li>Super Energy       (lvl 52)  Avantoe + Mort Myre Fungus         117.5 XP</li>
     *   <li>Super Strength     (lvl 55)  Kwuarm + Limpwurt Root             125.0 XP</li>
     *   <li>Prayer Potion      (lvl 38)  Ranarr Weed + Snape Grass           87.5 XP</li>
     *   <li>Super Restore      (lvl 63)  Snapdragon + Red Spider Eggs        142.5 XP</li>
     *   <li>Saradomin Brew     (lvl 81)  Toadflax + Crushed Nest            180.0 XP</li>
     * </ul>
     */
    public List<CraftEntry> getHerbloreBreakdown()
    {
        List<CraftEntry> list = new ArrayList<>();

        // Tarromin + Limpwurt Root — Strength Potion (lvl 12, 50 XP)
        int strength = Math.min(getTarrominCount(), getCount(ITEM_LIMPWURT_ROOT));
        if (strength > 0) list.add(new CraftEntry("Strength Potion", strength, HERB_XP_STRENGTH_POTION));

        // Irit + Eye of Newt — Super Attack (lvl 45, 100 XP)
        int superAttack = Math.min(getIritCount(), getCount(ItemID.EYE_OF_NEWT));
        if (superAttack > 0) list.add(new CraftEntry("Super Attack", superAttack, HERB_XP_SUPER_ATTACK));

        // Avantoe + Mort Myre Fungus — Super Energy (lvl 52, 117.5 XP)
        int superEnergy = Math.min(getAvantoeCount(), getCount(ITEM_MORT_MYRE_FUNGUS));
        if (superEnergy > 0) list.add(new CraftEntry("Super Energy", superEnergy, HERB_XP_SUPER_ENERGY));

        // Kwuarm + Limpwurt Root — Super Strength (lvl 55, 125 XP)
        // Limpwurt is shared with Strength Potion; both compete for the same secondary.
        int superStrength = Math.min(getKwuarmCount(), getCount(ITEM_LIMPWURT_ROOT));
        if (superStrength > 0) list.add(new CraftEntry("Super Strength", superStrength, HERB_XP_SUPER_STRENGTH));

        // Ranarr + Snape Grass — Prayer Potion (lvl 38, 87.5 XP)
        int prayer = Math.min(getRanarCount(), getCount(ItemID.SNAPE_GRASS));
        if (prayer > 0) list.add(new CraftEntry("Prayer Potion", prayer, HERB_XP_PRAYER_POTION));

        // Snapdragon + Red Spider Eggs — Super Restore (lvl 63, 142.5 XP)
        int restore = Math.min(getSnapdragonCount(), getCount(ITEM_RED_SPIDERS_EGGS));
        if (restore > 0) list.add(new CraftEntry("Super Restore", restore, HERB_XP_SUPER_RESTORE));

        // Cadantine + White Berries — Super Defence (lvl 66, 150 XP)
        int superDefence = Math.min(getCadantineCount(), getCount(ITEM_WHITE_BERRIES));
        if (superDefence > 0) list.add(new CraftEntry("Super Defence", superDefence, HERB_XP_SUPER_DEFENCE));

        // Lantadyme + Dragon Scale Dust — Antifire Potion (lvl 69, 157.5 XP)
        int antifire = Math.min(getLantadymeCount(), getCount(ITEM_DRAGON_SCALE_DUST));
        if (antifire > 0) list.add(new CraftEntry("Antifire Potion", antifire, HERB_XP_ANTIFIRE));

        // Dwarf Weed + Wine of Zamorak — Ranging Potion (lvl 72, 162.5 XP)
        int ranging = Math.min(getDwarfWeedCount(), getCount(ITEM_WINE_OF_ZAMORAK));
        if (ranging > 0) list.add(new CraftEntry("Ranging Potion", ranging, HERB_XP_RANGING_POTION));

        // Toadflax + Crushed Nest — Saradomin Brew (lvl 81, 180 XP)
        int brew = Math.min(getToadflaxCount(), getCount(ITEM_CRUSHED_NEST));
        if (brew > 0) list.add(new CraftEntry("Saradomin Brew", brew, HERB_XP_SARADOMIN_BREW));

        return list;
    }

    /**
     * Cooking: raw fish in bank (ordered by XP highest first) plus Jug of Wine.
     *
     * <p>Covers early-game fish (Trout/Salmon/Tuna) through late-game (Anglerfish/Dark Crab).</p>
     *
     * <p>Jug of Wine (Cooking 35, 200 XP): Grapes + Jug of Water → fermenting jug of wine.
     * Craftable count is {@code min(grapes, jug_of_water)}.  XP is granted when the jug
     * finishes fermenting in the inventory, not on the click — but for planning purposes the
     * full 200 XP per grape is counted.</p>
     */
    public List<CraftEntry> getCookingBreakdown()
    {
        List<CraftEntry> list = new ArrayList<>();
        addIf(list, "Anglerfish",  getCount(ITEM_RAW_ANGLERFISH),  COOK_XP_ANGLERFISH);
        addIf(list, "Dark Crab",   getCount(ITEM_RAW_DARK_CRAB),   COOK_XP_DARK_CRAB);
        addIf(list, "Shark",       getCount(ItemID.RAW_SHARK),      COOK_XP_SHARK);
        // Jug of Wine (200 XP) sits between Shark and Karambwan in XP order
        int wines = Math.min(getCount(ITEM_GRAPES), getCount(ITEM_JUG_OF_WATER));
        addIf(list, "Jug of Wine", wines, COOK_XP_WINE);
        addIf(list, "Karambwan",   getCount(ItemID.RAW_KARAMBWAN),  COOK_XP_KARAMBWAN);
        addIf(list, "Monkfish",    getCount(ItemID.RAW_MONKFISH),   COOK_XP_MONKFISH);
        addIf(list, "Bass",        getCount(ItemID.RAW_BASS),       COOK_XP_BASS);
        addIf(list, "Swordfish",   getCount(ItemID.RAW_SWORDFISH),  COOK_XP_SWORDFISH);
        addIf(list, "Lobster",     getCount(ItemID.RAW_LOBSTER),    COOK_XP_LOBSTER);
        addIf(list, "Tuna",        getCount(ItemID.RAW_TUNA),       COOK_XP_TUNA);
        addIf(list, "Salmon",      getCount(ItemID.RAW_SALMON),     COOK_XP_SALMON);
        addIf(list, "Trout",       getCount(ItemID.RAW_TROUT),      COOK_XP_TROUT);
        return list;
    }

    /**
     * Number of Giant Seaweed in the bank.
     * Used to estimate Superglass Make potential: 1 seaweed + 3 Buckets of Sand
     * → 18 Molten Glass via the Superglass Make spell (Magic 77).
     */
    public int getGiantSeaweedCount() { return getCount(ITEM_GIANT_SEAWEED); }

    /**
     * Number of Buckets of Sand in the bank.
     * Shared resource for Superglass Make; also used for Construction (Molten Glass → Orbs).
     */
    public int getBucketOfSandCount() { return getCount(ITEM_BUCKET_OF_SAND); }

    /**
     * Fletching: Broad Arrows and Broad Bolts from the Slayer shop.
     *
     * <p><b>Broad Arrows</b> (Fletching 52, 15 XP each):
     * {@code ItemID.BROAD_ARROWHEADS} + {@code ItemID.ARROW_SHAFT} + {@code ItemID.FEATHER}</p>
     *
     * <p><b>Broad Bolts</b> (Fletching 55, 3 XP each):
     * {@code ItemID.UNFINISHED_BROAD_BOLTS} + {@code ItemID.FEATHER}</p>
     */
    public List<CraftEntry> getFletchingBreakdown()
    {
        List<CraftEntry> list = new ArrayList<>();

        int shafts   = getCount(ItemID.ARROW_SHAFT);
        int feathers = getCount(ItemID.FEATHER);

        // Broad Arrows: arrowheads (11874) + shaft + feather
        int arrowheads = getCount(ItemID.BROAD_ARROWHEADS);   // 11874
        int arrows     = Math.min(arrowheads, Math.min(shafts, feathers));
        if (arrows > 0) list.add(new CraftEntry("Broad Arrow", arrows, FLETCH_XP_BROAD_ARROW));

        // Broad Bolts: unfinished broad bolts (11876) + feather
        int unfBolts = getCount(ItemID.UNFINISHED_BROAD_BOLTS);   // 11876
        int bolts    = Math.min(unfBolts, feathers);
        if (bolts > 0) list.add(new CraftEntry("Broad Bolt", bolts, FLETCH_XP_BROAD_BOLT));

        return list;
    }

    /**
     * Fletching (standard arrows): arrowhead + arrow shaft + feather → arrow.
     *
     * <p>Each entry represents one arrowhead type; the craftable count is
     * {@code min(arrowheads, min(shafts, feathers))} so the display is accurate
     * about what can actually be made in one session.  Arrow shafts and feathers
     * are shared resources — the player decides which tier to prioritise.</p>
     *
     * <p>Results are ordered highest XP-per-arrow first (dragon → bronze),
     * which matches Ironman priority (highest XP tier is always most efficient).</p>
     *
     * <p>Uses {@link LogBowEntry} since the data shape is identical: name, count,
     * XP-per-item, level requirement.</p>
     */
    public List<LogBowEntry> getArrowBreakdown()
    {
        int shafts   = getCount(ItemID.ARROW_SHAFT);
        int feathers = getCount(ItemID.FEATHER);

        if (shafts == 0 || feathers == 0) return new ArrayList<>();

        List<LogBowEntry> list = new ArrayList<>();
        addArrowIf(list, "Dragon Arrows",  ITEM_DRAGON_ARROWHEADS,  shafts, feathers, FLETCH_XP_DRAGON_ARROW,  90);
        addArrowIf(list, "Rune Arrows",    ITEM_RUNE_ARROWHEADS,    shafts, feathers, FLETCH_XP_RUNE_ARROW,    75);
        addArrowIf(list, "Adamant Arrows", ITEM_ADAMANT_ARROWHEADS, shafts, feathers, FLETCH_XP_ADAMANT_ARROW, 60);
        addArrowIf(list, "Mithril Arrows", ITEM_MITHRIL_ARROWHEADS, shafts, feathers, FLETCH_XP_MITHRIL_ARROW, 45);
        addArrowIf(list, "Steel Arrows",   ITEM_STEEL_ARROWHEADS,   shafts, feathers, FLETCH_XP_STEEL_ARROW,   30);
        addArrowIf(list, "Iron Arrows",    ITEM_IRON_ARROWHEADS,    shafts, feathers, FLETCH_XP_IRON_ARROW,    15);
        addArrowIf(list, "Bronze Arrows",  ITEM_BRONZE_ARROWHEADS,  shafts, feathers, FLETCH_XP_BRONZE_ARROW,   1);
        return list;
    }

    private void addArrowIf(List<LogBowEntry> list, String name, int arrowheadId,
                            int shafts, int feathers, double xpEach, int levelReq)
    {
        int arrowheads = getCount(arrowheadId);
        int craftable  = Math.min(arrowheads, Math.min(shafts, feathers));
        if (craftable > 0)
            list.add(new LogBowEntry(name, craftable, xpEach, levelReq));
    }

    /**
     * Fletching (gem bolt tipping): gem tips applied to base bolts for Fletching XP.
     *
     * <p>This is a staple Ironman activity — gem tips come from Slayer/PvM drops or
     * gem cutting, and the resulting enchanted bolts are BIS ranged ammunition.
     * Results are ordered highest XP-per-bolt first (onyx → sapphire).</p>
     *
     * <p>Base bolt pools (mithril/adamant/runite) are shared across multiple tip types;
     * each entry shows the max craftable as if only that type were being made.
     * The player decides priority.</p>
     *
     * <p>Uses {@link LogBowEntry} since the data shape is identical.</p>
     */
    public List<LogBowEntry> getBoltTippingBreakdown()
    {
        int mithrilBolts = getCount(ItemID.MITHRIL_BOLTS);
        int adamantBolts = getCount(ItemID.ADAMANT_BOLTS);
        int runiteBolts  = getCount(ItemID.RUNITE_BOLTS);

        List<LogBowEntry> list = new ArrayList<>();
        addBoltTipIf(list, "Onyx Bolts",       ItemID.ONYX_BOLT_TIPS,        runiteBolts,  FLETCH_XP_ONYX_BOLT,        73);
        addBoltTipIf(list, "Dragonstone Bolts", ItemID.DRAGONSTONE_BOLT_TIPS, runiteBolts,  FLETCH_XP_DRAGONSTONE_BOLT, 71);
        addBoltTipIf(list, "Diamond Bolts",     ItemID.DIAMOND_BOLT_TIPS,     adamantBolts, FLETCH_XP_DIAMOND_BOLT,     65);
        addBoltTipIf(list, "Ruby Bolts",        ItemID.RUBY_BOLT_TIPS,        adamantBolts, FLETCH_XP_RUBY_BOLT,        63);
        addBoltTipIf(list, "Emerald Bolts",     ItemID.EMERALD_BOLT_TIPS,     mithrilBolts, FLETCH_XP_EMERALD_BOLT,     58);
        addBoltTipIf(list, "Sapphire Bolts",    ItemID.SAPPHIRE_BOLT_TIPS,    mithrilBolts, FLETCH_XP_SAPPHIRE_BOLT,    56);
        return list;
    }

    private void addBoltTipIf(List<LogBowEntry> list, String name, int tipItemId,
                               int baseBolts, double xpEach, int levelReq)
    {
        int tips      = getCount(tipItemId);
        int craftable = Math.min(tips, baseBolts);
        if (craftable > 0)
            list.add(new LogBowEntry(name, craftable, xpEach, levelReq));
    }

    /**
     * Fletching (logs → longbow u): banked logs by tier, ordered highest level first.
     * Each entry includes the minimum Fletching level required so the Bank tab can
     * gate display based on the player's actual level.
     *
     * <p>XP values are per log (one log = one longbow u):
     * Normal 10 | Oak 25 | Willow 41.5 | Maple 58.3 | Yew 75 | Magic 91.5 | Redwood 106
     */
    public List<LogBowEntry> getFletchingLogBreakdown()
    {
        List<LogBowEntry> list = new ArrayList<>();
        addLogIf(list, "Redwood Logs",  getCount(ITEM_REDWOOD_LOGS), FLETCH_XP_REDWOOD_LONGBOW, 90);
        addLogIf(list, "Magic Logs",    getCount(ITEM_MAGIC_LOGS),   FLETCH_XP_MAGIC_LONGBOW,   85);
        addLogIf(list, "Yew Logs",      getCount(ITEM_YEW_LOGS),     FLETCH_XP_YEW_LONGBOW,     70);
        addLogIf(list, "Maple Logs",    getCount(ITEM_MAPLE_LOGS),   FLETCH_XP_MAPLE_LONGBOW,   55);
        addLogIf(list, "Willow Logs",   getCount(ITEM_WILLOW_LOGS),  FLETCH_XP_WILLOW_LONGBOW,  35);
        addLogIf(list, "Oak Logs",      getCount(ITEM_OAK_LOGS),     FLETCH_XP_OAK_LONGBOW,     20);
        addLogIf(list, "Logs",          getCount(ITEM_LOGS),         FLETCH_XP_NORMAL_LONGBOW,   5);
        return list;
    }

    private void addLogIf(List<LogBowEntry> list, String name, int count, double xpEach, int levelReq)
    {
        if (count > 0) list.add(new LogBowEntry(name, count, xpEach, levelReq));
    }

    /**
     * Crafting (gems): uncut gems that can be cut with a chisel.
     * Listed highest XP first since that's priority order for Ironmen.
     */
    public List<CraftEntry> getCraftingGemBreakdown()
    {
        List<CraftEntry> list = new ArrayList<>();
        addIf(list, "Zenyte",      getCount(ITEM_UNCUT_ZENYTE),      CRAFT_XP_CUT_ZENYTE);
        addIf(list, "Dragonstone", getCount(ITEM_UNCUT_DRAGONSTONE), CRAFT_XP_CUT_DRAGONSTONE);
        addIf(list, "Diamond",     getCount(ITEM_UNCUT_DIAMOND),     CRAFT_XP_CUT_DIAMOND);
        addIf(list, "Ruby",        getCount(ITEM_UNCUT_RUBY),        CRAFT_XP_CUT_RUBY);
        addIf(list, "Emerald",     getCount(ITEM_UNCUT_EMERALD),     CRAFT_XP_CUT_EMERALD);
        addIf(list, "Sapphire",    getCount(ITEM_UNCUT_SAPPHIRE),    CRAFT_XP_CUT_SAPPHIRE);
        return list;
    }

    /**
     * Crafting (glass): molten glass blown into Unpowered Orbs (best XP method).
     * Requires a glassblowing pipe and level 46+ Crafting.
     */
    public List<CraftEntry> getCraftingGlassBreakdown()
    {
        List<CraftEntry> list = new ArrayList<>();
        int glass = getCount(ITEM_MOLTEN_GLASS);
        if (glass > 0) list.add(new CraftEntry("Unpowered Orb", glass, CRAFT_XP_GLASS_ORB));
        return list;
    }

    /**
     * Crafting (dragonhide): tanned leathers in bank, ordered highest XP first.
     *
     * <p>Each body requires 3 tanned leathers.  {@link DragonhideEntry} captures
     * the total leathers, how many bodies can be made right now, and any leftover
     * leather — so the Bank tab can show an accurate XP figure rather than an
     * inflated "all leathers × per-leather rate" estimate.</p>
     *
     * <p>Only tanned leathers are tracked — raw hides (before tanning) cannot be
     * crafted directly and are excluded.</p>
     */
    public List<DragonhideEntry> getDragonhideBreakdown()
    {
        List<DragonhideEntry> list = new ArrayList<>();
        addDhIf(list, "Black d'hide", getCount(ITEM_BLACK_DRAGON_LEATHER), CRAFT_XP_BLACK_DHIDE_BODY, 84);
        addDhIf(list, "Red d'hide",   getCount(ITEM_RED_DRAGON_LEATHER),   CRAFT_XP_RED_DHIDE_BODY,   77);
        addDhIf(list, "Blue d'hide",  getCount(ITEM_BLUE_DRAGON_LEATHER),  CRAFT_XP_BLUE_DHIDE_BODY,  71);
        addDhIf(list, "Green d'hide", getCount(ITEM_GREEN_DRAGON_LEATHER), CRAFT_XP_GREEN_DHIDE_BODY, 63);
        return list;
    }

    private void addDhIf(List<DragonhideEntry> list, String name,
                         int leathers, int xpPerBody, int levelReq)
    {
        if (leathers > 0)
            list.add(new DragonhideEntry(name, leathers, xpPerBody, levelReq));
    }

    /**
     * Number of Flax in the bank.  Flax is spun into Bowstrings at a Spinning
     * Wheel (Crafting 10, 15 XP each) — a passive Bowstring supply for Fletching.
     */
    public int getFlaxCount() { return getCount(ITEM_FLAX); }

    /**
     * Crafting (battlestaves): banked battlestaves × orbs, highest XP first.
     *
     * <p>Battlestaves are one of the primary Ironman Crafting XP sources: staves are
     * bought from Zaff/Naff daily, and orbs are charged at the elemental obelisks
     * (Thieving + Magic to get there, plus Slayer/PvM drops).  Only combinations
     * where BOTH the battlestaff and the specific orb are present are shown — a
     * single battlestaff count is shared across all four orb types (it is consumed
     * once per staff made).</p>
     *
     * <p>Levels: Water 54 (100 XP) · Earth 58 (112.5 XP) · Fire 62 (125 XP) · Air 66 (137.5 XP)</p>
     */
    public List<CraftEntry> getBattlestaffBreakdown()
    {
        List<CraftEntry> list = new ArrayList<>();
        int staves = getCount(ITEM_BATTLESTAFF);
        if (staves == 0) return list;  // no staves → nothing to show

        // Each orb type independently caps at min(staves, orbs) — a player may have
        // mixed orb supplies from different obelisk trips.
        addIf(list, "Water Battlestaff", Math.min(staves, getCount(ITEM_WATER_ORB)), CRAFT_XP_WATER_BATTLESTAFF);
        addIf(list, "Earth Battlestaff", Math.min(staves, getCount(ITEM_EARTH_ORB)), CRAFT_XP_EARTH_BATTLESTAFF);
        addIf(list, "Fire Battlestaff",  Math.min(staves, getCount(ITEM_FIRE_ORB)),  CRAFT_XP_FIRE_BATTLESTAFF);
        addIf(list, "Air Battlestaff",   Math.min(staves, getCount(ITEM_AIR_ORB)),   CRAFT_XP_AIR_BATTLESTAFF);
        return list;
    }

    /**
     * Construction: planks in bank, sorted by XP efficiency.
     * XP values are per plank using the most efficient furniture method at each tier.
     */
    public List<CraftEntry> getConstructionBreakdown()
    {
        List<CraftEntry> list = new ArrayList<>();
        addIf(list, "Mahogany Plank", getCount(ITEM_MAHOGANY_PLANK), CONST_XP_MAHOGANY_PLANK);
        addIf(list, "Teak Plank",     getCount(ITEM_TEAK_PLANK),     CONST_XP_TEAK_PLANK);
        addIf(list, "Oak Plank",      getCount(ITEM_OAK_PLANK),      CONST_XP_OAK_PLANK);
        addIf(list, "Plank",          getCount(ITEM_PLANK),           CONST_XP_PLANK);
        return list;
    }

    /**
     * Farming: herb seeds banked, ordered by XP value.
     * {@code totalXp} is an estimate: planting XP + (5 avg picks × harvest XP per herb).
     * Actual yield varies 3–8 herbs; this is a planning heuristic.
     */
    public List<CraftEntry> getFarmingBreakdown()
    {
        List<CraftEntry> list = new ArrayList<>();
        addIf(list, "Torstol Seed",    getCount(ITEM_TORSTOL_SEED),   FARM_XP_TORSTOL_SEED);
        addIf(list, "Dwarf Weed Seed", getCount(ITEM_DWARF_WEED_SEED),FARM_XP_DWARF_WEED_SEED);
        addIf(list, "Lantadyme Seed",  getCount(ITEM_LANTADYME_SEED), FARM_XP_LANTADYME_SEED);
        addIf(list, "Cadantine Seed",  getCount(ITEM_CADANTINE_SEED), FARM_XP_CADANTINE_SEED);
        addIf(list, "Snapdragon Seed", getCount(ITEM_SNAPDRAGON_SEED),FARM_XP_SNAPDRAGON_SEED);
        addIf(list, "Kwuarm Seed",     getCount(ITEM_KWUARM_SEED),    FARM_XP_KWUARM_SEED);
        addIf(list, "Avantoe Seed",    getCount(ITEM_AVANTOE_SEED),   FARM_XP_AVANTOE_SEED);
        addIf(list, "Ranarr Seed",     getCount(ITEM_RANARR_SEED),    FARM_XP_RANARR_SEED);
        addIf(list, "Toadflax Seed",   getCount(ITEM_TOADFLAX_SEED),  FARM_XP_TOADFLAX_SEED);
        return list;
    }

    /**
     * Prayer: dragon bones (and variants) in bank, with Chaos Altar XP values.
     *
     * <p>All XP values are at the <b>Chaos Altar</b> (level 38 Wilderness) which gives
     * <b>3.5× base XP</b> per bone used.  Additionally, each bone has a 50% chance of
     * NOT being consumed, effectively doubling the average XP per bone carried — but
     * the count shown here is the raw banked count, not a projected "effective" number.</p>
     *
     * <p>Ordered highest XP first (Superior Dragon Bones → Baby Dragon Bones).</p>
     */
    public List<CraftEntry> getPrayerBonesBreakdown()
    {
        List<CraftEntry> list = new ArrayList<>();
        addIf(list, "Superior Dragon Bones", getCount(ITEM_SUPERIOR_DRAGON_BONES), PRAY_XP_SUPERIOR_DRAGON_BONES);
        addIf(list, "Hydra Bones",           getCount(ITEM_HYDRA_BONES),           PRAY_XP_HYDRA_BONES);
        addIf(list, "Lava Dragon Bones",     getCount(ITEM_LAVA_DRAGON_BONES),     PRAY_XP_LAVA_DRAGON_BONES);
        addIf(list, "Drake Bones",           getCount(ITEM_DRAKE_BONES),           PRAY_XP_DRAKE_BONES);
        addIf(list, "Wyvern Bones",          getCount(ITEM_WYVERN_BONES),          PRAY_XP_WYVERN_BONES);
        addIf(list, "Dragon Bones",          getCount(ITEM_DRAGON_BONES),          PRAY_XP_DRAGON_BONES);
        addIf(list, "Wyrm Bones",            getCount(ITEM_WYRM_BONES),            PRAY_XP_WYRM_BONES);
        addIf(list, "Baby Dragon Bones",     getCount(ITEM_BABYDRAGON_BONES),      PRAY_XP_BABY_DRAGON_BONES);
        return list;
    }

    /**
     * Prayer: ensouled heads in bank that can be reanimated via the Arceuus spellbook.
     *
     * <p>Ensouled heads use a different mechanism from Chaos Altar bones — the player
     * must cast Reanimate on the head at the Dark Altar (or anywhere with Arceuus favour)
     * and then kill the reanimated creature to receive Prayer XP.  No bone-save mechanic;
     * XP is 100% granted on kill.</p>
     *
     * <p>Key heads tracked (ordered highest XP first):
     * Abyssal (1,600 XP · Arceuus 90) · Dragon / Dagannoth / Demon (1,560 XP · Arceuus 72)</p>
     */
    public List<CraftEntry> getEnsouledHeadBreakdown()
    {
        List<CraftEntry> list = new ArrayList<>();
        addIf(list, "Ensouled Abyssal Head",   getCount(ITEM_ENSOULED_ABYSSAL_HEAD),   PRAY_XP_ENSOULED_ABYSSAL);
        addIf(list, "Ensouled Dragon Head",    getCount(ITEM_ENSOULED_DRAGON_HEAD),    PRAY_XP_ENSOULED_DRAGON);
        addIf(list, "Ensouled Dagannoth Head", getCount(ITEM_ENSOULED_DAGANNOTH_HEAD), PRAY_XP_ENSOULED_DAGANNOTH);
        addIf(list, "Ensouled Demon Head",     getCount(ITEM_ENSOULED_DEMON_HEAD),     PRAY_XP_ENSOULED_DEMON);
        return list;
    }

    // ─── Smithing bar counts ──────────────────────────────────────────────────

    /**
     * Returns the number of Steel Bars in the bank.
     * Primary Ironman use: Cannonballs (4 per bar, 25.6 Smithing XP, requires
     * Smithing 35 + Dwarf Cannon quest completion).
     */
    public int getSteelBarCount()   { return getCount(ITEM_STEEL_BAR);   }

    /** Mithril Bars — best used in Giant's Foundry or smelted to platelegs for alching. */
    public int getMithrilBarCount() { return getCount(ITEM_MITHRIL_BAR); }

    /** Adamant Bars — Giant's Foundry or adamant platelegs (alch value). */
    public int getAdamantBarCount() { return getCount(ITEM_ADAMANT_BAR); }

    /** Rune Bars — Giant's Foundry or high-alch via rune scimitar/longsword. */
    public int getRuneBarCount()    { return getCount(ITEM_RUNE_BAR);    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private void addIf(List<CraftEntry> list, String name, int count, double xpEach)
    {
        if (count > 0) list.add(new CraftEntry(name, count, xpEach));
    }

    // ─── Herblore per-herb breakdown ─────────────────────────────────────────

    /**
     * Returns one {@link HerbPotionEntry} for every herb type present in the bank,
     * even when the secondary ingredient is missing or insufficient.
     *
     * <p>Unlike {@link #getHerbloreBreakdown()} (which requires both herb AND
     * secondary to be present), this method lets the UI show the player exactly
     * what herbs they have and what secondaries they still need.
     */
    public List<HerbPotionEntry> getHerbPotionBreakdown()
    {
        List<HerbPotionEntry> list = new ArrayList<>();
        addHerbIf(list, "Strength Potion",  "Tarromin",   getTarrominCount(),   "Limpwurt Root",       getCount(ITEM_LIMPWURT_ROOT),     HERB_XP_STRENGTH_POTION, 12);
        addHerbIf(list, "Super Attack",     "Irit",        getIritCount(),       "Eye of Newt",         getCount(ItemID.EYE_OF_NEWT),     HERB_XP_SUPER_ATTACK,    45);
        addHerbIf(list, "Prayer Potion",    "Ranarr",      getRanarCount(),      "Snape Grass",         getCount(ItemID.SNAPE_GRASS),     HERB_XP_PRAYER_POTION,   38);
        addHerbIf(list, "Super Energy",     "Avantoe",     getAvantoeCount(),    "Mort Myre Fungus",    getCount(ITEM_MORT_MYRE_FUNGUS),  HERB_XP_SUPER_ENERGY,    52);
        addHerbIf(list, "Super Strength",   "Kwuarm",      getKwuarmCount(),     "Limpwurt Root",       getCount(ITEM_LIMPWURT_ROOT),     HERB_XP_SUPER_STRENGTH,  55);
        addHerbIf(list, "Super Restore",    "Snapdragon",  getSnapdragonCount(), "Red Spider Eggs",     getCount(ITEM_RED_SPIDERS_EGGS),  HERB_XP_SUPER_RESTORE,   63);
        addHerbIf(list, "Super Defence",    "Cadantine",   getCadantineCount(),  "White Berries",       getCount(ITEM_WHITE_BERRIES),     HERB_XP_SUPER_DEFENCE,   66);
        addHerbIf(list, "Antifire Potion",  "Lantadyme",   getLantadymeCount(),  "Dragon Scale Dust",   getCount(ITEM_DRAGON_SCALE_DUST), HERB_XP_ANTIFIRE,        69);
        addHerbIf(list, "Ranging Potion",   "Dwarf Weed",  getDwarfWeedCount(),  "Wine of Zamorak",     getCount(ITEM_WINE_OF_ZAMORAK),   HERB_XP_RANGING_POTION,  72);
        addHerbIf(list, "Saradomin Brew",   "Toadflax",    getToadflaxCount(),   "Crushed Nest",        getCount(ITEM_CRUSHED_NEST),      HERB_XP_SARADOMIN_BREW,  81);
        return list;
    }

    private void addHerbIf(List<HerbPotionEntry> list,
                           String potionName, String herbName, int herbCount,
                           String secondaryName, int secondaryCount,
                           double xpEach, int levelReq)
    {
        if (herbCount > 0)
            list.add(new HerbPotionEntry(potionName, herbName, herbCount,
                                         secondaryName, secondaryCount, xpEach, levelReq));
    }

    // ─── CraftEntry inner class ───────────────────────────────────────────────

    /**
     * A single craftable/processable item: its name, banked count, and
     * estimated total XP if all items are processed.
     */
    public static class CraftEntry
    {
        public final String name;
        public final int    count;
        public final int    totalXp;

        public CraftEntry(String name, int count, double xpEach)
        {
            this.name    = name;
            this.count   = count;
            this.totalXp = (int) (count * xpEach);
        }
    }

    // ─── HerbPotionEntry inner class ──────────────────────────────────────────

    /**
     * Represents one herb type found in the bank alongside its paired secondary.
     *
     * <p>Unlike {@link CraftEntry}, this entry is created whenever the herb is present
     * regardless of whether the secondary is available — letting the UI show the player
     * their herb inventory even when secondaries are missing.
     */
    public static class HerbPotionEntry
    {
        /** Name of the potion this herb makes (e.g. "Super Attack"). */
        public final String potionName;
        /** Display name of the herb (e.g. "Irit"). */
        public final String herbName;
        /** Number of clean + grimy herbs in the bank. */
        public final int    herbCount;
        /** Display name of the required secondary ingredient. */
        public final String secondaryName;
        /** Number of secondaries in the bank (0 = missing). */
        public final int    secondaryCount;
        /** How many potions can be crafted right now = min(herbCount, secondaryCount). */
        public final int    craftable;
        /** XP gained per potion. */
        public final double xpEach;
        /** Herblore level required to make this potion. */
        public final int    levelReq;
        /** Total XP if all craftable potions are made. */
        public final int    totalXp;

        HerbPotionEntry(String potionName, String herbName, int herbCount,
                        String secondaryName, int secondaryCount,
                        double xpEach, int levelReq)
        {
            this.potionName     = potionName;
            this.herbName       = herbName;
            this.herbCount      = herbCount;
            this.secondaryName  = secondaryName;
            this.secondaryCount = secondaryCount;
            this.craftable      = Math.min(herbCount, secondaryCount);
            this.xpEach         = xpEach;
            this.levelReq       = levelReq;
            this.totalXp        = (int) (craftable * xpEach);
        }

        /** {@code true} when the secondary is completely absent — nothing can be crafted. */
        public boolean isMissingSecondary()  { return secondaryCount == 0; }

        /** {@code true} when secondaries are present but fewer than the herb count. */
        public boolean isShortOnSecondary()  { return secondaryCount > 0 && secondaryCount < herbCount; }
    }

    // ─── DragonhideEntry inner class ─────────────────────────────────────────

    /**
     * One dragon leather type found in the bank, with body-crafting breakdown.
     *
     * <p>Bodies require 3 leathers each.  {@code totalXp} is exact — leftover
     * leathers (1 or 2 that cannot form a full body) are excluded from the XP
     * total so the display matches what the player will actually earn.</p>
     */
    public static class DragonhideEntry
    {
        /** Display label (e.g. "Black d'hide"). */
        public final String name;
        /** Total tanned leathers in bank. */
        public final int leathers;
        /** How many bodies can be crafted right now (floor(leathers / 3)). */
        public final int bodies;
        /** Leftover leathers that cannot form a full body (leathers % 3). */
        public final int leftover;
        /** XP per body (186 / 210 / 234 / 258). */
        public final int xpPerBody;
        /** Minimum Crafting level to make the body (63 / 71 / 77 / 84). */
        public final int levelReq;
        /** Total XP if all craftable bodies are made (bodies × xpPerBody). */
        public final int totalXp;

        DragonhideEntry(String name, int leathers, int xpPerBody, int levelReq)
        {
            this.name       = name;
            this.leathers   = leathers;
            this.bodies     = leathers / 3;
            this.leftover   = leathers % 3;
            this.xpPerBody  = xpPerBody;
            this.levelReq   = levelReq;
            this.totalXp    = bodies * xpPerBody;
        }
    }

    // ─── AlchCandidate inner class ────────────────────────────────────────────

    /**
     * A banked item that may be profitable to High Alch.
     *
     * <p>Only the item ID and count are stored here — GP values are looked up
     * by {@code BankTabPanel} via {@code ItemManager} on the EDT so that
     * {@code BankScanner} has no dependency on RuneLite's item price cache.</p>
     */
    public static class AlchCandidate
    {
        /** RuneLite item ID. */
        public final int    itemId;
        /** Display name of the item. */
        public final String name;
        /** Quantity banked. */
        public final int    count;

        AlchCandidate(int itemId, String name, int count)
        {
            this.itemId = itemId;
            this.name   = name;
            this.count  = count;
        }
    }

    /**
     * Item IDs tracked as potential High Alch candidates.
     *
     * <p>Covers common Ironman drop-table loot and crafted items that are
     * frequently banked in quantity:
     * <ul>
     *   <li>Rune weapons and armour (Slayer / barrows loot)</li>
     *   <li>Dragon items (Wilderness, GWD drops)</li>
     *   <li>Crafted bows and crossbow bodies (Fletching output)</li>
     *   <li>Adamant items (smithed / dropped)</li>
     * </ul>
     */
    private static final int[][] ALCH_CANDIDATES = {
        // { itemId, displayName-index } — names parallel the IDs below
        // Rune weapons
        { ItemID.RUNE_SCIMITAR,         0 },
        { ItemID.RUNE_SWORD,            1 },
        { ItemID.RUNE_LONGSWORD,        2 },
        { ItemID.RUNE_2H_SWORD,         3 },
        { ItemID.RUNE_BATTLEAXE,        4 },
        { ItemID.RUNE_WARHAMMER,        5 },
        { ItemID.RUNE_MACE,             6 },
        { ItemID.RUNE_DAGGER,           7 },
        { ItemID.RUNE_HASTA,            8 },
        { ItemID.RUNE_SPEAR,            9 },
        // Rune armour
        { ItemID.RUNE_FULL_HELM,       10 },
        { ItemID.RUNE_PLATEBODY,       11 },
        { ItemID.RUNE_PLATELEGS,       12 },
        { ItemID.RUNE_PLATESKIRT,      13 },
        { ItemID.RUNE_KITESHIELD,      14 },
        { ItemID.RUNE_SQ_SHIELD,       15 },
        // Dragon items
        { ItemID.DRAGON_SCIMITAR,      16 },
        { ItemID.DRAGON_LONGSWORD,     17 },
        { ItemID.DRAGON_BATTLEAXE,     18 },
        { ItemID.DRAGON_MACE,          19 },
        { ItemID.DRAGON_DAGGER,        20 },
        { ItemID.DRAGON_SPEAR,         21 },
        // Adamant (smithed / barrows loot)
        { ItemID.ADAMANT_PLATEBODY,    22 },
        { ItemID.ADAMANT_PLATELEGS,    23 },
        // Bows (fletched / drops)
        { ItemID.MAGIC_LONGBOW,        24 },
        { ItemID.YEW_LONGBOW,          25 },
        { ItemID.MAPLE_LONGBOW,        26 },
        // Crossbow bodies
        { ItemID.ADAMANT_CROSSBOW,     27 },
        { ItemID.RUNE_CROSSBOW,        28 },
        // Misc high-alch items from drops
        { ItemID.GRANITE_MAUL,         29 },
        { ItemID.GRANITE_HAMMER,       30 },
    };

    private static final String[] ALCH_NAMES = {
        "Rune Scimitar", "Rune Sword", "Rune Longsword", "Rune 2h Sword",
        "Rune Battleaxe", "Rune Warhammer", "Rune Mace", "Rune Dagger",
        "Rune Hasta", "Rune Spear",
        "Rune Full Helm", "Rune Platebody", "Rune Platelegs", "Rune Plateskirt",
        "Rune Kiteshield", "Rune Sq Shield",
        "Dragon Scimitar", "Dragon Longsword", "Dragon Battleaxe",
        "Dragon Mace", "Dragon Dagger", "Dragon Spear",
        "Adamant Platebody", "Adamant Platelegs",
        "Magic Longbow", "Yew Longbow", "Maple Longbow",
        "Adamant Crossbow", "Rune Crossbow",
        "Granite Maul", "Granite Hammer",
    };

    /**
     * Returns all tracked items present in the bank as {@link AlchCandidate}s,
     * regardless of whether alching is profitable — the caller is responsible
     * for filtering by alch profit using live GE prices.
     *
     * <p>Only items with {@code count > 0} are included.</p>
     */
    public List<AlchCandidate> getAlchCandidates()
    {
        List<AlchCandidate> list = new ArrayList<>();
        for (int[] row : ALCH_CANDIDATES)
        {
            int itemId = row[0];
            int nameIndex = row[1];
            int count = getCount(itemId);
            if (count > 0)
                list.add(new AlchCandidate(itemId, ALCH_NAMES[nameIndex], count));
        }
        return list;
    }

    // ─── Sailing shipyard summary ─────────────────────────────────────────────

    /**
     * A lightweight snapshot of all materials relevant to ship hull/sail upgrades.
     *
     * <p>The three "ready" flags indicate whether the player has enough of every
     * required material to attempt the corresponding Shipwright upgrade right now.
     * Upgrade cost constants are package-accessible so {@code BankTabPanel} can
     * render exact have/need lines without duplicating the numbers.</p>
     */
    public static class ShipyardSummary
    {
        // Raw banked counts
        public final int regularPlanks;   // Regular planks — Sloop hull
        public final int oakPlanks;       // Oak planks — Caravel hull
        public final int teakPlanks;      // Teak planks — Galleon hull
        public final int boltOfCloth;     // Bolt of cloth — all sail tiers
        public final int steelNails;      // Steel nails — Sloop + Caravel
        public final int mithrilNails;    // Mithril nails — Galleon

        // Derived readiness flags
        public final boolean sloopReady;
        public final boolean caravelReady;
        public final boolean galleonReady;

        /**
         * {@code true} when the player has at least one sail-related material
         * (cloth or nails).  Used by {@code BankTabPanel} to decide whether to
         * render the section at all.
         */
        public final boolean hasSailingMaterials;

        ShipyardSummary(int regularPlanks, int oakPlanks, int teakPlanks,
                        int boltOfCloth, int steelNails, int mithrilNails)
        {
            this.regularPlanks = regularPlanks;
            this.oakPlanks     = oakPlanks;
            this.teakPlanks    = teakPlanks;
            this.boltOfCloth   = boltOfCloth;
            this.steelNails    = steelNails;
            this.mithrilNails  = mithrilNails;

            this.sloopReady   = regularPlanks >= SLOOP_PLANKS_NEED
                             && steelNails    >= SLOOP_NAILS_NEED
                             && boltOfCloth   >= SLOOP_CLOTH_NEED;

            this.caravelReady = oakPlanks     >= CARAVEL_PLANKS_NEED
                             && steelNails    >= CARAVEL_NAILS_NEED
                             && boltOfCloth   >= CARAVEL_CLOTH_NEED;

            this.galleonReady = teakPlanks    >= GALLEON_PLANKS_NEED
                             && mithrilNails  >= GALLEON_NAILS_NEED
                             && boltOfCloth   >= GALLEON_CLOTH_NEED;

            this.hasSailingMaterials = boltOfCloth > 0 || steelNails > 0 || mithrilNails > 0;
        }
    }

    /**
     * Returns a {@link ShipyardSummary} snapshot from the current bank cache.
     * Reads planks, nails and sail cloth needed for ship tier upgrades.
     * Safe to call on any thread — reads only from the HashMap cache.
     */
    public ShipyardSummary getShipyardSummary()
    {
        return new ShipyardSummary(
            getCount(ITEM_PLANK),
            getCount(ITEM_OAK_PLANK),
            getCount(ITEM_TEAK_PLANK),
            getCount(ITEM_BOLT_OF_CLOTH),
            getCount(ItemID.STEEL_NAILS),
            getCount(ITEM_MITHRIL_NAILS)
        );
    }

    // ─── LogBowEntry inner class ──────────────────────────────────────────────

    /**
     * A log type that can be fletched into longbows (u).
     * Includes the minimum Fletching level required so the UI can show a
     * level-gate note when the player can't yet use these logs.
     */
    public static class LogBowEntry
    {
        /** Display name of the log type (e.g. "Yew Logs"). */
        public final String logName;
        /** Number of logs in the bank. */
        public final int    count;
        /** XP per log (= XP per longbow u). */
        public final double xpEach;
        /** Minimum Fletching level to fletch these logs. */
        public final int    levelReq;
        /** Total XP if all logs are fletched. */
        public final int    totalXp;

        LogBowEntry(String logName, int count, double xpEach, int levelReq)
        {
            this.logName  = logName;
            this.count    = count;
            this.xpEach   = xpEach;
            this.levelReq = levelReq;
            this.totalXp  = (int) (count * xpEach);
        }
    }
}
