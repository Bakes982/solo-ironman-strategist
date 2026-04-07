package com.soloironman;

import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.List;

/**
 * GUIDE tab — the home screen of the plugin.
 *
 * <p>Sections:
 * <ol>
 *   <li><b>Active Grinds & Forecast</b> — three live-updating {@link StrategistCard}s
 *       driven by the game tick ({@link #updatePanelData}).</li>
 *   <li><b>Progression Guide</b> — expandable checklist cards with inline
 *       checkboxes for manual completion, auto-completed by quest/stat/item triggers.</li>
 *   <li><b>Farm Run Timer</b> — a 80-minute countdown persisted in
 *       {@link ConfigManager} so it survives client restarts.</li>
 * </ol>
 *
 * <p>Layout contract: root is {@link BorderLayout} with {@link BorderLayout#NORTH}
 * holding the cards column. This anchors content to the top and fills the full
 * panel width inside the parent scroll pane — no card squishing.
 */
@Singleton
public class GuideTabPanel extends JPanel
{
    // ── Colours ───────────────────────────────────────────────────────────────
    private static final Color C_MAIN_BG = ColorScheme.DARK_GRAY_COLOR;
    private static final Color C_CARD_BG = ColorScheme.DARKER_GRAY_COLOR;
    private static final Color C_GREEN   = new Color( 74, 222, 128);
    private static final Color C_RED     = new Color(248, 113, 113);
    private static final Color C_AMBER   = new Color(255, 152,   0);
    private static final Color C_DIM     = new Color(150, 150, 150);
    private static final Color C_YELLOW  = new Color(253, 230, 138);

    // ── Farm timer constants ───────────────────────────────────────────────────
    private static final int    HERB_GROW_MS   = 80 * 60 * 1_000;
    private static final String CONFIG_GROUP   = "soloironman";
    private static final String KEY_COMPLETED  = "completedMilestones";
    private static final String KEY_FARM_START = "farmRunStartTime";

    // ── Daily tasks config key prefix ─────────────────────────────────────────
    // Value stored: "YYYY-MM-DD" (UTC). If the stored date equals today the task
    // is considered done; otherwise it is reset and shown as unchecked.
    private static final String KEY_DAILY_PREFIX = "daily_";

    /** Ordered list of daily tasks shown in the DAILY TASKS section. */
    private static final String[][] DAILY_TASKS = {
            // { id,                  display title,                 expand tip }
            { "battlestaves",
              "Buy Battlestaves (Zaff)",
              "Buy 64 Battlestaves from Zaff in Varrock (requires Varrock Hard Diary). " +
              "Without the diary you can still buy 10 from Naff in Burthorpe. " +
              "High Alch or keep for your own Crafting training — they're one of the " +
              "best passive daily GP sources for an Ironman." },
            { "birdhouses",
              "Birdhouse Run",
              "Check and reset Birdhouses on Fossil Island (requires Bone Voyage quest). " +
              "Each run takes under 3 minutes and passively produces Hunter XP and " +
              "bird nests with herb and tree seeds. Reset them every ~50 minutes — " +
              "at minimum do one run per login." },
            { "herb_run",
              "Herb Run (Ranarrs + Contracts)",
              "Plant Ranarrs in ALL disease-free patches (Troll Stronghold + Weiss always, " +
              "then Hosidius, Ardougne, Falador, Catherby). Always complete a Farming " +
              "Contract at the Guild per run for bonus seeds + extra XP." },
            { "kingdom",
              "Collect Kingdom Resources",
              "Check Miscellania (Kingdom of Miscellania quest) and collect any " +
              "accumulated resources. Keep your approval at 100% by doing daily tasks " +
              "at Miscellania (woodcutting/mining/fishing). Assign workers to Maples " +
              "and Coal for the best Ironman resource return." },
    };

    // ── Live metric cards (refs retained for updateValue() calls) ─────────────
    private final StrategistCard progressionCard =
            StrategistCard.metric("NEXT MILESTONE",  "Waiting...", StrategistCard.Category.FOUNDATION);
    private final StrategistCard supplyCard =
            StrategistCard.metric("SUPPLY FORECAST", "Waiting...", StrategistCard.Category.SKILLING);
    private final StrategistCard rumourCard =
            StrategistCard.metric("ACTIVE RUMOUR",   "Waiting...", StrategistCard.Category.GEAR);

    // Subtitle panel injected once into rumourCard via addSubtitle().
    // Labels are kept as fields so text can be updated live each tick.
    private final JLabel rumourTrapLabel  = makeSubLabel("");
    private final JLabel rumourTeleLabel  = makeSubLabel("");
    private       boolean rumourSubtitleAdded = false;

    private final StrategistCard goalCard =
            StrategistCard.metric("ACTIVE GOAL",     "Waiting...", StrategistCard.Category.COMBAT);
    private final StrategistCard slayerCard =
            StrategistCard.metric("SLAYER TASK",     "Waiting...", StrategistCard.Category.COMBAT);
    private final StrategistCard bankReadyCard =
            StrategistCard.metric("READY TO PROCESS","Open bank to scan", StrategistCard.Category.SKILLING);
    private final StrategistCard deathCard =
            StrategistCard.metric("LAST DEATH",       "No death recorded", StrategistCard.Category.COMBAT);

    // Death card subtitle labels — injected once via addSubtitle()
    private final JLabel deathLocationLabel = makeSubLabel("");
    private final JLabel deathAgeLabel      = makeSubLabel("");
    private       boolean deathSubtitleAdded = false;

    // ── Dynamic checklist ─────────────────────────────────────────────────────
    private final JPanel checklistPanel;

    // ── Daily tasks panel ─────────────────────────────────────────────────────
    private final JPanel dailyTasksPanel;

    // ── Farm timer ────────────────────────────────────────────────────────────
    private JLabel  farmTimerLabel;
    private Timer   farmSwingTimer;
    private boolean farmTimerWasActive = false;

    // ── Daily reset tracking ───────────────────────────────────────────────────
    // Stored as "YYYY-MM-DD". When getTodayUtc() differs from this value the
    // daily tasks panel is rebuilt so completed tasks auto-reset at midnight UTC.
    private String lastKnownDate = "";

    // ── State ─────────────────────────────────────────────────────────────────
    private Set<String> cachedAutoCompleted = new HashSet<>();

    // ── Dependencies ──────────────────────────────────────────────────────────
    private final MetaDataProvider    metaDataProvider;
    private final ConfigManager       configManager;
    private final BankScanner         bankScanner;
    private final DeathTrackerService deathTrackerService;
    private final SoloIronmanConfig   config;

    @Inject
    public GuideTabPanel(MetaDataProvider    metaDataProvider,
                         ConfigManager       configManager,
                         BankScanner         bankScanner,
                         DeathTrackerService deathTrackerService,
                         SoloIronmanConfig   config)
    {
        super();
        this.metaDataProvider    = metaDataProvider;
        this.configManager       = configManager;
        this.bankScanner         = bankScanner;
        this.deathTrackerService = deathTrackerService;
        this.config              = config;

        checklistPanel = new JPanel();
        checklistPanel.setLayout(new BoxLayout(checklistPanel, BoxLayout.Y_AXIS));
        checklistPanel.setBackground(C_MAIN_BG);
        checklistPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        dailyTasksPanel = new JPanel();
        dailyTasksPanel.setLayout(new BoxLayout(dailyTasksPanel, BoxLayout.Y_AXIS));
        dailyTasksPanel.setBackground(C_MAIN_BG);
        dailyTasksPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        lastKnownDate = getTodayUtc();

        buildUi();
        startFarmSwingTimer();
        buildDailyTasksUi();  // populate after buildUi() so panel is in the hierarchy
    }

    private void buildUi()
    {
        setLayout(new BorderLayout());
        setBackground(C_MAIN_BG);

        // Cards column: BoxLayout for vertical stacking.
        // Anchored to BorderLayout.NORTH so it fills width without centring.
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBackground(C_MAIN_BG);
        col.setBorder(new EmptyBorder(6, 8, 8, 8));

        // ── Section 1: Active Grinds ──────────────────────────────────────────
        col.add(sectionLabel("ACTIVE GRINDS & FORECAST"));
        col.add(progressionCard);
        col.add(vgap(4));
        col.add(supplyCard);
        col.add(vgap(4));
        col.add(rumourCard);
        col.add(vgap(4));
        col.add(goalCard);
        col.add(vgap(4));
        col.add(slayerCard);
        col.add(vgap(4));
        col.add(bankReadyCard);
        col.add(vgap(4));
        col.add(deathCard);

        col.add(divider());

        // ── Section 2: Progression Checklist ─────────────────────────────────
        col.add(sectionLabel("PROGRESSION GUIDE"));
        col.add(checklistPanel);

        col.add(divider());

        // ── Section 3: Daily Tasks ────────────────────────────────────────────
        col.add(sectionLabel("DAILY TASKS"));
        col.add(dailyTasksPanel);

        col.add(divider());

        // ── Section 4: Farm Timer ─────────────────────────────────────────────
        col.add(sectionLabel("FARM RUN TIMER"));
        col.add(buildFarmTimerCard());

        // Anchor to NORTH: card column snaps to top, spans full viewport width
        add(col, BorderLayout.NORTH);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public update API  (called from SoloIronmanPanel which is already on EDT)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Push live game-tick data to the five metric cards.
     * Must be called from the EDT.
     *
     * @param bankAvailable {@code true} once the bank has been opened and the
     *                      cache is populated; {@code false} on first login before
     *                      the player opens their bank.
     */
    public void updatePanelData(String milestone, int pots, double hours,
                                String rumour, String goalName, boolean goalMet,
                                boolean bankAvailable)
    {
        progressionCard.updateValue(milestone);

        if (!bankAvailable)
        {
            supplyCard.updateValue("Open bank to calibrate", C_DIM);
            bankReadyCard.updateValue("Open bank to scan", C_DIM);
        }
        else
        {
            String supplyText = pots + " pots  ·  " + String.format("%.1f", hours) + " hrs";
            double warn = config != null ? config.lowSupplyWarning() : 1.0;
            Color  supplyColor = hours < warn ? C_RED : hours < warn * 4.0 ? C_AMBER : C_GREEN;
            supplyCard.updateValue(supplyText, supplyColor);
        }

        // Rumour card is driven by updateRumourInfo() — not updated here.

        if (goalMet)
        {
            goalCard.updateValue(goalName + "  ✓", C_GREEN);
        }
        else
        {
            goalCard.updateValue(goalName, C_YELLOW);
        }
    }

    /**
     * Full refresh on stat change.
     * Recalculates auto-completed milestones and rebuilds the checklist.
     * Must be called from the EDT.
     */
    public void refresh(Client client, int sailingLevel)
    {
        Set<String> tempAuto = new HashSet<>();
        if (client != null)
        {
            for (MetaDataProvider.GuideMilestone m : metaDataProvider.getAllMilestones())
            {
                if (metaDataProvider.isMilestoneAutoComplete(m, client, sailingLevel, bankScanner))
                    tempAuto.add(m.id);
            }
        }
        this.cachedAutoCompleted = tempAuto;
        updateChecklist();
    }

    /** Stop the farm timer Swing.Timer on plugin shutdown. */
    public void stopFarmTimer()
    {
        if (farmSwingTimer != null) farmSwingTimer.stop();
    }

    /**
     * Updates the READY TO PROCESS card with the highest-priority action
     * derivable from the bank cache.  Safe to call from the game thread —
     * wraps all Swing work in {@code invokeLater} internally.
     *
     * <p>Priority order (first non-empty wins):
     * <ol>
     *   <li>Herblore — process paired herbs + secondaries into potions</li>
     *   <li>Fletching — fletch broad arrows (free XP from Slayer shop)</li>
     *   <li>Cooking  — cook the highest-XP raw fish</li>
     *   <li>Crafting — cut the highest-value uncut gems</li>
     *   <li>Battlestaves — add orb to battlestaff (daily Zaff/Naff supply)</li>
     *   <li>Construction — build with highest-tier planks</li>
     *   <li>Prayer — offer bones at Chaos Altar</li>
     *   <li>Prayer — reanimate ensouled heads (Arceuus)</li>
     * </ol>
     */
    public void updateBankReady()
    {
        SwingUtilities.invokeLater(() ->
        {
            if (!bankScanner.isHasData())
            {
                bankReadyCard.updateValue("Open bank to scan", C_DIM);
                return;
            }

            // 1. Herblore — paired herbs + secondaries
            List<BankScanner.CraftEntry> herb = bankScanner.getHerbloreBreakdown();
            if (!herb.isEmpty())
            {
                BankScanner.CraftEntry top = herb.get(0);
                bankReadyCard.updateValue("Make " + top.count + "× " + top.name, C_GREEN);
                return;
            }

            // 2. Fletching — free XP from Slayer supplies
            List<BankScanner.CraftEntry> fletch = bankScanner.getFletchingBreakdown();
            if (!fletch.isEmpty())
            {
                BankScanner.CraftEntry top = fletch.get(0);
                bankReadyCard.updateValue("Fletch " + top.count + "× " + top.name, C_GREEN);
                return;
            }

            // 3. Cooking — process raw fish
            List<BankScanner.CraftEntry> cook = bankScanner.getCookingBreakdown();
            if (!cook.isEmpty())
            {
                BankScanner.CraftEntry top = cook.get(0);
                bankReadyCard.updateValue("Cook " + top.count + "× " + top.name, C_YELLOW);
                return;
            }

            // 4. Crafting gems
            List<BankScanner.CraftEntry> gems = bankScanner.getCraftingGemBreakdown();
            if (!gems.isEmpty())
            {
                BankScanner.CraftEntry top = gems.get(0);
                bankReadyCard.updateValue("Cut " + top.count + "× " + top.name, C_YELLOW);
                return;
            }

            // 5. Battlestaves — daily Zaff/Naff staves + charged orbs
            List<BankScanner.CraftEntry> staves = bankScanner.getBattlestaffBreakdown();
            if (!staves.isEmpty())
            {
                BankScanner.CraftEntry top = staves.get(0);
                bankReadyCard.updateValue("Craft " + top.count + "× " + top.name, C_GREEN);
                return;
            }

            // 6. Construction planks
            List<BankScanner.CraftEntry> planks = bankScanner.getConstructionBreakdown();
            if (!planks.isEmpty())
            {
                BankScanner.CraftEntry top = planks.get(0);
                bankReadyCard.updateValue("Build with " + top.count + "× " + top.name, C_YELLOW);
                return;
            }

            // 7. Prayer bones (Chaos Altar) or ensouled heads (Arceuus)
            List<BankScanner.CraftEntry> bones = bankScanner.getPrayerBonesBreakdown();
            if (!bones.isEmpty())
            {
                BankScanner.CraftEntry top = bones.get(0);
                bankReadyCard.updateValue("Altar: " + top.count + "× " + top.name, C_YELLOW);
                return;
            }
            List<BankScanner.CraftEntry> heads = bankScanner.getEnsouledHeadBreakdown();
            if (!heads.isEmpty())
            {
                BankScanner.CraftEntry top = heads.get(0);
                bankReadyCard.updateValue("Reanimate: " + top.count + "× " + top.name, C_YELLOW);
                return;
            }

            // No processable items — check for supply-blocking shortfalls before declaring clean
            int snapeShortfall = bankScanner.getSnapeGrassShortfall();
            if (snapeShortfall > 0)
            {
                bankReadyCard.updateValue("Need " + snapeShortfall + " more Snape Grass", C_RED);
                return;
            }
            if (bankScanner.hasMissingEyeOfNewt())
            {
                bankReadyCard.updateValue("Need Eye of Newt (" + bankScanner.getIritCount() + " Irit waiting)", C_RED);
                return;
            }

            bankReadyCard.updateValue("Nothing pending  ✓", C_DIM);
        });
    }

    /**
     * Push rich Rumour data (creature name, trap type, teleport) to the
     * ACTIVE RUMOUR card.  The trap and teleport appear as a two-line subtitle
     * below the creature name so the player always knows what to pack and where
     * to go without leaving the plugin panel.
     *
     * <p>Must be called from the EDT.</p>
     *
     * @param info {@link RumourService.RumourInfo} — use
     *             {@link RumourService.RumourInfo#NONE} when nothing is assigned.
     */
    public void updateRumourInfo(RumourService.RumourInfo info)
    {
        if (info == null || info.isEmpty())
        {
            rumourCard.updateValue("None active", C_DIM);
            rumourTrapLabel.setText("");
            rumourTeleLabel.setText("");
        }
        else
        {
            rumourCard.updateValue(info.name, C_GREEN);
            rumourTrapLabel.setText("⚒ " + info.trapType);
            rumourTeleLabel.setText("⚑ " + info.teleport);
        }

        // Inject the subtitle panel exactly once; after that just update labels.
        if (!rumourSubtitleAdded)
        {
            JPanel sub = new JPanel();
            sub.setLayout(new BoxLayout(sub, BoxLayout.Y_AXIS));
            sub.setOpaque(false);
            sub.add(rumourTrapLabel);
            sub.add(rumourTeleLabel);
            rumourCard.addSubtitle(sub);
            rumourSubtitleAdded = true;
        }
    }

    /**
     * Update the SLAYER TASK card with the player's current assignment.
     * Must be called from the EDT.
     *
     * @param taskName  Display name of the creature (e.g. "Gargoyles"), or empty if no task.
     * @param remaining Kill count remaining on the task.
     */
    public void updateSlayerTask(String taskName, int remaining)
    {
        if (taskName == null || taskName.isEmpty() || remaining <= 0)
        {
            slayerCard.updateValue("No active task", C_DIM);
        }
        else
        {
            slayerCard.updateValue(taskName + "  ×" + remaining, C_RED);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Checklist
    // ─────────────────────────────────────────────────────────────────────────

    private void updateChecklist()
    {
        checklistPanel.removeAll();

        List<MetaDataProvider.GuideMilestone> all = metaDataProvider.getAllMilestones();
        if (all.isEmpty())
        {
            checklistPanel.revalidate();
            checklistPanel.repaint();
            return;
        }

        Set<String> manualDone = getManualCompleted();
        List<MetaDataProvider.GuideMilestone> incomplete = new ArrayList<>();
        for (MetaDataProvider.GuideMilestone m : all)
        {
            if (!cachedAutoCompleted.contains(m.id) && !manualDone.contains(m.id))
                incomplete.add(m);
        }

        if (incomplete.isEmpty())
        {
            JLabel done = dimLabel("All milestones complete — well done!");
            checklistPanel.add(done);
        }
        else
        {
            int shown = Math.min(5, incomplete.size());
            for (int i = 0; i < shown; i++)
            {
                MetaDataProvider.GuideMilestone m = incomplete.get(i);

                JCheckBox cb = new JCheckBox();
                cb.setOpaque(false);
                cb.setFocusPainted(false);
                cb.addActionListener(e -> onMilestoneToggled(m.id, cb.isSelected()));

                StrategistCard card = StrategistCard.milestone(
                        m.title, m.tip, categoryFor(m.category), cb);

                // Auto-expand the top priority item so the tip is immediately visible
                if (i == 0) card.setExpanded(true);

                checklistPanel.add(card);
                checklistPanel.add(vgap(4));
            }

            // Show count of hidden milestones so player knows there's more to come
            int hidden = incomplete.size() - shown;
            if (hidden > 0)
            {
                checklistPanel.add(vgap(2));
                checklistPanel.add(dimLabel("+ " + hidden + " more milestone" + (hidden == 1 ? "" : "s") + " — complete the above to unlock"));
            }
        }

        checklistPanel.revalidate();
        checklistPanel.repaint();
    }

    private void onMilestoneToggled(String id, boolean completed)
    {
        Set<String> current = getManualCompleted();
        if (completed) current.add(id);
        else           current.remove(id);

        configManager.setConfiguration(CONFIG_GROUP, KEY_COMPLETED,
                current.isEmpty() ? "" : String.join(",", current));

        SwingUtilities.invokeLater(() -> {
            updateChecklist();
            checklistPanel.revalidate();
            checklistPanel.repaint();
        });
    }

    private Set<String> getManualCompleted()
    {
        String stored = configManager.getConfiguration(CONFIG_GROUP, KEY_COMPLETED);
        if (stored == null || stored.trim().isEmpty()) return new HashSet<>();
        return new HashSet<>(Arrays.asList(stored.split(",")));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Farm Timer
    // ─────────────────────────────────────────────────────────────────────────

    private JPanel buildFarmTimerCard()
    {
        // Use a regular JPanel styled to match cards — not a StrategistCard
        // because the timer has buttons that don't fit the metric card pattern.
        JPanel card = new JPanel(new BorderLayout(12, 0));
        card.setBackground(C_CARD_BG);
        card.setBorder(new EmptyBorder(12, 14, 12, 12));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));

        // Left: label stack
        JPanel stack = new JPanel();
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.setOpaque(false);

        JLabel titleLbl = new JLabel("HERB RUN TIMER");
        titleLbl.setFont(FontManager.getRunescapeSmallFont());
        titleLbl.setForeground(C_DIM);

        farmTimerLabel = new JLabel("No timer running");
        farmTimerLabel.setFont(FontManager.getRunescapeBoldFont());
        farmTimerLabel.setForeground(C_DIM);

        stack.add(titleLbl);
        stack.add(Box.createRigidArea(new Dimension(0, 3)));
        stack.add(farmTimerLabel);

        card.add(stack, BorderLayout.CENTER);

        // Right: action buttons
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btns.setOpaque(false);

        JButton startBtn = styledButton("Start 80m", new Color(22, 101, 52));
        startBtn.addActionListener(e -> {
            configManager.setConfiguration(CONFIG_GROUP, KEY_FARM_START,
                    String.valueOf(System.currentTimeMillis()));
            updateFarmTimerLabel();
        });

        JButton clearBtn = styledButton("Clear", new Color(55, 65, 81));
        clearBtn.addActionListener(e -> {
            configManager.setConfiguration(CONFIG_GROUP, KEY_FARM_START, "");
            updateFarmTimerLabel();
        });

        btns.add(startBtn);
        btns.add(clearBtn);
        card.add(btns, BorderLayout.EAST);

        return card;
    }

    private void startFarmSwingTimer()
    {
        if (farmSwingTimer != null) farmSwingTimer.stop();
        farmSwingTimer = new Timer(1_000, e -> {
            updateFarmTimerLabel();
            checkDailyReset();
            refreshDeathCard();
        });
        farmSwingTimer.start();
    }

    /**
     * Called every second by the farm timer. Detects when the UTC date has
     * rolled over to a new day and rebuilds the daily tasks UI so completed
     * tasks auto-reset at midnight without requiring a client restart.
     */
    private void checkDailyReset()
    {
        String today = getTodayUtc();
        if (!today.equals(lastKnownDate))
        {
            lastKnownDate = today;
            buildDailyTasksUi();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Death tracker
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called once from {@link SoloIronmanPlugin#onActorDeath} (game thread) to
     * persist a new death record and immediately refresh the card on the EDT.
     *
     * @param x     World tile X at death
     * @param y     World tile Y at death
     * @param plane World plane at death
     */
    public void recordDeath(int x, int y, int plane)
    {
        deathTrackerService.recordDeath(x, y, plane, System.currentTimeMillis());
        SwingUtilities.invokeLater(this::refreshDeathCard);
    }

    /**
     * Reads the current death record from {@link DeathTrackerService} and
     * updates the LAST DEATH card.  Called every second by the farm Swing.Timer
     * so the despawn countdown ticks live without any game-thread involvement.
     *
     * <p>Runs on the EDT (timer fires on EDT by design).</p>
     */
    private void refreshDeathCard()
    {
        DeathTrackerService.DeathRecord rec = deathTrackerService.getLastDeath();

        // Inject subtitle labels once (same pattern as rumourCard subtitle)
        if (!deathSubtitleAdded)
        {
            JPanel sub = new JPanel();
            sub.setLayout(new BoxLayout(sub, BoxLayout.Y_AXIS));
            sub.setOpaque(false);
            sub.add(deathLocationLabel);
            sub.add(deathAgeLabel);
            deathCard.addSubtitle(sub);
            deathSubtitleAdded = true;
        }

        if (rec == null)
        {
            deathCard.updateValue("No death recorded", C_DIM);
            deathLocationLabel.setText("");
            deathAgeLabel.setText("");
        }
        else if (rec.isExpired())
        {
            deathCard.updateValue("Items likely expired", C_DIM);
            deathLocationLabel.setText(rec.locationString());
            deathAgeLabel.setText(rec.ageString());
        }
        else
        {
            // Live countdown — colour shifts amber → red as the window closes
            long minsLeft = rec.msRemaining() / 60_000;
            Color timerColor = minsLeft <= 10 ? C_RED : C_AMBER;
            deathCard.updateValue(rec.timerString(), timerColor);
            deathLocationLabel.setText(rec.locationString());
            deathAgeLabel.setText(rec.ageString());
        }
    }

    private void updateFarmTimerLabel()
    {
        if (farmTimerLabel == null) return;

        String stored = configManager.getConfiguration(CONFIG_GROUP, KEY_FARM_START);
        if (stored == null || stored.trim().isEmpty() || "0".equals(stored.trim()))
        {
            farmTimerWasActive = false;
            farmTimerLabel.setText("No timer running");
            farmTimerLabel.setForeground(C_DIM);
            return;
        }

        long startTime;
        try { startTime = Long.parseLong(stored.trim()); }
        catch (NumberFormatException ex) { farmTimerWasActive = false; return; }

        long remaining = HERB_GROW_MS - (System.currentTimeMillis() - startTime);
        if (remaining <= 0)
        {
            if (farmTimerWasActive) Toolkit.getDefaultToolkit().beep();
            farmTimerWasActive = false;
            farmTimerLabel.setText("HERBS READY!  ✓");
            farmTimerLabel.setForeground(C_GREEN);
        }
        else
        {
            farmTimerWasActive = true;
            long mins = remaining / 60_000L;
            long secs = (remaining % 60_000L) / 1_000L;
            farmTimerLabel.setText(String.format("%dm %02ds remaining", mins, secs));
            farmTimerLabel.setForeground(C_YELLOW);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Daily Tasks
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds (or rebuilds) the DAILY TASKS card list.
     *
     * <p>Called once from the constructor after {@code buildUi()}, and again
     * from the farm-timer Swing.Timer at midnight (when today's UTC date
     * changes) so completed tasks auto-reset overnight.</p>
     *
     * <p>Each task is an expandable {@link StrategistCard} with an inline
     * {@link JCheckBox}.  Checking a box persists today's UTC date in
     * ConfigManager under {@code "daily_<id>"}.  A stored date that differs
     * from today is treated as "not done" so the box resets automatically.</p>
     */
    private void buildDailyTasksUi()
    {
        dailyTasksPanel.removeAll();
        String today = getTodayUtc();

        for (String[] task : DAILY_TASKS)
        {
            final String id      = task[0];
            final String title   = task[1];
            final String tipText = task[2];
            final boolean done   = isDailyDone(id, today);

            JCheckBox cb = new JCheckBox();
            cb.setOpaque(false);
            cb.setFocusPainted(false);
            cb.setSelected(done);
            cb.addActionListener(e -> onDailyToggled(id, cb.isSelected()));

            StrategistCard card = StrategistCard.milestone(title, tipText,
                    StrategistCard.Category.FOUNDATION, cb);

            if (done)
            {
                // Dim the value label to show the task is completed today
                card.updateValue("✓ Done today", C_DIM);
            }

            dailyTasksPanel.add(card);
            dailyTasksPanel.add(vgap(4));
        }

        // Footer: reset notice
        JLabel note = dimLabel("Tasks reset at UTC midnight");
        note.setBorder(new EmptyBorder(4, 4, 0, 0));
        dailyTasksPanel.add(note);

        dailyTasksPanel.revalidate();
        dailyTasksPanel.repaint();
    }

    /** @return {@code true} if the task with the given id was completed today (UTC). */
    private boolean isDailyDone(String id, String today)
    {
        String stored = configManager.getConfiguration(CONFIG_GROUP, KEY_DAILY_PREFIX + id);
        return today.equals(stored);
    }

    /** Called when the player toggles a daily task checkbox. */
    private void onDailyToggled(String id, boolean checked)
    {
        String value = checked ? getTodayUtc() : "";
        configManager.setConfiguration(CONFIG_GROUP, KEY_DAILY_PREFIX + id, value);
        SwingUtilities.invokeLater(this::buildDailyTasksUi);
    }

    /** Returns today's date in UTC as {@code "YYYY-MM-DD"}. */
    private static String getTodayUtc()
    {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private JLabel sectionLabel(String text)
    {
        JLabel l = new JLabel(text);
        l.setFont(FontManager.getRunescapeSmallFont());
        l.setForeground(C_DIM);
        l.setBorder(new EmptyBorder(12, 4, 6, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    /** Small dim label for rumour/subtitle rows. */
    private static JLabel makeSubLabel(String text)
    {
        JLabel l = new JLabel(text);
        l.setFont(FontManager.getRunescapeSmallFont());
        l.setForeground(new Color(150, 150, 150));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JLabel dimLabel(String text)
    {
        JLabel l = new JLabel(text);
        l.setFont(FontManager.getRunescapeSmallFont());
        l.setForeground(C_DIM);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JSeparator divider()
    {
        JSeparator s = new JSeparator();
        s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        s.setForeground(new Color(55, 65, 81));
        s.setBorder(new EmptyBorder(6, 0, 6, 0));
        return s;
    }

    private Component vgap(int px)
    {
        return Box.createRigidArea(new Dimension(0, px));
    }

    private JButton styledButton(String label, Color bg)
    {
        JButton b = new JButton(label);
        b.setFont(FontManager.getRunescapeSmallFont());
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setPreferredSize(new Dimension(80, 24));
        return b;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Utility
    // ─────────────────────────────────────────────────────────────────────────

    /** Map JSON category string → StrategistCard colour category. */
    private static StrategistCard.Category categoryFor(String raw)
    {
        if (raw == null) return StrategistCard.Category.FOUNDATION;
        switch (raw.toLowerCase())
        {
            case "combat":   return StrategistCard.Category.COMBAT;
            case "skilling": return StrategistCard.Category.SKILLING;
            case "gear":     return StrategistCard.Category.GEAR;
            default:         return StrategistCard.Category.FOUNDATION;
        }
    }
}
