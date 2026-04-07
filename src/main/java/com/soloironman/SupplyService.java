package com.soloironman;

import javax.inject.Inject;
import net.runelite.api.ItemID;

/**
 * Estimates the player's prayer-potion supply from the cached bank data.
 *
 * <p>Uses {@link BankScanner} (HashMap cache, always readable) instead of
 * {@code client.getItemContainer(InventoryID.BANK)}, which returns {@code null}
 * whenever the bank UI is closed — i.e. on every game tick during normal play.
 *
 * <p>Supply sources counted:
 * <ul>
 *   <li>Prayer Potion (4/3/2/1) — existing ready-made pots</li>
 *   <li>Ranarr Weed / Grimy Ranarr Weed — each makes 1 pot (3 doses)</li>
 *   <li>Ranarr Seed × 8 estimated herbs per seed</li>
 * </ul>
 */
public class SupplyService
{
    @Inject private BankScanner bankScanner;

    private static final int MINUTES_PER_DOSE = 3;

    /**
     * Returns the total number of prayer-potion equivalents in the bank.
     * Each ready-made pot (any dose), each ranarr herb, and each ranarr seed
     * (× 8 estimated herbs) counts as one pot for the forecast card.
     *
     * @return 0 if the bank cache has not yet been populated (bank never opened).
     */
    public int getPrayerPotionForecast()
    {
        if (!bankScanner.isHasData()) return 0;

        int total = 0;

        // Existing ready-made prayer potions (any dose = 1 pot for the counter)
        total += bankScanner.getCount(ItemID.PRAYER_POTION4);
        total += bankScanner.getCount(ItemID.PRAYER_POTION3);
        total += bankScanner.getCount(ItemID.PRAYER_POTION2);
        total += bankScanner.getCount(ItemID.PRAYER_POTION1);

        // Clean/grimy Ranarr weeds — each brews into 1 prayer pot
        total += bankScanner.getCount(ItemID.RANARR_WEED);
        total += bankScanner.getCount(ItemID.GRIMY_RANARR_WEED);

        // Ranarr seeds — ~8 herbs per seed on average
        total += bankScanner.getCount(ItemID.RANARR_SEED) * 8;

        return total;
    }

    /**
     * Returns the total prayer-potion <em>doses</em> available in the bank.
     * Doses are used to calculate hours remaining ({@link #getHoursRemaining}).
     *
     * @return 0 if the bank cache has not yet been populated.
     */
    public int getTotalPrayerDoses()
    {
        if (!bankScanner.isHasData()) return 0;

        int totalDoses = 0;

        // Ready-made potions — count actual doses
        totalDoses += bankScanner.getCount(ItemID.PRAYER_POTION4) * 4;
        totalDoses += bankScanner.getCount(ItemID.PRAYER_POTION3) * 3;
        totalDoses += bankScanner.getCount(ItemID.PRAYER_POTION2) * 2;
        totalDoses += bankScanner.getCount(ItemID.PRAYER_POTION1) * 1;

        // Ranarr weeds/grimy — each makes 1 pot (3 doses)
        totalDoses += bankScanner.getCount(ItemID.RANARR_WEED)       * 3;
        totalDoses += bankScanner.getCount(ItemID.GRIMY_RANARR_WEED) * 3;

        // Ranarr seeds — ~8 herbs × 3 doses each
        totalDoses += bankScanner.getCount(ItemID.RANARR_SEED) * 8 * 3;

        return totalDoses;
    }

    /**
     * Converts total doses to hours of prayer (3 min/dose assumption).
     */
    public double getHoursRemaining()
    {
        int totalMinutes = getTotalPrayerDoses() * MINUTES_PER_DOSE;
        return totalMinutes / 60.0;
    }

    /**
     * Returns {@code true} once the bank has been opened at least once this
     * session and the cache is populated.  Used by the overlay to show a
     * calibration hint instead of "0 pots" when the player hasn't opened their
     * bank yet.
     */
    public boolean isBankDataAvailable()
    {
        return bankScanner.isHasData();
    }
}
