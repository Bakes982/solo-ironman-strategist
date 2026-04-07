package com.soloironman;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;

/**
 * On-screen HUD overlay for the Solo Ironman Strategist plugin.
 *
 * <p>Displays a compact information panel in the top-left corner (or wherever
 * the player drags it) showing:
 * <ul>
 *   <li><b>Next</b>   — the next progression milestone from {@link ProgressionService}</li>
 *   <li><b>Supply</b> — prayer pot count + hours remaining (turns red below 1 hour)</li>
 *   <li><b>Rumour</b> — active Hunter Rumour task from {@link RumourService}
 *                       (hidden when no task is active)</li>
 *   <li><b>Goal</b>   — current goal item from config, with ✓ when acquired</li>
 * </ul>
 *
 * <p>The overlay is hidden entirely when the player is not logged in.
 *
 * <p>Rendering happens on the <b>game/client thread</b> (called by RuneLite's
 * overlay manager each frame), so all data access here is thread-safe — the
 * service methods are O(1) VarPlayer / HashMap reads and do not touch Swing.
 */
@Singleton
public class ActiveGrindOverlay extends Overlay
{
    private static final Color C_TITLE  = ColorScheme.BRAND_ORANGE;
    private static final Color C_LABEL  = new Color(150, 150, 150);
    private static final Color C_WHITE  = Color.WHITE;
    private static final Color C_GREEN  = new Color( 74, 222, 128);
    private static final Color C_RED    = new Color(248, 113, 113);
    private static final Color C_AMBER  = new Color(255, 152,   0);
    private static final Color C_BLUE   = new Color(147, 197, 253);

    private final PanelComponent panelComponent = new PanelComponent();

    private final Client             client;
    private final SoloIronmanConfig  config;
    private final ProgressionService progressionService;
    private final SupplyService      supplyService;
    private final RumourService      rumourService;
    private final GoalService        goalService;

    @Inject
    public ActiveGrindOverlay(Client            client,
                               SoloIronmanConfig  config,
                               ProgressionService progressionService,
                               SupplyService      supplyService,
                               RumourService      rumourService,
                               GoalService        goalService)
    {
        this.client             = client;
        this.config             = config;
        this.progressionService = progressionService;
        this.supplyService      = supplyService;
        this.rumourService      = rumourService;
        this.goalService        = goalService;

        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(OverlayPriority.MED);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        // Only show when logged in
        if (client.getGameState() != GameState.LOGGED_IN) return null;

        panelComponent.getChildren().clear();

        // ── Title ─────────────────────────────────────────────────────────────
        panelComponent.getChildren().add(TitleComponent.builder()
                .text("Ironman Grind")
                .color(C_TITLE)
                .build());

        // ── Next milestone ────────────────────────────────────────────────────
        String milestone = progressionService.getNextMilestone();
        panelComponent.getChildren().add(LineComponent.builder()
                .left("Next:")
                .leftColor(C_LABEL)
                .right(milestone)
                .rightColor(C_WHITE)
                .build());

        // ── Supply forecast ───────────────────────────────────────────────────
        int    pots  = supplyService.getPrayerPotionForecast();
        double hours = supplyService.getHoursRemaining();
        final String supplyText;
        final Color  supplyColor;
        if (!supplyService.isBankDataAvailable())
        {
            supplyText  = "Open bank to calibrate";
            supplyColor = C_LABEL;
        }
        else
        {
            double warnThreshold = config.lowSupplyWarning();
            supplyColor = hours < warnThreshold ? C_RED
                        : hours < warnThreshold * 4.0 ? C_AMBER
                        : C_GREEN;
            supplyText  = pots + " pots  (" + String.format("%.1f", hours) + "h)";
        }
        panelComponent.getChildren().add(LineComponent.builder()
                .left("Supply:")
                .leftColor(C_LABEL)
                .right(supplyText)
                .rightColor(supplyColor)
                .build());

        // ── Active rumour (skip line when none) ───────────────────────────────
        String rumour = rumourService.getCurrentTask();
        if (rumour != null && !rumour.isEmpty() && !"None".equalsIgnoreCase(rumour))
        {
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Rumour:")
                    .leftColor(C_LABEL)
                    .right(rumour)
                    .rightColor(C_BLUE)
                    .build());
        }

        // ── Current goal ──────────────────────────────────────────────────────
        String  goalName = config.currentGoal().getName();
        boolean goalMet  = goalService.isGoalMet(config.currentGoal().getItemId());
        panelComponent.getChildren().add(LineComponent.builder()
                .left("Goal:")
                .leftColor(C_LABEL)
                .right(goalMet ? goalName + "  ✓" : goalName)
                .rightColor(goalMet ? C_GREEN : C_AMBER)
                .build());

        return panelComponent.render(graphics);
    }
}
