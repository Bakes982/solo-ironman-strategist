package com.soloironman;

import net.runelite.api.ItemComposition;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * BANK tab — live multi-skill analysis of the player's bank contents.
 *
 * <p>Sections (all rebuilt on every {@link #updateBankDisplay()} call):
 * <ol>
 *   <li><b>Herblore</b>      — craftable potion counts + banked XP</li>
 *   <li><b>Cooking</b>       — raw fish + Jug of Wine, highest XP first</li>
 *   <li><b>Crafting</b>      — dragonhide bodies, battlestaves, gems, glass, Superglass Make, flax</li>
 *   <li><b>Construction</b>  — plank tier breakdown</li>
 *   <li><b>Fletching</b>     — logs→longbows, arrows, gem bolt tips, broad arrows/bolts</li>
 *   <li><b>Smithing</b>      — steel→cannonballs + mithril/adamant/rune bars</li>
 *   <li><b>Farming</b>       — herb seeds with estimated run XP</li>
 *   <li><b>Prayer</b>        — dragon bones (Chaos Altar 3.5×) + ensouled heads (Arceuus)</li>
 *   <li><b>Warnings</b>      — missing secondaries (Eye of Newt, Snape Grass, etc.)</li>
 * </ol>
 *
 * <p>Each row is an expandable {@link StrategistCard} — clicking it reveals a
 * per-item XP breakdown so the player can judge processing priority at a glance.
 *
 * <p>Threading: {@link #updateBankDisplay()} wraps all Swing work in
 * {@link SwingUtilities#invokeLater} internally; callers on the game thread are safe.
 */
@Singleton
public class BankTabPanel extends JPanel
{
    // ── Section heading colours (match skill themes) ──────────────────────────
    private static final Color C_MAIN_BG    = ColorScheme.DARK_GRAY_COLOR;
    private static final Color C_DIM        = new Color(150, 150, 150);
    private static final Color C_RED        = new Color(248, 113, 113);
    private static final Color C_AMBER      = new Color(255, 152,   0);
    private static final Color C_BLUE       = new Color(147, 197, 253);
    private static final Color C_HERB_GREEN = new Color( 56, 120,  80);  // Herblore
    private static final Color C_CRAFT_TAN  = new Color(188, 143, 100);  // Crafting
    private static final Color C_CONST_WOOD = new Color(180, 130,  70);  // Construction
    private static final Color C_FARM_LIME  = new Color(132, 204,  22);  // Farming
    private static final Color C_SAIL_CYAN  = new Color( 34, 211, 238);  // Sailing / Shipyard
    private static final Color C_SMITH_STEEL  = new Color(148, 163, 184); // Smithing (steel-blue)
    private static final Color C_PRAYER_WHITE = new Color(200, 200, 255); // Prayer (light blue-white)

    // ── OSRS XP table — XP_TABLE[level] = total XP required to reach that level ─
    private static final int[] XP_TABLE = buildXpTable();

    private static int[] buildXpTable()
    {
        int[] table = new int[100]; // indices 1-99; [0] unused
        double points = 0;
        for (int i = 1; i < 99; i++)
        {
            points += Math.floor(i + 300.0 * Math.pow(2.0, i / 7.0));
            table[i + 1] = (int) (points / 4);
        }
        return table;
    }

    /** Returns the skill level (1–99) for a given cumulative XP total. */
    private static int levelForXp(int xp)
    {
        for (int lvl = 99; lvl >= 2; lvl--)
            if (xp >= XP_TABLE[lvl]) return lvl;
        return 1;
    }

    // ── Dynamic panels (rebuilt on each bank update) ──────────────────────────
    private final JPanel bankContentPanel;
    private final JPanel warningPanel;
    private final JPanel alchContentPanel;
    private final JPanel sailingContentPanel;

    private final BankScanner        bankScanner;
    private final ItemManager        itemManager;
    private final SoloIronmanConfig  config;

    /** Skill XP snapshot — updated every game tick via {@link #updateSkillXp}. */
    private volatile int[] cachedSkillXp;

    @Inject
    public BankTabPanel(BankScanner bankScanner, ItemManager itemManager, SoloIronmanConfig config)
    {
        super();
        this.bankScanner = bankScanner;
        this.itemManager = itemManager;
        this.config      = config;

        bankContentPanel = new JPanel();
        bankContentPanel.setLayout(new BoxLayout(bankContentPanel, BoxLayout.Y_AXIS));
        bankContentPanel.setBackground(C_MAIN_BG);
        bankContentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        warningPanel = new JPanel();
        warningPanel.setLayout(new BoxLayout(warningPanel, BoxLayout.Y_AXIS));
        warningPanel.setBackground(C_MAIN_BG);
        warningPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        alchContentPanel = new JPanel();
        alchContentPanel.setLayout(new BoxLayout(alchContentPanel, BoxLayout.Y_AXIS));
        alchContentPanel.setBackground(C_MAIN_BG);
        alchContentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        sailingContentPanel = new JPanel();
        sailingContentPanel.setLayout(new BoxLayout(sailingContentPanel, BoxLayout.Y_AXIS));
        sailingContentPanel.setBackground(C_MAIN_BG);
        sailingContentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        buildUi();
    }

    private void buildUi()
    {
        setLayout(new BorderLayout());
        setBackground(C_MAIN_BG);

        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBackground(C_MAIN_BG);
        col.setBorder(new EmptyBorder(6, 8, 8, 8));

        col.add(sectionLabel("BANK ANALYSIS", C_DIM));
        col.add(bankContentPanel);

        col.add(divider());

        col.add(sectionLabel("WARNINGS", C_RED));
        col.add(warningPanel);

        col.add(divider());

        col.add(sectionLabel("ALCH ANALYZER", new Color(255, 215, 0)));  // gold
        col.add(dimLabel("  Items worth alching: HA value > GE price"));
        col.add(Box.createRigidArea(new Dimension(0, 4)));
        col.add(alchContentPanel);

        col.add(divider());

        col.add(sectionLabel("SHIPYARD", C_SAIL_CYAN));
        col.add(dimLabel("  Ship hull/sail materials for upgrade readiness"));
        col.add(Box.createRigidArea(new Dimension(0, 4)));
        col.add(sailingContentPanel);

        add(col, BorderLayout.NORTH);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public update (EDT-safe)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Receives a fresh skill-XP snapshot from the game thread every tick.
     * Stored so {@link #buildHerbloreSection} can compute level projections.
     */
    public void updateSkillXp(int[] xpValues)
    {
        this.cachedSkillXp = xpValues;
    }

    /**
     * Rebuilds every skill section from the scanner's cached data.
     * Safe to call from the game thread; Swing work runs on the EDT.
     */
    public void updateBankDisplay()
    {
        SwingUtilities.invokeLater(() ->
        {
            bankContentPanel.removeAll();
            warningPanel.removeAll();

            sailingContentPanel.removeAll();
            if (!bankScanner.isHasData())
            {
                bankContentPanel.add(dimLabel("Open your bank to scan..."));
                alchContentPanel.add(dimLabel("  Open your bank to scan..."));
                sailingContentPanel.add(dimLabel("  Open your bank to scan..."));
            }
            else
            {
                buildHerbloreSection();
                buildCookingSection();
                buildCraftingSection();
                buildConstructionSection();
                buildFletchingSection();
                buildSmithingSection();
                buildFarmingSection();
                buildPrayerSection();
                buildWarningSection();
                buildAlchSection();
                buildSailingSection();
            }

            bankContentPanel.revalidate();
            bankContentPanel.repaint();
            warningPanel.revalidate();
            warningPanel.repaint();
            alchContentPanel.revalidate();
            alchContentPanel.repaint();
            sailingContentPanel.revalidate();
            sailingContentPanel.repaint();
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Section builders
    // ─────────────────────────────────────────────────────────────────────────

    private void buildHerbloreSection()
    {
        bankContentPanel.add(skillLabel("HERBLORE", C_HERB_GREEN));
        List<BankScanner.HerbPotionEntry> list = bankScanner.getHerbPotionBreakdown();
        if (list.isEmpty())
        {
            bankContentPanel.add(dimLabel("  No herbs found in bank."));
        }
        else
        {
            // XP projection summary — only show when we have the player's current XP
            int[] xpSnap = cachedSkillXp;
            if (xpSnap != null && Skill.HERBLORE.ordinal() < xpSnap.length)
            {
                int currentXp       = xpSnap[Skill.HERBLORE.ordinal()];
                int totalCraftableXp = list.stream().mapToInt(e -> e.totalXp).sum();
                if (totalCraftableXp > 0)
                {
                    int fromLevel = levelForXp(currentXp);
                    int toLevel   = levelForXp(currentXp + totalCraftableXp);
                    String summary = "  Banked: " + fmt(totalCraftableXp) + " xp"
                            + "  (Lvl " + fromLevel + " → " + toLevel + ")";
                    bankContentPanel.add(dimLabel(summary));
                    bankContentPanel.add(vgap(3));
                }
            }

            for (BankScanner.HerbPotionEntry e : list)
            {
                bankContentPanel.add(herbCard(e));
                bankContentPanel.add(vgap(3));
            }
        }
        bankContentPanel.add(vgap(6));
    }

    private void buildCookingSection()
    {
        bankContentPanel.add(skillLabel("COOKING", C_AMBER));
        List<BankScanner.CraftEntry> list = bankScanner.getCookingBreakdown();
        if (list.isEmpty())
        {
            bankContentPanel.add(dimLabel("  No raw fish, grapes or jugs of water found."));
        }
        else
        {
            // XP projection summary — same pattern as Herblore and Battlestaves
            int[] xpSnap = cachedSkillXp;
            if (xpSnap != null && Skill.COOKING.ordinal() < xpSnap.length)
            {
                int currentXp  = xpSnap[Skill.COOKING.ordinal()];
                int totalCookXp = list.stream().mapToInt(e -> e.totalXp).sum();
                if (totalCookXp > 0)
                {
                    int fromLevel = levelForXp(currentXp);
                    int toLevel   = levelForXp(currentXp + totalCookXp);
                    bankContentPanel.add(dimLabel(
                            "  Banked: " + fmt(totalCookXp) + " xp"
                            + "  (Lvl " + fromLevel + " → " + toLevel + ")"));
                    bankContentPanel.add(vgap(3));
                }
            }
            addCraftCards(list, StrategistCard.Category.FOUNDATION);
        }
        bankContentPanel.add(vgap(6));
    }

    private void buildCraftingSection()
    {
        List<BankScanner.DragonhideEntry> dhides       = bankScanner.getDragonhideBreakdown();
        List<BankScanner.CraftEntry>      battlestaves = bankScanner.getBattlestaffBreakdown();
        List<BankScanner.CraftEntry>      gems         = bankScanner.getCraftingGemBreakdown();
        List<BankScanner.CraftEntry>      glass        = bankScanner.getCraftingGlassBreakdown();
        int flax        = bankScanner.getFlaxCount();
        int seaweed     = bankScanner.getGiantSeaweedCount();
        int sand        = bankScanner.getBucketOfSandCount();

        // Superglass Make: 1 Giant Seaweed + 3 Buckets of Sand → 18 Molten Glass
        // Craft XP from the resulting glass (blown into orbs at 52.5 xp each)
        int superglasCasts   = Math.min(seaweed, sand / 3);
        int superglasOutput  = superglasCasts * 18;    // molten glass produced
        int superglasXp      = (int) (superglasOutput * 52.5); // if all blown into orbs

        if (dhides.isEmpty() && battlestaves.isEmpty() && gems.isEmpty()
                && glass.isEmpty() && flax == 0 && superglasCasts == 0) return;

        bankContentPanel.add(skillLabel("CRAFTING", C_CRAFT_TAN));

        // ── Combined XP projection across all Crafting sources ──────────────
        int[] xpSnap = cachedSkillXp;
        if (xpSnap != null && Skill.CRAFTING.ordinal() < xpSnap.length)
        {
            int currentXp = xpSnap[Skill.CRAFTING.ordinal()];
            int totalXp   = dhides.stream().mapToInt(e -> e.totalXp).sum()
                          + battlestaves.stream().mapToInt(e -> e.totalXp).sum()
                          + gems.stream().mapToInt(e -> e.totalXp).sum()
                          + glass.stream().mapToInt(e -> e.totalXp).sum()
                          + flax * 15
                          + superglasXp;
            if (totalXp > 0)
            {
                int fromLvl = levelForXp(currentXp);
                int toLvl   = levelForXp(currentXp + totalXp);
                bankContentPanel.add(dimLabel(
                        "  Banked: " + fmt(totalXp) + " xp"
                        + "  (Lvl " + fromLvl + " → " + toLvl + ")"));
                bankContentPanel.add(vgap(3));
            }
        }

        // ── Dragonhide (needle) — highest XP per item, listed first ─────────
        if (!dhides.isEmpty())
        {
            bankContentPanel.add(subLabel("Dragonhide (needle)"));
            for (BankScanner.DragonhideEntry e : dhides)
            {
                bankContentPanel.add(dragonhideCard(e));
                bankContentPanel.add(vgap(3));
            }
        }

        // ── Battlestaves (orb) ───────────────────────────────────────────────
        if (!battlestaves.isEmpty())
        {
            bankContentPanel.add(subLabel("Battlestaves (orb)"));
            addCraftCards(battlestaves, StrategistCard.Category.SKILLING);
        }

        // ── Gems (chisel) ────────────────────────────────────────────────────
        if (!gems.isEmpty())
        {
            bankContentPanel.add(subLabel("Gems (chisel)"));
            addCraftCards(gems, StrategistCard.Category.GEAR);
        }

        // ── Giant Seaweed → Superglass Make → Glass (pipe) ──────────────────
        if (superglasCasts > 0)
        {
            bankContentPanel.add(subLabel("Superglass Make (Magic 77)"));
            String valueLine = "x" + seaweed + " seaweed + x" + sand
                             + " sand → x" + superglasOutput + " glass";
            String expand    = superglasCasts + " casts of Superglass Make\n"
                             + seaweed + " × Giant Seaweed  +  " + sand + " × Bucket of Sand\n"
                             + "→ " + superglasOutput + " × Molten Glass  (18 per cast)\n"
                             + "Then blow all glass into Orbs: " + fmt(superglasXp) + " Crafting XP\n"
                             + "Req: Magic 77  |  Farm seaweed at Fossil Island patch\n"
                             + "Astral runes needed: " + superglasCasts + " × 6 = " + (superglasCasts * 6);
            bankContentPanel.add(new StrategistCard(
                    "Giant Seaweed → Glass", valueLine, expand, StrategistCard.Category.SKILLING));
            bankContentPanel.add(vgap(3));
        }

        // ── Existing molten glass (pipe) ─────────────────────────────────────
        if (!glass.isEmpty())
        {
            bankContentPanel.add(subLabel("Glass (pipe)"));
            addCraftCards(glass, StrategistCard.Category.GEAR);
        }

        // ── Flax (spinning wheel) ────────────────────────────────────────────
        if (flax > 0)
        {
            bankContentPanel.add(subLabel("Flax (spinning wheel)"));
            String valueLine = "x" + flax + "  ·  " + fmt(flax * 15) + " xp";
            String expand    = flax + " × Flax\n"
                             + "→ " + flax + " × Bowstring  (1 : 1)\n"
                             + fmt(flax * 15) + " Crafting XP  (15 per flax)\n"
                             + "Req: Crafting 10  |  Use at any Spinning Wheel\n"
                             + "Bowstrings → Fletch Longbows (or sell to shop for GP)";
            bankContentPanel.add(new StrategistCard(
                    "Flax → Bowstrings", valueLine, expand, StrategistCard.Category.SKILLING));
            bankContentPanel.add(vgap(3));
        }

        bankContentPanel.add(vgap(6));
    }

    /**
     * Builds a dragonhide body card with exact body count and leftover note.
     *
     * <p>Unlike generic craft cards, dragonhide bodies consume 3 leathers each
     * so we need to show bodies craftable (not raw leather count) and any
     * leftover leathers that cannot form a complete body.</p>
     */
    private StrategistCard dragonhideCard(BankScanner.DragonhideEntry e)
    {
        String valueLine = "x" + e.bodies + " bodies  ·  " + fmt(e.totalXp) + " xp"
                + (e.leftover > 0 ? "  (+" + e.leftover + " left)" : "");
        String expand    = e.leathers + " × " + e.name + " leather\n"
                         + "→ " + e.bodies + " × " + e.name + " body  (3 leathers each)\n"
                         + fmt(e.totalXp) + " total XP  (" + e.xpPerBody + " xp/body)\n"
                         + (e.leftover > 0 ? e.leftover + " leather(s) left over\n" : "")
                         + "Min Crafting: " + e.levelReq;
        return new StrategistCard(e.name + " body", valueLine, expand,
                StrategistCard.Category.SKILLING);
    }

    private void buildConstructionSection()
    {
        List<BankScanner.CraftEntry> list = bankScanner.getConstructionBreakdown();
        if (list.isEmpty()) return;  // hide section if no planks

        bankContentPanel.add(skillLabel("CONSTRUCTION", C_CONST_WOOD));
        addCraftCards(list, StrategistCard.Category.FOUNDATION);
        bankContentPanel.add(vgap(6));
    }

    private void buildFletchingSection()
    {
        List<BankScanner.CraftEntry>    broadList  = bankScanner.getFletchingBreakdown();
        List<BankScanner.LogBowEntry>   logList    = bankScanner.getFletchingLogBreakdown();
        List<BankScanner.LogBowEntry>   arrowList  = bankScanner.getArrowBreakdown();
        List<BankScanner.LogBowEntry>   boltList   = bankScanner.getBoltTippingBreakdown();
        if (broadList.isEmpty() && logList.isEmpty() && arrowList.isEmpty() && boltList.isEmpty()) return;

        int[] xpSnap = cachedSkillXp;
        int fletchLevel = 1;
        if (xpSnap != null && Skill.FLETCHING.ordinal() < xpSnap.length)
            fletchLevel = levelForXp(xpSnap[Skill.FLETCHING.ordinal()]);

        bankContentPanel.add(skillLabel("FLETCHING", C_BLUE));

        // ── Logs → longbow (u) ──
        if (!logList.isEmpty())
        {
            // Total XP only from tiers the player can currently fletch
            int usableXp = 0;
            for (BankScanner.LogBowEntry e : logList)
                if (fletchLevel >= e.levelReq) usableXp += e.totalXp;

            if (usableXp > 0)
            {
                int fromLevel = fletchLevel;
                int toLevel   = levelForXp(
                        (xpSnap != null ? xpSnap[Skill.FLETCHING.ordinal()] : 0) + usableXp);
                String summary = "  Logs: " + fmt(usableXp) + " xp  (Lvl "
                        + fromLevel + " → " + toLevel + ")";
                bankContentPanel.add(dimLabel(summary));
                bankContentPanel.add(vgap(3));
            }

            bankContentPanel.add(subLabel("Logs (longbow u)"));
            for (BankScanner.LogBowEntry e : logList)
            {
                boolean canFletch = fletchLevel >= e.levelReq;
                String valueLine = "x" + e.count + "  ·  " + fmt(e.totalXp) + " xp"
                        + (canFletch ? "" : "  (need lvl " + e.levelReq + ")");
                String expand = e.count + " × " + e.logName + "\n"
                        + fmt(e.totalXp) + " total xp  (" + (int) e.xpEach + " xp each)\n"
                        + "Min Fletching: " + e.levelReq
                        + (canFletch ? "  ✓" : "  ✗ (locked)");
                StrategistCard.Category cat = canFletch
                        ? StrategistCard.Category.GEAR
                        : StrategistCard.Category.FOUNDATION;
                bankContentPanel.add(new StrategistCard(e.logName, valueLine, expand, cat));
                bankContentPanel.add(vgap(3));
            }
        }

        // ── Standard arrows (arrowhead + shaft + feather) ──
        if (!arrowList.isEmpty())
        {
            // Total XP from all unlocked arrow tiers
            int arrowXp = 0;
            for (BankScanner.LogBowEntry e : arrowList)
                if (fletchLevel >= e.levelReq) arrowXp += e.totalXp;

            if (arrowXp > 0)
            {
                int fromLevel = fletchLevel;
                int toLevel   = levelForXp(
                        (xpSnap != null ? xpSnap[Skill.FLETCHING.ordinal()] : 0) + arrowXp);
                String summary = "  Arrows: " + fmt(arrowXp) + " xp  (Lvl "
                        + fromLevel + " → " + toLevel + ")";
                bankContentPanel.add(dimLabel(summary));
                bankContentPanel.add(vgap(3));
            }

            bankContentPanel.add(subLabel("Arrows (tip + shaft + feather)"));
            for (BankScanner.LogBowEntry e : arrowList)
            {
                boolean canFletch = fletchLevel >= e.levelReq;
                String valueLine = "x" + fmt(e.count) + "  ·  " + fmt(e.totalXp) + " xp"
                        + (canFletch ? "" : "  (need lvl " + e.levelReq + ")");
                String expand = fmt(e.count) + " × " + e.logName + "\n"
                        + fmt(e.totalXp) + " total xp  (" + e.xpEach + " xp each)\n"
                        + "Min Fletching: " + e.levelReq
                        + (canFletch ? "  ✓" : "  ✗ (locked)") + "\n"
                        + "Uses arrow shafts + feathers (shared resources)";
                StrategistCard.Category cat = canFletch
                        ? StrategistCard.Category.GEAR
                        : StrategistCard.Category.FOUNDATION;
                bankContentPanel.add(new StrategistCard(e.logName, valueLine, expand, cat));
                bankContentPanel.add(vgap(3));
            }
        }

        // ── Gem bolt tipping (gem tips + base bolts) ──
        if (!boltList.isEmpty())
        {
            int boltXp = 0;
            for (BankScanner.LogBowEntry e : boltList)
                if (fletchLevel >= e.levelReq) boltXp += e.totalXp;

            if (boltXp > 0)
            {
                int fromLevel = fletchLevel;
                int toLevel   = levelForXp(
                        (xpSnap != null ? xpSnap[Skill.FLETCHING.ordinal()] : 0) + boltXp);
                String summary = "  Bolt tips: " + fmt(boltXp) + " xp  (Lvl "
                        + fromLevel + " → " + toLevel + ")";
                bankContentPanel.add(dimLabel(summary));
                bankContentPanel.add(vgap(3));
            }

            bankContentPanel.add(subLabel("Gem Bolt Tips (tip + base bolt)"));
            for (BankScanner.LogBowEntry e : boltList)
            {
                boolean canFletch = fletchLevel >= e.levelReq;
                String base = e.logName.contains("Sapphire") || e.logName.contains("Emerald")
                        ? "Mithril bolts"
                        : e.logName.contains("Ruby") || e.logName.contains("Diamond")
                        ? "Adamant bolts"
                        : "Runite bolts";
                String valueLine = "x" + fmt(e.count) + "  ·  " + fmt(e.totalXp) + " xp"
                        + (canFletch ? "" : "  (need lvl " + e.levelReq + ")");
                String expand = fmt(e.count) + " × " + e.logName + "\n"
                        + fmt(e.totalXp) + " total xp  (" + e.xpEach + " xp each)\n"
                        + "Base: " + base + "  |  Min Fletching: " + e.levelReq
                        + (canFletch ? "  ✓" : "  ✗ (locked)") + "\n"
                        + "Enchant with Magic for combat use";
                StrategistCard.Category cat = canFletch
                        ? StrategistCard.Category.GEAR
                        : StrategistCard.Category.FOUNDATION;
                bankContentPanel.add(new StrategistCard(e.logName, valueLine, expand, cat));
                bankContentPanel.add(vgap(3));
            }
        }

        // ── Broad arrows / bolts ──
        if (!broadList.isEmpty())
        {
            boolean hasOtherSections = !logList.isEmpty() || !arrowList.isEmpty() || !boltList.isEmpty();
            if (hasOtherSections) bankContentPanel.add(subLabel("Broad (Slayer shop)"));
            addCraftCards(broadList, StrategistCard.Category.GEAR);
        }

        bankContentPanel.add(vgap(6));
    }

    private void buildFarmingSection()
    {
        List<BankScanner.CraftEntry> list = bankScanner.getFarmingBreakdown();
        if (list.isEmpty()) return;  // hide section if no seeds

        bankContentPanel.add(skillLabel("FARMING — SEEDS", C_FARM_LIME));
        bankContentPanel.add(dimLabel(
                "  Est. XP = planting + avg 5 herb picks per seed"));
        bankContentPanel.add(vgap(3));
        addCraftCards(list, StrategistCard.Category.SKILLING);
        bankContentPanel.add(vgap(6));
    }

    private void buildPrayerSection()
    {
        List<BankScanner.CraftEntry> bones = bankScanner.getPrayerBonesBreakdown();
        List<BankScanner.CraftEntry> heads = bankScanner.getEnsouledHeadBreakdown();
        if (bones.isEmpty() && heads.isEmpty()) return;

        bankContentPanel.add(skillLabel("PRAYER", C_PRAYER_WHITE));

        // XP projection summary (bones + heads combined)
        int[] xpSnap    = cachedSkillXp;
        int currentXp   = 0;
        int prayerLevel = 1;
        if (xpSnap != null && Skill.PRAYER.ordinal() < xpSnap.length)
        {
            currentXp   = xpSnap[Skill.PRAYER.ordinal()];
            prayerLevel = levelForXp(currentXp);
        }

        int totalBoneXp = bones.stream().mapToInt(e -> e.totalXp).sum();
        int totalHeadXp = heads.stream().mapToInt(e -> e.totalXp).sum();
        int totalXp     = totalBoneXp + totalHeadXp;

        if (totalXp > 0 && xpSnap != null)
        {
            int toLevel = levelForXp(currentXp + totalXp);
            bankContentPanel.add(dimLabel(
                    "  Banked: " + fmt(totalXp) + " xp  (Lvl " + prayerLevel + " → " + toLevel + ")"));
            bankContentPanel.add(vgap(3));
        }

        // ── Bones → Chaos Altar ──
        if (!bones.isEmpty())
        {
            bankContentPanel.add(subLabel("Bones (Chaos Altar — 3.5× base XP, 50% save)"));
            for (BankScanner.CraftEntry e : bones)
            {
                int xpEach   = e.count > 0 ? e.totalXp / e.count : 0;
                String valueLine = "x" + e.count + "  ·  " + fmt(e.totalXp) + " xp";
                String expand    = e.count + " × " + e.name + "\n"
                                 + fmt(e.totalXp) + " xp  (" + xpEach + " xp each)\n"
                                 + "Method: Chaos Altar (lvl 38 Wilderness)\n"
                                 + "50% chance bone is NOT consumed per use";
                bankContentPanel.add(new StrategistCard(
                        e.name, valueLine, expand, StrategistCard.Category.COMBAT));
                bankContentPanel.add(vgap(3));
            }
        }

        // ── Ensouled heads → Arceuus reanimation ──
        if (!heads.isEmpty())
        {
            bankContentPanel.add(subLabel("Ensouled Heads (Arceuus reanimation)"));
            for (BankScanner.CraftEntry e : heads)
            {
                int xpEach   = e.count > 0 ? e.totalXp / e.count : 0;
                int magicReq = e.name.contains("Abyssal") ? 90
                             : e.name.contains("Dragon")  ? 93
                             : e.name.contains("Demon")   ? 84
                             : 72; // Dagannoth
                String valueLine = "x" + e.count + "  ·  " + fmt(e.totalXp) + " xp";
                String expand    = e.count + " × " + e.name + "\n"
                                 + fmt(e.totalXp) + " xp  (" + xpEach + " xp each)\n"
                                 + "Method: Reanimate (Arceuus) → kill creature\n"
                                 + "Req: Magic " + magicReq + " (Arceuus spellbook)";
                bankContentPanel.add(new StrategistCard(
                        e.name, valueLine, expand, StrategistCard.Category.COMBAT));
                bankContentPanel.add(vgap(3));
            }
        }

        bankContentPanel.add(vgap(6));
    }

    /**
     * ALCH ANALYZER — lists banked items where High Alch value exceeds the
     * current GE price, sorted by profit per item (highest first).
     *
     * <p>Uses {@link ItemManager#getItemPrice} for GE data and
     * {@link ItemComposition#getHaPrice} (via {@link ItemManager#getItemComposition})
     * for the fixed High Alch value.  Both calls are safe on the EDT — ItemManager
     * returns cached values without network I/O.</p>
     *
     * <p>Only items with {@code count > 0} AND {@code haValue > gePrice > 0} are shown.
     * Items with a GE price of 0 (unknown / untraded) are skipped — they produce
     * false positives since 0 is always less than any alch value.</p>
     */
    private void buildAlchSection()
    {
        alchContentPanel.removeAll();

        java.util.List<BankScanner.AlchCandidate> candidates = bankScanner.getAlchCandidates();

        // Resolve prices and filter to profitable items only.
        // Each int[] row: [candidateIndex, haValue, gePrice, profitEach]
        java.util.List<int[]> profitable = new java.util.ArrayList<>();
        for (int i = 0; i < candidates.size(); i++)
        {
            BankScanner.AlchCandidate c = candidates.get(i);
            int gePrice = itemManager.getItemPrice(c.itemId);
            if (gePrice <= 0) continue;  // unknown price → skip (avoids false positives)

            ItemComposition comp = itemManager.getItemComposition(c.itemId);
            int haValue = comp.getHaPrice();
            if (haValue <= 0) continue;  // no alch value

            int profitEach = haValue - gePrice;
            if (profitEach > 0)
                profitable.add(new int[]{ i, haValue, gePrice, profitEach });
        }

        // Sort by profit-per-item descending so the best alch is always at the top
        profitable.sort((a, b) -> Integer.compare(b[3], a[3]));

        if (profitable.isEmpty())
        {
            alchContentPanel.add(dimLabel("  No profitable alch items in bank."));
            return;
        }

        for (int[] row : profitable)
        {
            BankScanner.AlchCandidate c    = candidates.get(row[0]);
            int haValue     = row[1];
            int gePrice     = row[2];
            int profitEach  = row[3];
            int totalProfit = profitEach * c.count;

            String valueLine = "+" + fmtGp(profitEach) + " each  ·  "
                             + fmtGp(totalProfit) + " total  (×" + c.count + ")";

            String expand = c.name + " × " + c.count + "\n"
                          + "HA value:  " + fmtGp(haValue) + " gp\n"
                          + "GE price:  " + fmtGp(gePrice) + " gp\n"
                          + "Profit:    +" + fmtGp(profitEach) + " gp each\n"
                          + "Total:     +" + fmtGp(totalProfit) + " gp\n"
                          + "Cast: High Level Alchemy (Magic 55 + 5 Nature Runes)";

            StrategistCard card = new StrategistCard(
                    c.name, valueLine, expand, StrategistCard.Category.SKILLING);
            card.updateValue(valueLine, new Color(255, 215, 0));  // gold — profit signal
            alchContentPanel.add(card);
            alchContentPanel.add(vgap(3));
        }
    }

    /** Compact GP formatter: 1_500_000→"1.5M", 65_000→"65K", 800→"800". */
    private static String fmtGp(int gp)
    {
        if (gp >= 1_000_000)
        {
            double m = gp / 1_000_000.0;
            return (m == Math.floor(m)) ? (int) m + "M" : String.format("%.1fM", m);
        }
        if (gp >= 1_000)
        {
            double k = gp / 1_000.0;
            return (k == Math.floor(k)) ? (int) k + "K" : String.format("%.0fK", k);
        }
        return String.valueOf(gp);
    }

    /**
     * SMITHING — metal bars in bank, with cannonball production prominently shown.
     *
     * <p>Section layout:
     * <ol>
     *   <li><b>Steel Bars</b> — displayed as "X bars → Y cannonballs", the primary
     *       Ironman use.  Cannonballs fire at ~2/sec in a Dwarf Multicannon so the
     *       expand card also shows estimated task minutes at that rate.</li>
     *   <li><b>Mithril / Adamant / Rune Bars</b> — shown with the platelegs XP rate
     *       (the simplest consistent benchmark) and a tip toward Giant's Foundry or
     *       high-alch routes.</li>
     * </ol>
     *
     * <p>Section is hidden when no bars are banked.</p>
     */
    private void buildSmithingSection()
    {
        int steel   = bankScanner.getSteelBarCount();
        int mithril = bankScanner.getMithrilBarCount();
        int adamant = bankScanner.getAdamantBarCount();
        int rune    = bankScanner.getRuneBarCount();
        if (steel == 0 && mithril == 0 && adamant == 0 && rune == 0) return;

        bankContentPanel.add(skillLabel("SMITHING", C_SMITH_STEEL));

        // ── Steel → Cannonballs ──
        if (steel > 0)
        {
            int cannonballs = steel * 4;
            int smithXp     = (int) (steel * 25.6);
            // At ~2 balls/sec with a Dwarf Multicannon, 120 balls/min
            int taskMins    = Math.max(1, cannonballs / 120);
            String valueLine = "x" + steel + " bars → x" + cannonballs
                             + " balls  ·  " + fmt(smithXp) + " xp";
            String expand   = steel + " × Steel Bar\n"
                            + "→ " + cannonballs + " × Cannonball  (4 per bar)\n"
                            + fmt(smithXp) + " Smithing XP  (25.6 per bar)\n"
                            + "Req: Smithing 35  +  Dwarf Cannon quest\n"
                            + "Cannon fires ~2 balls/sec → ~" + taskMins + " min of task ammo";
            StrategistCard card = new StrategistCard(
                    "Steel → Cannonballs", valueLine, expand, StrategistCard.Category.COMBAT);
            card.updateValue(valueLine, C_AMBER);  // amber — notable resource
            bankContentPanel.add(card);
            bankContentPanel.add(vgap(3));
        }

        // ── Higher-tier bars ──
        // XP reference = platelegs method (3 bars, highest common benchmark).
        // Giant's Foundry rates vary by bar mix — we note it in the expand text.
        addBarCard("Mithril Bars",  mithril,  50.0,
                "Mithril Platelegs (50 xp/bar) → alch  |  Giant's Foundry for bonus XP");
        addBarCard("Adamant Bars",  adamant,  62.5,
                "Adamant Platelegs (62.5 xp/bar) → alch  |  Giant's Foundry for bonus XP");
        addBarCard("Rune Bars",     rune,     75.0,
                "Rune Scimitar/Longsword (75 xp/bar) → alch  |  Giant's Foundry for bonus XP");

        bankContentPanel.add(vgap(6));
    }

    /**
     * Renders a single higher-tier bar card (Mithril / Adamant / Rune).
     *
     * @param name    Display label (e.g. "Rune Bars")
     * @param bars    Number of bars in the bank (0 = skip)
     * @param xpPer   XP per bar using the platelegs reference method
     * @param tip     One-line guidance for the expand body
     */
    private void addBarCard(String name, int bars, double xpPer, String tip)
    {
        if (bars == 0) return;
        int totalXp = (int) (bars * xpPer);
        String valueLine = "x" + bars + "  ·  " + fmt(totalXp) + " xp  (" + (int) xpPer + " xp/bar)";
        String expand    = bars + " × " + name + "\n"
                         + fmt(totalXp) + " total XP  (" + xpPer + " xp/bar)\n"
                         + tip;
        bankContentPanel.add(new StrategistCard(name, valueLine, expand, StrategistCard.Category.SKILLING));
        bankContentPanel.add(vgap(3));
    }

    /**
     * SHIPYARD — shows Sloop / Caravel / Galleon upgrade readiness based on
     * banked planks, nails and bolt of cloth.
     *
     * <p>Each tier renders a {@link StrategistCard}:
     * <ul>
     *   <li><b>Green (SKILLING)</b>  — all materials stockpiled, ready to upgrade</li>
     *   <li><b>Amber (FOUNDATION)</b> — partially stocked, shows what is missing</li>
     * </ul>
     *
     * <p>Section is always shown so it acts as a reminder even when the player
     * has nothing banked yet.  Upgrade costs are beta-era estimates — the footer
     * note tells the player to verify with the Shipwright NPC.
     */
    private void buildSailingSection()
    {
        BankScanner.ShipyardSummary s = bankScanner.getShipyardSummary();

        // Materials summary line
        String materialLine = "  Cloth: " + s.boltOfCloth
                + "  ·  Steel nails: " + s.steelNails
                + "  ·  Mithril nails: " + s.mithrilNails;
        sailingContentPanel.add(dimLabel(materialLine));
        sailingContentPanel.add(vgap(3));

        addShipTierCard("Sloop Upgrade",
            "Planks",      s.regularPlanks, BankScanner.SLOOP_PLANKS_NEED,
            "Steel nails", s.steelNails,    BankScanner.SLOOP_NAILS_NEED,
            "Cloth",       s.boltOfCloth,   BankScanner.SLOOP_CLOTH_NEED);

        addShipTierCard("Caravel Upgrade",
            "Oak planks",  s.oakPlanks,     BankScanner.CARAVEL_PLANKS_NEED,
            "Steel nails", s.steelNails,    BankScanner.CARAVEL_NAILS_NEED,
            "Cloth",       s.boltOfCloth,   BankScanner.CARAVEL_CLOTH_NEED);

        addShipTierCard("Galleon Upgrade",
            "Teak planks", s.teakPlanks,    BankScanner.GALLEON_PLANKS_NEED,
            "Mith nails",  s.mithrilNails,  BankScanner.GALLEON_NAILS_NEED,
            "Cloth",       s.boltOfCloth,   BankScanner.GALLEON_CLOTH_NEED);

        sailingContentPanel.add(dimLabel("  * Costs are estimates — confirm with Shipwright NPC"));
    }

    /**
     * Builds a single ship-tier upgrade card.
     *
     * @param title       Card header (e.g. "Caravel Upgrade")
     * @param pName       Plank type label (e.g. "Oak planks")
     * @param pHave       How many planks the player has banked
     * @param pNeed       How many planks the upgrade costs
     * @param nName       Nail type label
     * @param nHave       Nails banked
     * @param nNeed       Nails needed
     * @param cHave       Cloth banked
     * @param cNeed       Cloth needed
     */
    private void addShipTierCard(String title,
                                  String pName, int pHave, int pNeed,
                                  String nName, int nHave, int nNeed,
                                  String cName, int cHave, int cNeed)
    {
        boolean ready = pHave >= pNeed && nHave >= nNeed && cHave >= cNeed;

        // Build "need X more Y" shortfall list for the value line
        List<String> missing = new ArrayList<>();
        if (pHave < pNeed) missing.add("need " + (pNeed - pHave) + " " + pName);
        if (nHave < nNeed) missing.add("need " + (nNeed - nHave) + " " + nName);
        if (cHave < cNeed) missing.add("need " + (cNeed - cHave) + " " + cName);

        String valueLine = ready ? "READY  ✓" : String.join("  ·  ", missing);

        String expand = pName + ": " + pHave + " / " + pNeed
                      + "\n" + nName + ": " + nHave + " / " + nNeed
                      + "\n" + cName + ": " + cHave + " / " + cNeed;

        StrategistCard.Category cat = ready
                ? StrategistCard.Category.SKILLING
                : StrategistCard.Category.FOUNDATION;

        StrategistCard card = new StrategistCard(title, valueLine, expand, cat);
        card.updateValue(valueLine,
                ready ? new Color(74, 222, 128) : C_AMBER);  // green = ready, amber = stocking

        sailingContentPanel.add(card);
        sailingContentPanel.add(vgap(3));
    }

    private void buildWarningSection()
    {
        if (!config.herbloreSecondaryWarning())
        {
            warningPanel.add(dimLabel("  Secondary warnings disabled."));
            return;
        }

        boolean anyWarning = false;

        if (bankScanner.hasMissingEyeOfNewt())
        {
            StrategistCard w = StrategistCard.metric(
                    "Irit (x" + bankScanner.getIritCount() + ")",
                    "⚠  Missing Eye of Newt",
                    StrategistCard.Category.COMBAT);
            w.updateValue("⚠  Missing Eye of Newt", C_RED);
            warningPanel.add(w);
            warningPanel.add(vgap(4));
            anyWarning = true;
        }

        int shortfall = bankScanner.getSnapeGrassShortfall();
        if (shortfall > 0)
        {
            StrategistCard w = StrategistCard.metric(
                    "Ranarr (x" + bankScanner.getRanarCount() + ")",
                    "⚠  Need " + shortfall + " more Snape Grass",
                    StrategistCard.Category.COMBAT);
            w.updateValue("⚠  Need " + shortfall + " more Snape Grass", C_RED);
            warningPanel.add(w);
            warningPanel.add(vgap(4));
            anyWarning = true;
        }

        if (bankScanner.hasMissingRedSpiderEggs())
        {
            StrategistCard w = StrategistCard.metric(
                    "Snapdragon (x" + bankScanner.getSnapdragonCount() + ")",
                    "⚠  Missing Red Spider Eggs",
                    StrategistCard.Category.FOUNDATION);
            w.updateValue("⚠  Missing Red Spider Eggs", C_RED);
            warningPanel.add(w);
            warningPanel.add(vgap(4));
            anyWarning = true;
        }

        if (bankScanner.hasMissingMortMyreFungus())
        {
            StrategistCard w = StrategistCard.metric(
                    "Avantoe (x" + bankScanner.getAvantoeCount() + ")",
                    "⚠  Missing Mort Myre Fungus",
                    StrategistCard.Category.FOUNDATION);
            w.updateValue("⚠  Missing Mort Myre Fungus", C_RED);
            warningPanel.add(w);
            warningPanel.add(vgap(4));
            anyWarning = true;
        }

        int fungusShortfall = bankScanner.getMortMyreFungusShortfall();
        if (fungusShortfall > 0 && !bankScanner.hasMissingMortMyreFungus())
        {
            StrategistCard w = StrategistCard.metric(
                    "Avantoe (x" + bankScanner.getAvantoeCount() + ")",
                    "⚠  Need " + fungusShortfall + " more Mort Myre Fungus",
                    StrategistCard.Category.FOUNDATION);
            w.updateValue("⚠  Need " + fungusShortfall + " more Mort Myre Fungus", C_AMBER);
            warningPanel.add(w);
            warningPanel.add(vgap(4));
            anyWarning = true;
        }

        if (!anyWarning)
            warningPanel.add(dimLabel("  No issues detected.  ✓"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Card factory
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a herb potion card with three visual states:
     * <ul>
     *   <li><b>Red (COMBAT)</b>   — herb present, secondary completely missing → "needs X"</li>
     *   <li><b>Amber (FOUNDATION)</b> — herb present, secondary partially present → partial XP + buylist</li>
     *   <li><b>Green (SKILLING)</b>   — herb + secondary both present in full → craftable XP</li>
     * </ul>
     */
    private StrategistCard herbCard(BankScanner.HerbPotionEntry e)
    {
        String title = e.potionName + "  (lvl " + e.levelReq + ")";
        String valueLine;
        String expandText;
        StrategistCard.Category cat;

        if (e.isMissingSecondary())
        {
            // Red — secondary completely absent
            valueLine  = e.herbName + " x" + e.herbCount + "  ·  needs " + e.secondaryName;
            expandText = e.herbName + " × " + e.herbCount + "\n"
                       + e.secondaryName + " × 0  ← missing\n"
                       + "Buy " + e.herbCount + " " + e.secondaryName
                       + " to unlock " + fmt((int)(e.herbCount * e.xpEach)) + " xp";
            cat = StrategistCard.Category.COMBAT;
        }
        else if (e.isShortOnSecondary())
        {
            // Amber — partial secondaries
            int need = e.herbCount - e.secondaryCount;
            valueLine  = "x" + e.craftable + " craftable  ·  " + fmt(e.totalXp) + " xp";
            expandText = e.herbName + " × " + e.herbCount + "\n"
                       + e.secondaryName + " × " + e.secondaryCount + "\n"
                       + "Craftable now: " + e.craftable + " potions = " + fmt(e.totalXp) + " xp\n"
                       + "Buy " + need + " more " + e.secondaryName + " for full batch\n"
                       + "Full batch = " + fmt((int)(e.herbCount * e.xpEach)) + " xp";
            cat = StrategistCard.Category.FOUNDATION;
        }
        else
        {
            // Green — ready to craft
            valueLine  = "x" + e.craftable + "  ·  " + fmt(e.totalXp) + " xp banked";
            expandText = e.craftable + " × " + e.potionName + "\n"
                       + fmt(e.totalXp) + " total xp  (" + (int)e.xpEach + " xp each)\n"
                       + e.herbName + " × " + e.herbCount
                       + "  +  " + e.secondaryName + " × " + e.secondaryCount;
            cat = StrategistCard.Category.SKILLING;
        }

        StrategistCard card = new StrategistCard(title, valueLine, expandText, cat);
        // Auto-expand missing-secondary cards so warnings are immediately visible
        if (e.isMissingSecondary()) card.setExpanded(true);
        return card;
    }

    private void addCraftCards(List<BankScanner.CraftEntry> entries, StrategistCard.Category cat)
    {
        for (BankScanner.CraftEntry e : entries)
        {
            bankContentPanel.add(craftCard(e, cat));
            bankContentPanel.add(vgap(3));
        }
    }

    /**
     * One craft row: name + "x{count} · {total}xp banked".
     * Clicking expands to show per-item XP rate.
     */
    private StrategistCard craftCard(BankScanner.CraftEntry e, StrategistCard.Category cat)
    {
        String valueLine = "x" + e.count + "  ·  " + fmt(e.totalXp) + " xp banked";

        int xpEach = e.count > 0 ? e.totalXp / e.count : 0;
        String expand = e.count + " × " + e.name + "\n"
                + fmt(e.totalXp) + " total xp  (" + fmt(xpEach) + " xp each)";

        return new StrategistCard(e.name, valueLine, expand, cat);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private JLabel sectionLabel(String text, Color color)
    {
        JLabel l = new JLabel(text);
        l.setFont(FontManager.getRunescapeSmallFont());
        l.setForeground(color);
        l.setBorder(new EmptyBorder(10, 4, 6, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    /** Bold skill name heading (sits above its cards). */
    private JLabel skillLabel(String text, Color color)
    {
        JLabel l = new JLabel(text);
        l.setFont(FontManager.getRunescapeSmallFont());
        l.setForeground(color);
        l.setBorder(new EmptyBorder(4, 0, 4, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    /** Smaller italic sub-heading (e.g. "Gems (chisel)"). */
    private JLabel subLabel(String text)
    {
        JLabel l = new JLabel(text);
        l.setFont(FontManager.getRunescapeSmallFont());
        l.setForeground(new Color(180, 180, 180));
        l.setBorder(new EmptyBorder(2, 6, 2, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JLabel dimLabel(String text)
    {
        JLabel l = new JLabel(text);
        l.setFont(FontManager.getRunescapeSmallFont());
        l.setForeground(new Color(150, 150, 150));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JSeparator divider()
    {
        JSeparator s = new JSeparator();
        s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        s.setForeground(new Color(55, 65, 81));
        s.setBorder(new EmptyBorder(6, 0, 6, 0));
        return s;
    }

    private Component vgap(int px) { return Box.createRigidArea(new Dimension(0, px)); }

    private static String fmt(int xp)
    {
        if (xp >= 1_000_000) return String.format("%.1fM", xp / 1_000_000.0);
        if (xp >= 1_000)     return String.format("%,d", xp);
        return String.valueOf(xp);
    }
}
