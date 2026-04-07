package com.soloironman;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Persists and exposes the player's last recorded death for the LAST DEATH
 * metric card on the Guide tab.
 *
 * <h3>What is stored</h3>
 * <ul>
 *   <li>World tile coordinates (x, y, plane)</li>
 *   <li>{@code System.currentTimeMillis()} timestamp at the moment of death</li>
 * </ul>
 * All values are written to {@link ConfigManager} immediately on
 * {@link #recordDeath} so they survive client restarts and world hops.
 *
 * <h3>Item despawn window</h3>
 * Standard OSRS rule: dropped items (safe PvE death) persist for 60 minutes.
 * Dangerous deaths (PvP, Wilderness) may be shorter.  This service always uses
 * the 60-minute window as a conservative estimate — the card clearly labels it
 * "likely expired" once the window closes so the player understands it is an
 * approximation.
 *
 * <h3>Threading</h3>
 * {@link #recordDeath} is called from the game thread (inside
 * {@code onActorDeath}).  All fields accessed by the EDT go through
 * {@link ConfigManager} which is thread-safe.  {@link DeathRecord} is an
 * immutable value object; snapshot it once and pass to the Swing timer.
 */
@Slf4j
@Singleton
public class DeathTrackerService
{
    private static final String CONFIG_GROUP   = "soloironman";
    private static final String KEY_DEATH_X    = "deathX";
    private static final String KEY_DEATH_Y    = "deathY";
    private static final String KEY_DEATH_PLANE = "deathPlane";
    private static final String KEY_DEATH_TS   = "deathTimestamp";

    /** Standard PvE item-protection window in milliseconds (60 minutes). */
    public static final long DESPAWN_MS = 60L * 60 * 1_000;

    private final ConfigManager configManager;

    @Inject
    public DeathTrackerService(ConfigManager configManager)
    {
        this.configManager = configManager;
    }

    // ─── Write ────────────────────────────────────────────────────────────────

    /**
     * Persist a death event.  Safe to call on the game thread.
     *
     * @param x         World tile X coordinate at time of death
     * @param y         World tile Y coordinate at time of death
     * @param plane     World plane (0 = surface, 1 = first floor, etc.)
     * @param timestamp {@code System.currentTimeMillis()} at the death event
     */
    public void recordDeath(int x, int y, int plane, long timestamp)
    {
        configManager.setConfiguration(CONFIG_GROUP, KEY_DEATH_X,     String.valueOf(x));
        configManager.setConfiguration(CONFIG_GROUP, KEY_DEATH_Y,     String.valueOf(y));
        configManager.setConfiguration(CONFIG_GROUP, KEY_DEATH_PLANE, String.valueOf(plane));
        configManager.setConfiguration(CONFIG_GROUP, KEY_DEATH_TS,    String.valueOf(timestamp));
        log.info("SIS: Death recorded at ({}, {}) plane {} at ts {}", x, y, plane, timestamp);
    }

    /** Clear the stored death record (e.g. when the player manually resets). */
    public void clearDeath()
    {
        configManager.unsetConfiguration(CONFIG_GROUP, KEY_DEATH_X);
        configManager.unsetConfiguration(CONFIG_GROUP, KEY_DEATH_Y);
        configManager.unsetConfiguration(CONFIG_GROUP, KEY_DEATH_PLANE);
        configManager.unsetConfiguration(CONFIG_GROUP, KEY_DEATH_TS);
    }

    // ─── Read ─────────────────────────────────────────────────────────────────

    /**
     * Load the last death from persisted config.
     *
     * @return a {@link DeathRecord} snapshot, or {@code null} if no death has
     *         ever been recorded (fresh install) or the record was cleared
     */
    public DeathRecord getLastDeath()
    {
        String xStr    = configManager.getConfiguration(CONFIG_GROUP, KEY_DEATH_X);
        String yStr    = configManager.getConfiguration(CONFIG_GROUP, KEY_DEATH_Y);
        String planeStr = configManager.getConfiguration(CONFIG_GROUP, KEY_DEATH_PLANE);
        String tsStr   = configManager.getConfiguration(CONFIG_GROUP, KEY_DEATH_TS);

        if (xStr == null || tsStr == null) return null;

        try
        {
            return new DeathRecord(
                Integer.parseInt(xStr.trim()),
                Integer.parseInt(yStr  != null ? yStr.trim()   : "0"),
                Integer.parseInt(planeStr != null ? planeStr.trim() : "0"),
                Long.parseLong(tsStr.trim())
            );
        }
        catch (NumberFormatException e)
        {
            log.warn("SIS: Could not parse death record from config", e);
            return null;
        }
    }

    // ─── DeathRecord inner class ──────────────────────────────────────────────

    /**
     * Immutable snapshot of a single death event.
     *
     * <p>All time-derived methods ({@link #isExpired()}, {@link #timerString()})
     * read {@code System.currentTimeMillis()} on every call so they always
     * reflect the current moment — call them from the Swing timer for a live
     * countdown without storing any mutable state.</p>
     */
    public static class DeathRecord
    {
        /** World tile coordinates at the moment of death. */
        public final int  x;
        public final int  y;
        /** World plane (0 = surface). */
        public final int  plane;
        /** {@code System.currentTimeMillis()} when the death was recorded. */
        public final long timestamp;

        DeathRecord(int x, int y, int plane, long timestamp)
        {
            this.x         = x;
            this.y         = y;
            this.plane     = plane;
            this.timestamp = timestamp;
        }

        /**
         * Human-readable location string.
         * Example: {@code "X:3245  Y:3422"} or {@code "X:2565  Y:3368 (p1)"}
         */
        public String locationString()
        {
            return "X:" + x + "  Y:" + y
                    + (plane > 0 ? " (floor " + plane + ")" : "");
        }

        /** Milliseconds remaining until the standard 60-minute despawn window closes. */
        public long msRemaining()
        {
            return Math.max(0L, DESPAWN_MS - (System.currentTimeMillis() - timestamp));
        }

        /** {@code true} when 60 minutes have elapsed since the recorded death. */
        public boolean isExpired()
        {
            return System.currentTimeMillis() - timestamp >= DESPAWN_MS;
        }

        /**
         * Compact countdown string for the metric card value.
         * Examples: {@code "Despawn in 46:23"} / {@code "Items likely expired"}
         */
        public String timerString()
        {
            if (isExpired()) return "Items likely expired";
            long secs = msRemaining() / 1_000;
            return String.format("Despawn in %d:%02d", secs / 60, secs % 60);
        }

        /**
         * Minutes elapsed since death (for the card subtitle age indicator).
         * Example: {@code "42 min ago"}
         */
        public String ageString()
        {
            long mins = (System.currentTimeMillis() - timestamp) / 60_000;
            return "Died " + (mins < 1 ? "< 1" : mins) + " min ago";
        }
    }
}
