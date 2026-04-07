package com.soloironman;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Entry point for the Solo Ironman Strategist plugin.
 *
 * <p>Architecture (Phase 1 refactor):
 * <ul>
 *   <li>This class owns the plugin lifecycle and all EventBus subscriptions.</li>
 *   <li>{@link SoloIronmanPanel} is the thin UI orchestrator; its four tab panels
 *       ({@code GuideTabPanel}, {@code BankTabPanel}, {@code SkillsTabPanel},
 *       {@code TipsTabPanel}) are constructed and held by the panel.</li>
 *   <li>All data services ({@link BankScanner}, {@link ProgressionService}, etc.)
 *       are Guice singletons — never instantiate them manually.</li>
 * </ul>
 *
 * <p>Threading contract:
 * <ul>
 *   <li>EventBus subscribers run on the <b>game/client thread</b>.</li>
 *   <li>Every UI mutation <b>must</b> be wrapped in
 *       {@code SwingUtilities.invokeLater()} before touching Swing components.
 *       Methods on {@link SoloIronmanPanel} that already do this internally are
 *       documented as "EDT-safe".</li>
 * </ul>
 */
@Slf4j
@PluginDescriptor(
        name        = "Solo Ironman Strategist",
        description = "2026 meta intelligence for Solo Ironmen — milestone tracker, supply forecast, death timer, bank analysis, Slayer task, Hunter Rumours, daily tasks, money making, Varlamore & Sailing",
        tags        = {"ironman", "solo", "strategy", "progression", "milestone", "supply", "herblore", "fletching",
                       "sailing", "varlamore", "slayer", "rumour", "hunter", "farming", "death", "tracker",
                       "daily", "alch", "bank", "efficiency", "tips", "money", "guide", "crafting", "construction"}
)
public class SoloIronmanPlugin extends Plugin
{
    // ── Core RuneLite deps ────────────────────────────────────────────────────
    @Inject private Client            client;
    @Inject private OverlayManager    overlayManager;
    @Inject private ClientToolbar     clientToolbar;
    @Inject private SoloIronmanConfig config;

    // ── Plugin services ───────────────────────────────────────────────────────
    @Inject private BankScanner        bankScanner;
    @Inject private MetaDataProvider   metaDataProvider;
    @Inject private SkillGuideProvider skillGuideProvider;
    @Inject private ShopOverlay        shopOverlay;
    @Inject private ActiveGrindOverlay activeGrindOverlay;

    // ── 2026 Meta services ────────────────────────────────────────────────────
    @Inject private ProgressionService  progressionService;
    @Inject private SupplyService       supplyService;
    @Inject private RumourService       rumourService;
    @Inject private GoalService         goalService;
    @Inject private DeathTrackerService deathTrackerService;

    // ── UI (Phase 1 orchestrator — will delegate to tab sub-panels) ───────────
    @Inject private SoloIronmanPanel panel;

    private NavigationButton navButton;

    /**
     * Set to {@code true} when {@link GameState#LOGGED_IN} fires.
     * Cleared (and refresh executed) on the very first {@link GameTick} after
     * login, by which time the RuneLite client has received the player's skill
     * levels, quest states, and VarPlayers from the server.
     *
     * <p>Calling {@code client.getRealSkillLevel()} directly inside
     * {@code onGameStateChanged(LOGGED_IN)} always returns 1 because the
     * server-to-client stat packet arrives one or more ticks later.
     * We wait 3 ticks to be safe — experience shows tick 1 sometimes still
     * returns 1 for skills not recently changed.</p>
     */
    private int initialRefreshCountdown = 0;

    /**
     * Snapshot of each skill's real level, updated by {@link #onStatChanged}.
     * Used to distinguish actual level-ups (panel refresh needed) from
     * mid-combat XP drops (panel refresh not needed).
     *
     * <p>The array is indexed by {@link Skill#ordinal()}.  Skills that are not
     * tracked by RuneLite (e.g. Sailing) remain at 0 and are ignored.</p>
     */
    private final int[] lastKnownLevels = new int[Skill.values().length];

    /**
     * The last milestone title returned by {@link ProgressionService#getNextMilestone()}.
     * When the value changes between ticks (e.g. quest completed, item obtained
     * via {@link ItemContainerChanged}), a checklist rebuild is triggered even if
     * no level-up occurred — so the panel stays in sync after quest completions.
     */
    private String lastMilestoneTitle = "";

    /**
     * Tracks whether bank data was available on the previous tick.
     * When this transitions false→true we trigger a forced bank-ready update
     * so the Guide tab reflects the scan even if the {@link ItemContainerChanged}
     * event was processed between ticks (e.g. re-login or world-hop).
     */
    private boolean lastBankAvailable = false;

    /**
     * Set to true when the bank widget loads (WidgetLoaded group 12).
     * Cleared on the next tick after a successful bank scan.
     * Used to retry scanning on the tick AFTER widget load, because
     * {@code client.getItemContainer(BANK)} can return null on the same
     * tick that {@code WidgetLoaded} fires — the container isn't ready yet.
     */
    private boolean bankWidgetJustOpened = false;

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void startUp()
    {
        metaDataProvider.loadMeta();
        skillGuideProvider.loadGuides();
        overlayManager.add(shopOverlay);
        overlayManager.add(activeGrindOverlay);

        navButton = NavigationButton.builder()
                .tooltip("Solo Ironman Strategist")
                .icon(buildIcon())
                .priority(6)
                .panel(panel)
                .build();
        clientToolbar.addNavigation(navButton);

        log.info("Solo Ironman Strategist started — 2026 meta active");
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(shopOverlay);
        overlayManager.remove(activeGrindOverlay);
        clientToolbar.removeNavigation(navButton);
        panel.stopFarmTimer();
        bankScanner.reset();
        navButton = null;

        log.info("Solo Ironman Strategist stopped");
    }

    // ─── Event subscriptions ──────────────────────────────────────────────────

    /**
     * Bank and equipment scan trigger.
     *
     * <p>Both BANK and EQUIPMENT container events are forwarded to
     * {@link BankScanner#onBankChanged} so that:
     * <ul>
     *   <li>Bank scans populate the supply / herblore / crafting caches.</li>
     *   <li>Equipment scans populate the equipment cache used by
     *       {@link BankScanner#hasInBankOrEquipped} for milestone auto-completion.</li>
     * </ul>
     *
     * <p>The bank display is only rebuilt on BANK events — equipment changes
     * don't affect the bank tab UI but do affect milestone checks.
     */
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        int containerId = event.getContainerId();
        int bankId  = InventoryID.BANK.getId();
        int equipId = InventoryID.EQUIPMENT.getId();

        if (containerId == bankId)
        {
            bankScanner.onBankChanged(event);
            panel.updateBankDisplay();
        }
        else if (containerId == equipId)
        {
            bankScanner.onBankChanged(event);
        }
    }

    /**
     * WidgetLoaded fires when the bank interface opens (group ID 12).
     * We set a flag here rather than scanning immediately because
     * {@code client.getItemContainer(InventoryID.BANK)} can return null
     * on the same tick that WidgetLoaded fires — the server item-container
     * packet arrives on the NEXT tick.
     */
    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (event.getGroupId() == 12)
        {
            bankWidgetJustOpened = true;
        }
    }

    /**
     * Fires on every XP gain (mid-combat hits, skill actions, etc.).
     * Only triggers a panel refresh when the player's <em>real level</em>
     * actually increases — avoids unnecessary Swing redraws on every XP drop.
     */
    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        Skill skill = event.getSkill();
        int ordinal = skill.ordinal();
        int newLevel = client.getRealSkillLevel(skill);

        if (newLevel != lastKnownLevels[ordinal])
        {
            lastKnownLevels[ordinal] = newLevel;
            log.debug("Level-up detected: {} → {}", skill.getName(), newLevel);
            panel.refresh(client, config.sailingLevel());
        }
    }

    /**
     * Fires when any actor dies.  We check whether the dying actor is the local
     * player and, if so, record the death location and timestamp so the Guide
     * tab can display a live item-despawn countdown.
     *
     * <p>Called on the game thread.  {@link GuideTabPanel#recordDeath} wraps
     * its Swing work in {@code invokeLater} internally.</p>
     *
     * <p><b>Location note:</b> the WorldPoint is captured at the moment
     * {@code ActorDeath} fires, which is the tile where the player died
     * (before any safe-death teleport or respawn).  For Wilderness / instanced
     * boss deaths the recorded coordinates may differ from the dropped-item
     * location — the card labels this as an estimate.</p>
     */
    @Subscribe
    public void onActorDeath(ActorDeath event)
    {
        if (event.getActor() != client.getLocalPlayer()) return;

        WorldPoint loc = client.getLocalPlayer().getWorldLocation();
        if (loc == null) return;

        log.debug("Local player died at ({}, {}) plane {}", loc.getX(), loc.getY(), loc.getPlane());
        panel.recordDeath(loc.getX(), loc.getY(), loc.getPlane());
    }

    /**
     * Config change: if the user adjusts Sailing Level or Active Goal in the
     * RuneLite settings panel, immediately refresh skills/tips/guide so the
     * change is reflected without requiring a re-login.
     */
    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!"soloironman".equals(event.getGroup())) return;
        if (client.getGameState() == GameState.LOGGED_IN)
        {
            panel.refresh(client, config.sailingLevel());
        }
    }

    /**
     * Handles login and logout state transitions.
     *
     * <ul>
     *   <li><b>LOGGED_IN</b> — populate the Skills, Tips, and Guide tabs immediately
     *       so the player sees relevant content from the first tick rather than
     *       waiting for the first level-up / XP drop to trigger {@link #onStatChanged}.</li>
     *   <li><b>LOGIN_SCREEN</b> — reset bank data and clear the bank panel.
     *       World hops are intentionally <em>not</em> cleared: the same character's
     *       bank is still valid across hops, and {@link ItemContainerChanged} will
     *       fire again when the player opens their bank post-hop.</li>
     * </ul>
     */
    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            // Wait 3 ticks before refreshing: tick 1 sometimes still returns
            // level 1 for skills not recently changed, because the server stat
            // packets can arrive across multiple ticks after LOGGED_IN fires.
            initialRefreshCountdown = 3;
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN)
        {
            initialRefreshCountdown = 0;
            lastBankAvailable = false;
            bankWidgetJustOpened = false;
            java.util.Arrays.fill(lastKnownLevels, 0);  // reset level cache for next login
            lastMilestoneTitle = "";                     // reset so first-tick change triggers rebuild
            bankScanner.reset();
            // Update panel to "Open bank to scan..." state on the EDT
            SwingUtilities.invokeLater(() -> panel.updateBankDisplay());
        }
    }

    /**
     * Every game tick (~600 ms): gather fresh progression/supply/rumour data
     * from the lightweight services and push it to the panel on the EDT.
     *
     * <p>All service calls here are O(1) map/VarPlayer lookups — no heavy
     * computation happens in this subscriber.</p>
     */
    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if (client.getGameState() != GameState.LOGGED_IN) return;

        // Deferred initial refresh: wait for the countdown so stat packets from
        // the server have had time to arrive (tick 1 sometimes still returns 1).
        if (initialRefreshCountdown > 0)
        {
            initialRefreshCountdown--;
            if (initialRefreshCountdown == 0)
            {
                panel.refresh(client, config.sailingLevel());

                // Seed lastMilestoneTitle so the change-detection below doesn't
                // immediately fire a second refresh on the very same tick.
                lastMilestoneTitle = progressionService.getNextMilestone();

                // Auto-select the skill relevant to the next milestone so the player
                // immediately sees useful training methods without touching the dropdown.
                MetaDataProvider.GuideMilestone next = progressionService.getNextMilestoneObject();
                if (next != null && next.triggerSkill != null && !next.triggerSkill.isEmpty())
                {
                    panel.autoSelectSkill(next.triggerSkill);
                    log.debug("Auto-selected skill: {} (next milestone: {})",
                            next.triggerSkill, next.title);
                }

                log.debug("Initial panel refresh complete (tick 3 post-login)");
            }
        }

        // ── Bank polling ──────────────────────────────────────────────────────
        // Try to scan on the tick after WidgetLoaded (flag), AND on the
        // ItemContainerChanged event (handled separately). Both paths call
        // scanBank() then updateBankDisplay() only on first successful scan.
        if (bankWidgetJustOpened || !bankScanner.isHasData())
        {
            ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
            if (bankContainer != null)
            {
                boolean wasAvailable = bankScanner.isHasData();
                bankScanner.scanBank(bankContainer);
                bankWidgetJustOpened = false;
                if (!wasAvailable)
                {
                    panel.updateBankDisplay();
                }
            }
        }

        // ── Skill-level snapshot (read on game thread, passed to EDT) ─────────
        // Reading getRealSkillLevel() on the EDT risks a stale value due to
        // memory visibility; reading here on the game thread and passing a copy
        // is safe and always reflects the latest server-confirmed levels.
        final int[] skillLevels = new int[Skill.values().length];
        final int[] skillXp     = new int[Skill.values().length];
        for (Skill s : Skill.values())
        {
            skillLevels[s.ordinal()] = client.getRealSkillLevel(s);
            skillXp[s.ordinal()]     = client.getSkillExperience(s);
        }
        panel.updateSkillLevels(client, skillLevels, config.sailingLevel());
        panel.updateSkillXp(skillXp);

        // ── Slayer task snapshot ───────────────────────────────────────────────
        // VarPlayer 386 = creature ID (0 = no task), VarPlayer 387 = kills remaining.
        // Raw game enum 693 (SLAYER_TASK_CREATURE) maps creature IDs → display names.
        // The Java constant was removed from RuneLite's EnumID class in 1.12+, so we
        // use the raw integer directly — the underlying game client enum still exists.
        final int slayerCreatureId = client.getVarpValue(386);
        final int slayerTaskSize   = client.getVarpValue(387);
        final String slayerTaskName;
        if (slayerCreatureId <= 0)
        {
            slayerTaskName = "";
        }
        else
        {
            net.runelite.api.EnumComposition slayerEnum = client.getEnum(693);
            String enumName = slayerEnum != null
                    ? slayerEnum.getStringValue(slayerCreatureId) : null;
            slayerTaskName = (enumName != null && !enumName.isEmpty())
                    ? enumName : "Task #" + slayerCreatureId;
        }
        panel.updateSlayerTask(slayerTaskName, slayerTaskSize);

        final String                   milestone      = progressionService.getNextMilestone();
        final int                      pots           = supplyService.getPrayerPotionForecast();
        final double                   hours          = supplyService.getHoursRemaining();
        final RumourService.RumourInfo rumourInfo     = rumourService.getCurrentTaskInfo();
        final String                   rumour         = rumourInfo.name; // kept for updatePanelData signature
        final String                   goalName       = config.currentGoal().getName();
        final boolean                  goalMet        = goalService.isGoalMet(config.currentGoal().getItemId());
        final boolean                  bankAvailable  = supplyService.isBankDataAvailable();

        // When bank data first becomes available (false → true), force a bank-tab
        // rebuild so the Guide tab's READY TO PROCESS card updates.
        if (bankAvailable && !lastBankAvailable)
        {
            log.debug("Bank data became available — forcing bank display update");
            panel.updateBankDisplay();
        }
        lastBankAvailable = bankAvailable;

        // Detect milestone changes caused by quest completion or item acquisition —
        // these don't fire onStatChanged, so we rebuild the checklist here instead.
        if (!milestone.equals(lastMilestoneTitle))
        {
            lastMilestoneTitle = milestone;
            log.debug("Milestone changed → '{}'; triggering checklist rebuild", milestone);
            panel.refresh(client, config.sailingLevel());
        }

        final RumourService.RumourInfo rumourInfoFinal = rumourInfo; // effectively final for lambda
        SwingUtilities.invokeLater(() ->
        {
            panel.updatePanelData(milestone, pots, hours, rumour, goalName, goalMet, bankAvailable);
            panel.updateRumourInfo(rumourInfoFinal);
        });
    }

    // ─── Config & Icon ────────────────────────────────────────────────────────

    @Provides
    SoloIronmanConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(SoloIronmanConfig.class);
    }

    /**
     * Procedurally renders the orange "S" nav-bar icon — no image file required.
     * RenderingHints are set for a crisp render at 16×16.
     */
    private BufferedImage buildIcon()
    {
        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D    g    = icon.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,        RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,   RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(ColorScheme.BRAND_ORANGE);
        g.fillRoundRect(0, 0, 16, 16, 4, 4);

        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.drawString("S", 4, 13);

        g.dispose();
        return icon;
    }
}
