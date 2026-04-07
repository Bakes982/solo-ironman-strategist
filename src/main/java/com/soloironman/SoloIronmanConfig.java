package com.soloironman;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("soloironman")
public interface SoloIronmanConfig extends Config
{
    // ─── The New 2026 Goal Tracker & Supply Settings ──────────────────────────

    @ConfigSection(
        name        = "Active Grinds",
        description = "Settings for your current item goals and supply warnings",
        position    = 0
    )
    String grindsSection = "grinds";

    enum IronmanGoal {
        // ── Early / mid-game ─────────────────────────────────────────────────
        FIRE_CAPE          ("Fire Cape",             6570),
        DRAGON_DEFENDER    ("Dragon Defender",      12954),
        ABYSSAL_WHIP       ("Abyssal Whip",          4151),
        OCCULT             ("Occult Necklace",       12002),
        TRIDENT            ("Trident of the Swamp",  12899),
        BERSERKER_RING     ("Berserker Ring",         6737),
        DRAGON_WARHAMMER   ("Dragon Warhammer",      13576),
        ARMADYL_CROSSBOW   ("Armadyl Crossbow",      11785),
        // ── Mid-game bosses ──────────────────────────────────────────────────
        BANDOS_CHEST       ("Bandos Chestplate",     11832),
        BANDOS_TASSETS     ("Bandos Tassets",        11834),
        ARMADYL_CHEST      ("Armadyl Chestplate",    11828),
        ARMADYL_HELM       ("Armadyl Helmet",        11826),
        // ── Late-game ────────────────────────────────────────────────────────
        BOWFA              ("Enhanced Crystal Seed", 25859),
        EYE_OF_AYAK        ("Eye of Ayak",           29102),
        TWISTED_BOW        ("Twisted Bow",           20997),
        SCYTHE             ("Scythe of Vitur",       22325),
        INFERNAL_CAPE      ("Infernal Cape",         21295);

        private final String name;
        private final int itemId;
        IronmanGoal(String name, int itemId) { this.name = name; this.itemId = itemId; }
        public String getName() { return name; }
        public int getItemId() { return itemId; }
    }

    @ConfigItem(
        keyName     = "currentGoal",
        name        = "Active Goal",
        description = "Select the item you are currently grinding for",
        section     = grindsSection,
        position    = 1
    )
    default IronmanGoal currentGoal() { return IronmanGoal.BOWFA; }

    @ConfigItem(
        keyName     = "lowSupplyWarning",
        name        = "Low Supply Alert (Hours)",
        description = "Turns the overlay text RED when supplies drop below this many hours",
        section     = grindsSection,
        position    = 2
    )
    default double lowSupplyWarning() { return 1.0; }

    // ─── Shop Overlay Thresholds ────────────────────────────────────────────

    @ConfigSection(
        name        = "Shop Overlay Thresholds",
        description = "Minimum bank quantities before shop items are highlighted",
        position    = 1
    )
    String shopSection = "shop";

    @ConfigItem(
        keyName     = "broadArrowheadThreshold",
        name        = "Broad Arrowheads",
        description = "Highlight in shop (yellow) if bank < this. Red if < 1/5 of this.",
        section     = shopSection,
        position    = 1
    )
    default int broadArrowheadThreshold()
    {
        return 5000;
    }

    @ConfigItem(
        keyName     = "natureRuneThreshold",
        name        = "Nature Runes",
        description = "Highlight in shop (yellow) if bank < this. Red if < 1/5 of this.",
        section     = shopSection,
        position    = 2
    )
    default int natureRuneThreshold()
    {
        return 1000;
    }

    @ConfigItem(
        keyName     = "rosewoodPlankThreshold",
        name        = "Rosewood Planks (Sailing)",
        description = "Highlight in shop if Sailing >= 40 and bank < this.",
        section     = shopSection,
        position    = 3
    )
    default int rosewoodPlankThreshold()
    {
        return 200;
    }

    // ─── Bank Warnings ────────────────────────────────────────────────────────

    @ConfigSection(
        name        = "Bank Warnings",
        description = "Herblore secondary-ingredient warning settings",
        position    = 2
    )
    String warningsSection = "warnings";

    @ConfigItem(
        keyName     = "herbloreSecondaryWarning",
        name        = "Herblore Secondary Warning",
        description = "Warn when you have herbs but are missing the matching secondary ingredient",
        section     = warningsSection,
        position    = 0
    )
    default boolean herbloreSecondaryWarning()
    {
        return true;
    }

    // ─── Sailing ──────────────────────────────────────────────────────────────

    @ConfigSection(
        name        = "Sailing",
        description = "Manual Sailing level until RuneLite tracks the skill natively",
        position    = 3
    )
    String sailingSection = "sailing";

    @ConfigItem(
        keyName     = "sailingLevel",
        name        = "Sailing Level",
        description = "Your current Sailing level (used for Rosewood Plank threshold and Barracuda Trials tip)",
        section     = sailingSection,
        position    = 0
    )
    @Range(min = 1, max = 99)
    default int sailingLevel()
    {
        return 1;
    }

    // ─── Guide Checklist ──────────────────────────────────────────────────────

    @ConfigSection(
        name        = "Guide Checklist",
        description = "Ironman progression checklist and farm run timer state",
        position    = 4
    )
    String guideSection = "guide";

    @ConfigItem(
        keyName     = "completedMilestones",
        name        = "Completed Guide Steps",
        description = "Comma-separated list of manually completed milestone IDs (managed by the panel checkboxes)",
        section     = guideSection,
        position    = 0
    )
    default String completedMilestones()
    {
        return "";
    }

    @ConfigItem(
        keyName     = "farmRunStartTime",
        name        = "Farm Timer Start (epoch ms)",
        description = "Set by the 'Start Farm Timer' button. 0 = no timer running.",
        section     = guideSection,
        position    = 1
    )
    default long farmRunStartTime()
    {
        return 0L;
    }

    // ─── Support ──────────────────────────────────────────────────────────────

    @ConfigItem(
        keyName     = "supportLink",
        name        = "Support the Developer",
        description = "Join the Discord or Patreon!",
        position    = 99
    )
    default String supportLink() { return "patreon.com/SoloIronman"; }
}