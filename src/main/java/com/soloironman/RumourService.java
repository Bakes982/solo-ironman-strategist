package com.soloironman;

import javax.inject.Inject;
import net.runelite.api.Client;

/**
 * Reads the player's active Hunter Rumour task from VarPlayer 4120.
 *
 * <p>Returns a {@link RumourInfo} with the creature name, recommended trap
 * type, and best teleport.  Returns {@link RumourInfo#NONE} when no Rumour
 * is active so the HUD card shows "None active" cleanly.
 */
public class RumourService
{
    @Inject private Client client;

    // ─────────────────────────────────────────────────────────────────────────
    //  RumourInfo inner class
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Snapshot of the current Hunter Rumour assignment.
     *
     * <ul>
     *   <li>{@code name}     — display name of the creature (e.g. "Moonlight Moth")</li>
     *   <li>{@code trapType} — recommended trap to set (e.g. "Net Trap", "Box Trap")</li>
     *   <li>{@code teleport} — fastest travel to the hunting spot</li>
     * </ul>
     *
     * Use {@link #isEmpty()} to test whether no Rumour is active.
     */
    public static class RumourInfo
    {
        public static final RumourInfo NONE = new RumourInfo("", "", "");

        public final String name;
        public final String trapType;
        public final String teleport;

        public RumourInfo(String name, String trapType, String teleport)
        {
            this.name     = name;
            this.trapType = trapType;
            this.teleport = teleport;
        }

        /** {@code true} when no Rumour is currently assigned. */
        public boolean isEmpty() { return name == null || name.isEmpty(); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns full setup info for the current Rumour task, or
     * {@link RumourInfo#NONE} when no Rumour is active.
     */
    public RumourInfo getCurrentTaskInfo()
    {
        int rumourId = client.getVarpValue(4120);
        switch (rumourId)
        {
            // ── Varlamore creatures ───────────────────────────────────────────
            case  1: return new RumourInfo(
                    "Sunlight Antelope",
                    "Pitfall Trap",
                    "Quetzal → Civitas illa Fortis");
            case  2: return new RumourInfo(
                    "Moonlight Antelope",
                    "Pitfall Trap",
                    "Quetzal → Fortis (south path)");
            case  3: return new RumourInfo(
                    "Sunlight Moth",
                    "Net Trap",
                    "Quetzal → Civitas illa Fortis");
            case  4: return new RumourInfo(
                    "Moonlight Moth",
                    "Net Trap",
                    "Quetzal → Fortis (moonlit grounds)");
            case  5: return new RumourInfo(
                    "Spindlefrog",
                    "Box Trap",
                    "Quetzal → Hunter Guild");
            case  6: return new RumourInfo(
                    "White Rabbit",
                    "Snare",
                    "Quetzal → Hunter Guild meadow");
            case  7: return new RumourInfo(
                    "Varlamore Pigeon",
                    "Deadfall Trap",
                    "Quetzal → Civitas rooftops");
            // ── Chin / Red Chin variants ──────────────────────────────────────
            case  8: return new RumourInfo(
                    "Red Chinchompas",
                    "Box Trap",
                    "Fairy Ring AKS or Feldip Hills tele");
            case  9: return new RumourInfo(
                    "Black Chinchompas",
                    "Box Trap",
                    "Wilderness Lever → Edge → level 32 Wildy");
            // ── Classic creatures ─────────────────────────────────────────────
            case 10: return new RumourInfo(
                    "Herbiboar",
                    "No trap — track footprints",
                    "Fairy Ring AJR → Fossil Island");
            case 11: return new RumourInfo(
                    "Maniacal Monkey",
                    "Banana bait (bare hands)",
                    "Teleport to Ape Atoll (after MM2)");
            case 12: return new RumourInfo(
                    "Toxic Lizard",
                    "Box Trap",
                    "Fairy Ring DLQ → Desert Lizards area");
            case 13: return new RumourInfo(
                    "Swamp Lizard",
                    "Rope + Small Fishing Net",
                    "Canifis lodestone → Morytania swamp");
            case 14: return new RumourInfo(
                    "Carnivorous Chinchompa",
                    "Box Trap",
                    "Slayer ring → Feldip Hills");
            case 15: return new RumourInfo(
                    "Kebbits",
                    "Deadfall Trap",
                    "Fairy Ring AKQ or run from Rellekka");
            // ── Empty — no active Rumour ──────────────────────────────────────
            default: return RumourInfo.NONE;
        }
    }

    /**
     * Convenience method for the overlay — returns the creature name only,
     * or an empty string when no Rumour is active.
     */
    public String getCurrentTask()
    {
        return getCurrentTaskInfo().name;
    }

    /** Returns the recommended travel method to reach the Hunter Guild. */
    public String getBestTravel()
    {
        return "Quetzal Whistle → Guild";
    }
}
