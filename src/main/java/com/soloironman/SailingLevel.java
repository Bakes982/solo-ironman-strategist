package com.soloironman;

import net.runelite.api.Client;

/**
 * Resolves the player's Sailing level.
 *
 * <p>Sailing is a real tracked skill as of RuneLite 1.12.x
 * ({@code net.runelite.api.Skill.SAILING}), so the level is read straight from
 * the client. The manual {@link SoloIronmanConfig#sailingLevel()} value is only
 * used as a fallback for two cases:</p>
 *
 * <ol>
 *   <li>The client is not logged in yet — {@code getRealSkillLevel} returns 0.</li>
 *   <li>The plugin is running against an older RuneLite whose {@code Skill} enum
 *       has no {@code SAILING} constant — {@code valueOf} throws.</li>
 * </ol>
 *
 * <p>{@code Skill.SAILING} is resolved via {@code valueOf} rather than a direct
 * constant reference so the plugin still compiles and loads on those older
 * clients instead of failing with a {@code NoSuchFieldError}.</p>
 */
final class SailingLevel
{
    private SailingLevel()
    {
    }

    /**
     * @return the live Sailing level, or the configured manual level when the
     *         client cannot supply one. Never less than 1.
     */
    static int of(Client client, SoloIronmanConfig config)
    {
        if (client != null)
        {
            try
            {
                int level = client.getRealSkillLevel(net.runelite.api.Skill.valueOf("SAILING"));
                if (level > 0)
                {
                    return level;
                }
            }
            catch (IllegalArgumentException e)
            {
                // Skill.SAILING missing on this client — fall through to config.
            }
        }
        return config != null ? Math.max(1, config.sailingLevel()) : 1;
    }
}
