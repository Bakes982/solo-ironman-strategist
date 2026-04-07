package com.soloironman;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Loads skill_guides.json and provides query methods used by {@link SoloIronmanPanel}
 * to power the SKILL LOOKUP section.
 */
@Slf4j
@Singleton
public class SkillGuideProvider
{
    // ─── JSON data model ──────────────────────────────────────────────────────

    static class SkillGuideRoot
    {
        List<SkillEntry> skills;

        @SerializedName("herblore_potions")
        List<HerblorePotion> herblorePotions;
    }

    static class SkillEntry
    {
        String skill;           // e.g. "AGILITY"

        @SerializedName("display_name")
        String displayName;     // e.g. "Agility"

        List<TrainingMethod> methods;
    }

    static class TrainingMethod
    {
        @SerializedName("min_level")
        int minLevel;

        @SerializedName("max_level")
        int maxLevel;

        String name;

        @SerializedName("xp_per_hour")
        int xpPerHour;

        String location;
        String tip;
    }

    static class HerblorePotion
    {
        int level;
        String name;
        String herb;
        String secondary;
        double xp;

        @SerializedName("secondary_source")
        String secondarySource;

        String use;
    }

    // ─── State ────────────────────────────────────────────────────────────────

    private final Gson gson;
    private SkillGuideRoot data;

    @Inject
    public SkillGuideProvider(Gson gson)
    {
        this.gson = gson;
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    public void loadGuides()
    {
        try (InputStreamReader reader = new InputStreamReader(
            Objects.requireNonNull(
                getClass().getResourceAsStream("/com/soloironman/skill_guides.json"),
                "skill_guides.json not found in classpath"
            ),
            StandardCharsets.UTF_8
        ))
        {
            data = gson.fromJson(reader, SkillGuideRoot.class);
            int skillCount  = data.skills        != null ? data.skills.size()        : 0;
            int potionCount = data.herblorePotions != null ? data.herblorePotions.size() : 0;
            log.info("SIS SkillGuides: {} skill guides, {} herblore potions loaded",
                skillCount, potionCount);
        }
        catch (Exception e)
        {
            log.error("Failed to load skill_guides.json", e);
            data = new SkillGuideRoot();
        }
    }

    // ─── Skill query methods ──────────────────────────────────────────────────

    /** All display names in JSON order (used to populate the JComboBox). */
    public List<String> getAllSkillDisplayNames()
    {
        if (data == null)
        {
            loadGuides();
        }
        if (data == null || data.skills == null)
        {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (SkillEntry entry : data.skills)
        {
            names.add(entry.displayName);
        }
        return names;
    }

    /**
     * The highest training method the player is eligible for
     * (i.e. the last method where minLevel <= playerLevel).
     * Returns null if no methods are loaded or the player is below level 1.
     */
    public TrainingMethod getBestMethod(String displayName, int playerLevel)
    {
        SkillEntry entry = findByDisplayName(displayName);
        if (entry == null || entry.methods == null)
        {
            return null;
        }
        TrainingMethod best = null;
        for (TrainingMethod m : entry.methods)
        {
            if (playerLevel >= m.minLevel)
            {
                best = m;
            }
        }
        return best;
    }

    /**
     * The next method the player will unlock (first method where minLevel > playerLevel).
     * Returns null if the player already has access to all methods.
     */
    public TrainingMethod getNextMethod(String displayName, int playerLevel)
    {
        SkillEntry entry = findByDisplayName(displayName);
        if (entry == null || entry.methods == null)
        {
            return null;
        }
        for (TrainingMethod m : entry.methods)
        {
            if (m.minLevel > playerLevel)
            {
                return m;
            }
        }
        return null;
    }

    // ─── Herblore potion query methods ────────────────────────────────────────

    /**
     * The highest potion the player can make at their current Herblore level.
     */
    public HerblorePotion getBestPotion(int herbloreLevel)
    {
        if (data == null || data.herblorePotions == null)
        {
            return null;
        }
        HerblorePotion best = null;
        for (HerblorePotion p : data.herblorePotions)
        {
            if (herbloreLevel >= p.level)
            {
                best = p;
            }
        }
        return best;
    }

    /**
     * The next potion unlock above the player's current Herblore level.
     * Returns null if the player already has access to all potions.
     */
    public HerblorePotion getNextPotion(int herbloreLevel)
    {
        if (data == null || data.herblorePotions == null)
        {
            return null;
        }
        for (HerblorePotion p : data.herblorePotions)
        {
            if (p.level > herbloreLevel)
            {
                return p;
            }
        }
        return null;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private SkillEntry findByDisplayName(String displayName)
    {
        if (data == null || data.skills == null || displayName == null)
        {
            return null;
        }
        for (SkillEntry entry : data.skills)
        {
            if (displayName.equalsIgnoreCase(entry.displayName))
            {
                return entry;
            }
        }
        return null;
    }
}
