package com.soloironman;

import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;

/**
 * Checks whether the player's current goal item has been obtained.
 *
 * <p>Checks three sources, in order:
 * <ol>
 *   <li><b>Equipment cache</b> — updated on every equip change; works even before
 *       the bank has been opened.</li>
 *   <li><b>Bank cache</b> — populated after the first bank scan;
 *       {@link BankScanner#isHasData()} guards this check.</li>
 *   <li><b>Inventory</b> — always accessible from the client when logged in.</li>
 * </ol>
 */
public class GoalService
{
    @Inject private Client      client;
    @Inject private BankScanner bankScanner;

    /**
     * Returns {@code true} if the given item is in the bank cache, equipped,
     * or in the player's current inventory.
     */
    public boolean isGoalMet(int itemId)
    {
        // Equipment: always tracked (fires on every equip change, no bank-open required)
        if (bankScanner.hasEquipped(itemId)) return true;

        // Bank: only populated after the bank interface has been opened at least once
        if (bankScanner.isHasData() && bankScanner.hasItem(itemId)) return true;

        // Inventory: always accessible from the client when logged in
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv == null) return false;
        for (Item item : inv.getItems())
        {
            if (item.getId() == itemId) return true;
        }
        return false;
    }
}
