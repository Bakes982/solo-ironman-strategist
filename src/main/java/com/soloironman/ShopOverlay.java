package com.soloironman;

import net.runelite.api.Client;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;

/**
 * Smart Shop Overlay — highlights items in any shop window with a
 * 2px coloured border when the player's bank stock is below configured thresholds.
 *
 * Colour coding:
 *  RED    → critically low  (< 20% of threshold)
 *  YELLOW → getting low     (< 100% of threshold)
 *
 * Implements the information-only, no-automation requirement for Plugin Hub compliance.
 */
@Singleton
public class ShopOverlay extends WidgetItemOverlay
{
    // Colour constants
    private static final Color COLOR_CRITICAL = new Color(220, 38,  38,  200); // red
    private static final Color COLOR_WARNING  = new Color(234, 179,  8,  200); // yellow
    private static final Color COLOR_VARLAMORE = new Color(99, 102, 241, 200);  // indigo (Varlamore-specific)
    private static final Stroke BORDER_STROKE  = new BasicStroke(2f);

    // Varlamore — Hunters' sunlight crossbow crafting (confirmed OSRS wiki IDs)
    // Materials: Sunlight antelope antler (29168) + Hunters' crossbow → Hunters' sunlight crossbow (28869)
    private static final int ITEM_SUNLIGHT_ANTELOPE_ANTLER  = 29_168;
    private static final int ITEM_HUNTERS_SUNLIGHT_CROSSBOW = 28_869;

    // Rosewood plank (Sailing content) — confirmed OSRS wiki ID 31438
    private static final int ITEM_ROSEWOOD_PLANK = 31_438;

    private final Client client;
    private final BankScanner bankScanner;
    private final SoloIronmanConfig config;

    @Inject
    public ShopOverlay(Client client, BankScanner bankScanner, SoloIronmanConfig config)
    {
        this.client      = client;
        this.bankScanner = bankScanner;
        this.config      = config;

        // Register this overlay to fire only on shop widget items.
        // ComponentID.SHOP_INVENTORY_ITEM_CONTAINER = 19660800 = (InterfaceID.SHOP << 16) | 0
        // Confirmed via javap on net.runelite.client:1.10.44
        showOnInterfaces(ComponentID.SHOP_INVENTORY_ITEM_CONTAINER);
    }

    // ─── Core render method ───────────────────────────────────────────────────

    @Override
    public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
    {
        // showOnInterfaces already gates this to the shop context;
        // the explicit widget check is a redundant safety net.
        if (!bankScanner.isHasData())
        {
            // Bank has never been opened this session — no data to compare against
            return;
        }

        Color highlightColor = resolveHighlightColor(itemId);
        if (highlightColor == null)
        {
            return;
        }

        Rectangle bounds = widgetItem.getCanvasBounds();
        if (bounds == null)
        {
            return;
        }

        // Draw the 2px coloured border inside the item's canvas bounds
        graphics.setStroke(BORDER_STROKE);
        graphics.setColor(highlightColor);
        graphics.drawRect(
            bounds.x + 1,
            bounds.y + 1,
            bounds.width  - 2,
            bounds.height - 2
        );
    }

    // ─── Highlight logic ──────────────────────────────────────────────────────

    /**
     * Returns the highlight colour for a given shop item, or {@code null}
     * if no highlight is needed.
     */
    private Color resolveHighlightColor(int itemId)
    {
        // ── Broad Arrowheads ──────────────────────────────────────────────────
        if (itemId == ItemID.BROAD_ARROWHEADS)
        {
            int threshold = config.broadArrowheadThreshold();
            int inBank    = bankScanner.getCount(ItemID.BROAD_ARROWHEADS);
            if (inBank < threshold)
            {
                return inBank < (threshold / 5) ? COLOR_CRITICAL : COLOR_WARNING;
            }
        }

        // ── Eye of Newt (highlight if player has Irit but no Eye of Newt in bank) ─
        // Jatizso / Taverley herblore shops carry this item
        if (itemId == ItemID.EYE_OF_NEWT)
        {
            if (bankScanner.getIritCount() > 0 && bankScanner.getCount(ItemID.EYE_OF_NEWT) == 0)
            {
                return COLOR_CRITICAL;
            }
        }

        // ── Nature Runes ──────────────────────────────────────────────────────
        if (itemId == ItemID.NATURE_RUNE)
        {
            int threshold = config.natureRuneThreshold();
            int inBank    = bankScanner.getCount(ItemID.NATURE_RUNE);
            if (inBank < threshold)
            {
                return inBank < (threshold / 5) ? COLOR_CRITICAL : COLOR_WARNING;
            }
        }

        // ── Rosewood Planks (Sailing meta, level 40+ gate) ────────────────────
        if (itemId == ITEM_ROSEWOOD_PLANK)
        {
            int sailingLevel = getSailingLevel();
            if (sailingLevel >= 40)
            {
                int inBank = bankScanner.getCount(ITEM_ROSEWOOD_PLANK);
                if (inBank < config.rosewoodPlankThreshold())
                {
                    return COLOR_WARNING;
                }
            }
        }

        // ── Feathers (fishing shops) — highlight when broad arrowheads exceed feathers ─
        // Feathers are the bottleneck for fletching Broad Arrows when stocking up at Slayer masters.
        if (itemId == ItemID.FEATHER)
        {
            int shortfall = bankScanner.getFeatherShortfall();
            if (shortfall > 0)
            {
                return shortfall > 2000 ? COLOR_CRITICAL : COLOR_WARNING;
            }
        }

        // ── Sunlight antelope antler (Varlamore — highlight if crossbow not yet crafted) ─
        // Crafting: antler + Hunters' crossbow → Hunters' sunlight crossbow (74 Fletching)
        if (itemId == ITEM_SUNLIGHT_ANTELOPE_ANTLER)
        {
            if (!bankScanner.hasItem(ITEM_HUNTERS_SUNLIGHT_CROSSBOW))
            {
                return COLOR_VARLAMORE;
            }
        }

        return null;
    }

    /**
     * Returns the player's Sailing level.
     * Until Sailing is a tracked RuneLite {@link Skill}, falls back to the
     * manually configured value in {@link SoloIronmanConfig#sailingLevel()}.
     */
    private int getSailingLevel()
    {
        try
        {
            Skill sailing = Skill.valueOf("SAILING");
            return client.getRealSkillLevel(sailing);
        }
        catch (IllegalArgumentException e)
        {
            // Skill.SAILING not yet in this RuneLite version — use manual config
            return config.sailingLevel();
        }
    }
}
