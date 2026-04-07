package com.soloironman;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Provides the next progression milestone for the overlay and Guide tab.
 *
 * <p>Data source (in priority order):
 * <ol>
 *   <li>JSON milestones from {@link MetaDataProvider} — checked against the
 *       player's real quest state, equipped/banked items, and skill levels via
 *       {@link MetaDataProvider#isMilestoneAutoComplete}.</li>
 *   <li>Hard-coded fallback — only used if the JSON failed to load.</li>
 * </ol>
 *
 * <p>Thread safety: all methods are O(1) map/VarPlayer reads called from the
 * game/client thread (overlay render, {@code onGameTick}).  No Swing access.
 */
@Slf4j
@Singleton
public class ProgressionService
{
    private static final String CONFIG_GROUP  = "soloironman";
    private static final String KEY_COMPLETED = "completedMilestones";

    @Inject private Client            client;
    @Inject private MetaDataProvider  metaDataProvider;
    @Inject private BankScanner       bankScanner;
    @Inject private SoloIronmanConfig config;
    @Inject private ConfigManager     configManager;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the display title of the next incomplete progression milestone.
     * The list is sorted by {@code priority} (ascending) in the JSON — lower
     * numbers are shown first (most urgent).
     *
     * <p>A milestone is considered complete when ANY of these is true:
     * <ul>
     *   <li>The player has manually checked it off (stored in ConfigManager).</li>
     *   <li>{@link MetaDataProvider#isMilestoneAutoComplete} returns {@code true}
     *       (quest finished, item obtained, or skill level reached).</li>
     * </ul>
     */
    public String getNextMilestone()
    {
        MetaDataProvider.GuideMilestone next = getNextMilestoneObject();
        if (next != null) return next.title;

        // Either all done, or JSON is empty
        List<MetaDataProvider.GuideMilestone> all = metaDataProvider.getAllMilestones();
        if (!all.isEmpty()) return "All milestones complete!";

        // JSON didn't load — hard-coded fallback
        return hardcodedFallback();
    }

    /**
     * Returns the full {@link MetaDataProvider.GuideMilestone} for the next
     * incomplete step, or {@code null} when everything is complete.
     *
     * <p>Used by the Skills tab to auto-select the relevant skill and by the
     * Guide tab to highlight the most urgent focus area.
     */
    public MetaDataProvider.GuideMilestone getNextMilestoneObject()
    {
        if (client == null) return null;

        List<MetaDataProvider.GuideMilestone> all = metaDataProvider.getAllMilestones();
        if (all.isEmpty()) return null;

        Set<String> manualDone  = getManualCompleted();
        int         sailingLevel = config != null ? config.sailingLevel() : 1;

        for (MetaDataProvider.GuideMilestone m : all)
        {
            if (manualDone.contains(m.id)) continue;
            if (metaDataProvider.isMilestoneAutoComplete(m, client, sailingLevel, bankScanner)) continue;
            return m;
        }
        return null; // all complete
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Set<String> getManualCompleted()
    {
        if (configManager == null) return Collections.emptySet();
        String stored = configManager.getConfiguration(CONFIG_GROUP, KEY_COMPLETED);
        if (stored == null || stored.trim().isEmpty()) return new HashSet<>();
        return new HashSet<>(Arrays.asList(stored.split(",")));
    }

    /**
     * Hard-coded fallback shown only when the JSON milestone list failed to
     * load (should never happen in normal operation).
     */
    private String hardcodedFallback()
    {
        if (client == null) return "Loading...";
        try
        {
            int ranged = client.getRealSkillLevel(Skill.RANGED);
            int slayer = client.getRealSkillLevel(Skill.SLAYER);
            if (ranged < 70) return "Grind Moons of Peril (Atlatl)";
            if (slayer < 75) return "Slayer for Synapses (Scorching Bow)";
            if (ranged >= 85) return "Doom Delves (Ayak & Confliction)";
            return "Complete 'Twilight's Promise'";
        }
        catch (Exception e)
        {
            return "Loading...";
        }
    }
}
