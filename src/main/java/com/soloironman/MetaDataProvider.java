package com.soloironman;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Loads {@code ironman_meta.json} from the classpath and provides
 * contextual efficiency tips and guide milestones gated by the player's
 * current skill levels.
 *
 * Usage: call {@link #loadMeta()} once in {@code startUp()}, then call
 * {@link #getActiveTips} and {@link #getAllMilestones} on relevant events.
 */
@Slf4j
@Singleton
public class MetaDataProvider
{
    // ─── Gson data model ──────────────────────────────────────────────────────

    /** Top-level JSON object. */
    static class IronmanMeta
    {
        @SerializedName("guide_milestones")
        List<GuideMilestone> guideMilestones;

        @SerializedName("efficiency_tips")
        List<EfficiencyTip> efficiencyTips;

        @SerializedName("herblore_efficiency")
        List<HerbloreEntry> herbloreEfficiency;

        @SerializedName("food_progression")
        List<FoodEntry> foodProgression;

        @SerializedName("fletching_essentials")
        List<FletchingEntry> fletchingEssentials;

        @SerializedName("shopscape_hotspots")
        List<ShopEntry> shopscapeHotspots;

        @SerializedName("sailing_meta")
        List<SailingEntry> sailingMeta;

        @SerializedName("money_making_methods")
        List<MoneyMakingMethod> moneyMakingMethods;
    }

    /**
     * A single step in the Ironman progression checklist.
     *
     * Auto-completion logic (any condition being true marks it done):
     *  1. trigger_quest  → RuneLite Quest enum name; marks done when QuestState.FINISHED
     *  2. trigger_item   → ItemID integer; marks done when item is in bank OR equipped
     *  3. trigger_skill  → Skill enum name + trigger_level; marks done when skill >= level
     */
    static class GuideMilestone
    {
        String id;
        String category;   // FOUNDATION | QUESTS | COMBAT | SKILLS | GEAR

        int priority;      // lower = shown first (most urgent)
        String title;

        /** RuneLite Quest enum name (e.g. "WATERFALL_QUEST"). Auto-detects quest completion. */
        @SerializedName("trigger_quest")
        String triggerQuest;

        /** ItemID integer. Auto-detects when item is in bank or equipped. 0 = unused. */
        @SerializedName("trigger_item")
        int triggerItem;

        /** Skill enum name (e.g. "ATTACK"). Used as fallback or primary skill gate. */
        @SerializedName("trigger_skill")
        String triggerSkill;

        /** Skill level required to auto-complete when using trigger_skill. */
        @SerializedName("trigger_level")
        int triggerLevel;

        String tip;
    }

    /** Skill-gated efficiency tip shown in the sidebar panel. */
    static class EfficiencyTip
    {
        @SerializedName("trigger_skill")
        String triggerSkill;

        @SerializedName("trigger_level")
        int triggerLevel;

        String tip;
    }

    static class HerbloreEntry
    {
        String herb;
        String secondary;

        @SerializedName("secondary_item_id")
        int secondaryItemId;

        String source;
        String tip;
    }

    static class FoodEntry
    {
        @SerializedName("level_range")
        String levelRange;

        String item;
        String location;
        String efficiency;
    }

    static class FletchingEntry
    {
        String item;

        @SerializedName("req_level")
        int reqLevel;

        String materials;
        String source;
        String tip;
    }

    static class ShopEntry
    {
        String item;
        String location;
        String usage;
        String tip;
    }

    static class SailingEntry
    {
        String activity;

        @SerializedName("req_level")
        int reqLevel;

        String tip;
    }

    /**
     * A money making method with multi-skill requirements, optional quest gates,
     * and a GP/hr estimate. Shown in the Tips tab only when all requirements are met.
     */
    static class MoneyMakingMethod
    {
        String id;
        String title;
        String category;

        /** Estimated GP per hour based on wiki averages. */
        @SerializedName("gp_hr")
        int gpHr;

        /**
         * Map of skill name → required level.
         * Keys are RuneLite Skill enum names (e.g. "RUNECRAFT", "THIEVING").
         * All entries must be satisfied for the method to show.
         */
        Map<String, Integer> reqs;

        /** RuneLite Quest enum names — all must be FINISHED for the method to show. */
        @SerializedName("req_quests")
        List<String> reqQuests;

        String tip;
    }

    // ─── Fields ───────────────────────────────────────────────────────────────

    private final Gson gson;
    private IronmanMeta meta;

    @Inject
    public MetaDataProvider(Gson gson)
    {
        this.gson = gson;
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Call once from {@code SoloIronmanPlugin#startUp()}.
     * Reads {@code /com/soloironman/ironman_meta.json} from the JAR classpath.
     */
    public void loadMeta()
    {
        try (InputStreamReader reader = new InputStreamReader(
            Objects.requireNonNull(
                getClass().getResourceAsStream("/com/soloironman/ironman_meta.json"),
                "ironman_meta.json not found in classpath"
            ),
            StandardCharsets.UTF_8
        ))
        {
            meta = gson.fromJson(reader, IronmanMeta.class);
            int tipCount  = meta.efficiencyTips  != null ? meta.efficiencyTips.size()  : 0;
            int stepCount = meta.guideMilestones  != null ? meta.guideMilestones.size() : 0;
            log.info("SIS: loaded {} efficiency tips and {} guide milestones", tipCount, stepCount);
        }
        catch (Exception e)
        {
            log.error("Failed to load ironman_meta.json", e);
            meta = new IronmanMeta();
        }
    }

    // ─── Guide milestones ─────────────────────────────────────────────────────

    /**
     * Returns all guide milestones sorted by priority (ascending).
     */
    public List<GuideMilestone> getAllMilestones()
    {
        if (meta == null || meta.guideMilestones == null)
        {
            return Collections.emptyList();
        }
        return meta.guideMilestones.stream()
            .sorted(Comparator.comparingInt(m -> m.priority))
            .collect(Collectors.toList());
    }

    /**
     * Returns {@code true} if the milestone should be considered auto-completed
     * based on the player's current state. Checks three signal types — any one
     * returning true is sufficient:
     *
     * <ol>
     *   <li><b>Quest</b> — {@code trigger_quest}: RuneLite Quest enum name.
     *       Completes when {@code QuestState.FINISHED}.</li>
     *   <li><b>Item</b>  — {@code trigger_item}: ItemID integer.
     *       Completes when the item is in the bank or equipped.</li>
     *   <li><b>Skill</b> — {@code trigger_skill} + {@code trigger_level}.
     *       Completes when the real skill level &ge; the required level.</li>
     * </ol>
     */
    public boolean isMilestoneAutoComplete(GuideMilestone m, Client client,
                                           int manualSailingLevel, BankScanner bankScanner)
    {
        if (client == null)
        {
            return false;
        }

        // 1. Quest completion check
        if (m.triggerQuest != null && !m.triggerQuest.isEmpty())
        {
            try
            {
                Quest quest = Quest.valueOf(m.triggerQuest.toUpperCase());
                if (quest.getState(client) == QuestState.FINISHED)
                {
                    return true;
                }
            }
            catch (IllegalArgumentException e)
            {
                log.warn("Unknown quest in guide_milestones: '{}' — falling back to skill check",
                    m.triggerQuest);
            }
        }

        // 2. Item presence check (bank or equipped)
        if (m.triggerItem > 0 && bankScanner != null
            && bankScanner.hasInBankOrEquipped(m.triggerItem))
        {
            return true;
        }

        // 3. Skill level check
        if (m.triggerSkill != null && !m.triggerSkill.isEmpty())
        {
            if ("SAILING".equalsIgnoreCase(m.triggerSkill))
            {
                return manualSailingLevel >= m.triggerLevel;
            }
            try
            {
                Skill skill = Skill.valueOf(m.triggerSkill.toUpperCase());
                return client.getRealSkillLevel(skill) >= m.triggerLevel;
            }
            catch (IllegalArgumentException e)
            {
                log.warn("Unknown skill in guide_milestones: '{}'", m.triggerSkill);
            }
        }

        return false;
    }

    // ─── Efficiency tips ──────────────────────────────────────────────────────

    /**
     * Returns tips applicable to the player's current skill levels as raw
     * {@link EfficiencyTip} objects. Prefer this over {@link #getActiveTips}
     * so callers can generate meaningful card titles from
     * {@code tip.triggerSkill} and {@code tip.triggerLevel} directly.
     *
     * <p>Tips with a "SAILING" trigger use {@code manualSailingLevel} because
     * Sailing may not yet be a tracked RuneLite skill.</p>
     */
    public List<EfficiencyTip> getActiveTipEntries(Client client, int manualSailingLevel)
    {
        if (meta == null || meta.efficiencyTips == null)
        {
            return Collections.emptyList();
        }

        List<EfficiencyTip> active = new ArrayList<>();
        for (EfficiencyTip tip : meta.efficiencyTips)
        {
            if (isTipActive(tip, client, manualSailingLevel))
            {
                active.add(tip);
            }
        }
        return active;
    }

    /**
     * Returns tips applicable to the player's current levels using a pre-computed
     * skill-level snapshot read on the game thread.  Prefer this over
     * {@link #getActiveTipEntries(Client, int)} to avoid EDT/game-thread visibility
     * issues when calling {@code client.getRealSkillLevel()} from Swing callbacks.
     *
     * @param skillLevels array indexed by {@link Skill#ordinal()} — produced by
     *                    {@code client.getRealSkillLevel()} on the game thread
     * @param sailingLevel manual Sailing level from config (Sailing is not a native skill)
     */
    public List<EfficiencyTip> getActiveTipEntries(int[] skillLevels, int sailingLevel)
    {
        if (meta == null || meta.efficiencyTips == null || skillLevels == null)
        {
            return Collections.emptyList();
        }

        List<EfficiencyTip> active = new ArrayList<>();
        for (EfficiencyTip tip : meta.efficiencyTips)
        {
            if (isTipActiveFromLevels(tip, skillLevels, sailingLevel))
            {
                active.add(tip);
            }
        }
        return active;
    }

    private boolean isTipActiveFromLevels(EfficiencyTip tip, int[] skillLevels, int sailingLevel)
    {
        if (tip.triggerSkill == null) return true;
        if ("SAILING".equalsIgnoreCase(tip.triggerSkill))
            return sailingLevel >= tip.triggerLevel;

        // Handle display-name vs enum-name discrepancies (e.g. RUNECRAFTING → RUNECRAFT)
        String skillName = tip.triggerSkill.toUpperCase();
        if ("RUNECRAFTING".equals(skillName)) skillName = "RUNECRAFT";
        try
        {
            Skill skill = Skill.valueOf(skillName);
            int level = skill.ordinal() < skillLevels.length ? skillLevels[skill.ordinal()] : 0;
            return level >= tip.triggerLevel;
        }
        catch (IllegalArgumentException e)
        {
            log.warn("Unknown skill in efficiency_tips: '{}'", tip.triggerSkill);
            return false;
        }
    }

    /**
     * Returns tips applicable to the player's current skill levels as
     * pre-formatted strings with a {@code [SKILL LEVEL+]} prefix.
     *
     * @deprecated Prefer {@link #getActiveTipEntries} — the prefix makes
     *             card title extraction brittle. Kept for compatibility.
     */
    @Deprecated
    public List<String> getActiveTips(Client client, int manualSailingLevel)
    {
        if (meta == null || meta.efficiencyTips == null)
        {
            return Collections.emptyList();
        }

        List<String> active = new ArrayList<>();
        for (EfficiencyTip tip : meta.efficiencyTips)
        {
            if (isTipActive(tip, client, manualSailingLevel))
            {
                active.add("[" + tip.triggerSkill + " " + tip.triggerLevel + "+] " + tip.tip);
            }
        }
        return active;
    }

    /** Returns herblore efficiency entries for display in the panel. */
    public List<HerbloreEntry> getHerbloreEntries()
    {
        if (meta == null || meta.herbloreEfficiency == null)
        {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(meta.herbloreEfficiency);
    }

    /** Returns ShopScape hotspot entries. */
    public List<ShopEntry> getShopHotspots()
    {
        if (meta == null || meta.shopscapeHotspots == null)
        {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(meta.shopscapeHotspots);
    }

    /**
     * Returns money making methods unlocked by the player's current skill levels and quests.
     * All skill requirements in {@code reqs} must be met, and all {@code req_quests} must be
     * FINISHED. Uses a pre-computed skill snapshot for thread safety.
     */
    public List<MoneyMakingMethod> getActiveMoneyMakingMethods(int[] skillLevels, int sailingLevel,
                                                                Client client)
    {
        if (meta == null || meta.moneyMakingMethods == null || skillLevels == null)
        {
            return Collections.emptyList();
        }

        List<MoneyMakingMethod> active = new ArrayList<>();
        for (MoneyMakingMethod m : meta.moneyMakingMethods)
        {
            if (isMoneyMethodUnlocked(m, skillLevels, sailingLevel, client))
            {
                active.add(m);
            }
        }
        // Highest GP/hr first so the best available method is always at the top
        active.sort((a, b) -> Integer.compare(b.gpHr, a.gpHr));
        return active;
    }

    private boolean isMoneyMethodUnlocked(MoneyMakingMethod m, int[] skillLevels,
                                           int sailingLevel, Client client)
    {
        // Check all skill requirements
        if (m.reqs != null)
        {
            for (Map.Entry<String, Integer> req : m.reqs.entrySet())
            {
                String skillName = req.getKey().toUpperCase();
                int required = req.getValue();

                if ("SAILING".equals(skillName))
                {
                    if (sailingLevel < required) return false;
                    continue;
                }
                if ("RUNECRAFTING".equals(skillName)) skillName = "RUNECRAFT";

                try
                {
                    Skill skill = Skill.valueOf(skillName);
                    int have = skill.ordinal() < skillLevels.length
                        ? skillLevels[skill.ordinal()] : 0;
                    if (have < required) return false;
                }
                catch (IllegalArgumentException e)
                {
                    log.warn("Unknown skill in money_making_methods reqs: '{}'", req.getKey());
                    return false;
                }
            }
        }

        // Check all quest requirements
        if (m.reqQuests != null && !m.reqQuests.isEmpty() && client != null)
        {
            for (String questName : m.reqQuests)
            {
                try
                {
                    Quest quest = Quest.valueOf(questName.toUpperCase());
                    if (quest.getState(client) != QuestState.FINISHED) return false;
                }
                catch (IllegalArgumentException e)
                {
                    log.warn("Unknown quest in money_making_methods req_quests: '{}'", questName);
                    return false;
                }
            }
        }

        return true;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private boolean isTipActive(EfficiencyTip tip, Client client, int manualSailingLevel)
    {
        if (tip.triggerSkill == null)
        {
            return true;
        }
        if ("SAILING".equalsIgnoreCase(tip.triggerSkill))
        {
            return manualSailingLevel >= tip.triggerLevel;
        }
        try
        {
            Skill skill = Skill.valueOf(tip.triggerSkill.toUpperCase());
            return client.getRealSkillLevel(skill) >= tip.triggerLevel;
        }
        catch (IllegalArgumentException e)
        {
            log.warn("Unknown skill in efficiency_tips: '{}'", tip.triggerSkill);
            return false;
        }
    }
}
