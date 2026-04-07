package com.soloironman;

import net.runelite.api.Client;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Slim UI orchestrator for the Solo Ironman Strategist plugin.
 *
 * <p>This class owns exactly three things:
 * <ol>
 *   <li>The branded title bar.</li>
 *   <li>The {@link JTabbedPane} that hosts the four tab sub-panels.</li>
 *   <li>Thin delegation methods called by {@link SoloIronmanPlugin} on the EDT.</li>
 * </ol>
 *
 * <p>All UI construction, state, and business logic lives in the tab panels:
 * <ul>
 *   <li>{@link GuideTabPanel}  — Active grinds, progression checklist, farm timer</li>
 *   <li>{@link BankTabPanel}   — Herblore / Cooking / Fletching bank analysis</li>
 *   <li>{@link SkillsTabPanel} — Training method guide per skill</li>
 *   <li>{@link TipsTabPanel}   — 2026 meta efficiency tips</li>
 * </ul>
 *
 * <p>Threading: every method here either runs on the EDT already (called from
 * {@code onGameTick} via {@code invokeLater}) or delegates to panels that wrap
 * their own Swing work in {@code invokeLater} (e.g. {@link BankTabPanel#updateBankDisplay}).
 */
public class SoloIronmanPanel extends PluginPanel
{
    private static final Color C_MAIN_BG = ColorScheme.DARK_GRAY_COLOR;
    private static final Color C_CARD_BG = ColorScheme.DARKER_GRAY_COLOR;

    // ── Tab sub-panels (injected as Guice singletons) ─────────────────────────
    private final GuideTabPanel  guideTabPanel;
    private final BankTabPanel   bankTabPanel;
    private final SkillsTabPanel skillsTabPanel;
    private final TipsTabPanel   tipsTabPanel;

    @Inject
    public SoloIronmanPanel(GuideTabPanel  guideTabPanel,
                             BankTabPanel   bankTabPanel,
                             SkillsTabPanel skillsTabPanel,
                             TipsTabPanel   tipsTabPanel)
    {
        super(false); // false = don't apply default PluginPanel padding
        this.guideTabPanel  = guideTabPanel;
        this.bankTabPanel   = bankTabPanel;
        this.skillsTabPanel = skillsTabPanel;
        this.tipsTabPanel   = tipsTabPanel;
        buildUi();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UI construction
    // ─────────────────────────────────────────────────────────────────────────

    private void buildUi()
    {
        setLayout(new BorderLayout());
        setBackground(C_MAIN_BG);

        add(buildTitleBar(), BorderLayout.NORTH);
        add(buildTabPane(),  BorderLayout.CENTER);
    }

    private JPanel buildTitleBar()
    {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(C_CARD_BG);
        bar.setBorder(new EmptyBorder(10, 12, 10, 12));

        JLabel title = new JLabel("Solo Ironman Strategist");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(Color.WHITE);
        bar.add(title, BorderLayout.CENTER);

        // Subtle version badge (right-aligned)
        JLabel version = new JLabel("2026");
        version.setFont(FontManager.getRunescapeSmallFont());
        version.setForeground(new Color(100, 100, 100));
        bar.add(version, BorderLayout.EAST);

        return bar;
    }

    private JTabbedPane buildTabPane()
    {
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.setBackground(C_CARD_BG);
        tabs.setForeground(Color.WHITE);
        tabs.setFont(FontManager.getRunescapeSmallFont());
        tabs.setBorder(BorderFactory.createEmptyBorder());

        tabs.addTab("GUIDE",  scrollWrap(guideTabPanel));
        tabs.addTab("BANK",   scrollWrap(bankTabPanel));
        tabs.addTab("SKILLS", scrollWrap(skillsTabPanel));
        tabs.addTab("TIPS",   scrollWrap(tipsTabPanel));

        return tabs;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Delegation methods — called by SoloIronmanPlugin
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Push live game-tick data (milestone, supply, rumour, goal) to the Guide tab.
     * Called via {@code SwingUtilities.invokeLater} from {@link SoloIronmanPlugin#onGameTick}.
     *
     * @param bankAvailable {@code true} once the bank cache is populated (bank opened)
     */
    public void updatePanelData(String milestone, int pots, double hours,
                                String rumour, String goalName, boolean goalMet,
                                boolean bankAvailable)
    {
        guideTabPanel.updatePanelData(milestone, pots, hours, rumour, goalName, goalMet, bankAvailable);
    }

    /**
     * Trigger bank analysis UI rebuild after a bank container change.
     * {@link BankTabPanel} is EDT-safe and wraps its work in {@code invokeLater} internally,
     * so callers on the game thread are safe to call this directly.
     */
    public void updateBankDisplay()
    {
        bankTabPanel.updateBankDisplay();
        guideTabPanel.updateBankReady();   // keep Guide tab's "READY TO PROCESS" card in sync
    }

    /**
     * Record a player death: persist coordinates + timestamp via the Guide tab.
     * Called directly from {@link SoloIronmanPlugin#onActorDeath} on the game
     * thread — {@link GuideTabPanel#recordDeath} wraps its Swing work in
     * {@code invokeLater} internally.
     */
    public void recordDeath(int x, int y, int plane)
    {
        guideTabPanel.recordDeath(x, y, plane);
    }

    /**
     * Push rich Rumour task info (creature, trap type, teleport) to the Guide tab.
     * Calls {@link GuideTabPanel#updateRumourInfo} on the EDT.
     */
    public void updateRumourInfo(RumourService.RumourInfo info)
    {
        SwingUtilities.invokeLater(() -> guideTabPanel.updateRumourInfo(info));
    }

    /**
     * Push a pre-computed skill-level snapshot to the Skills and Tips tabs.
     * The {@code client} reference is forwarded to {@link TipsTabPanel} so that
     * money-making quest requirements can be evaluated correctly — without it the
     * quest checks in {@link MetaDataProvider#getActiveMoneyMakingMethods} would
     * silently skip every quest gate.
     *
     * <p>Levels are read on the game thread by {@link SoloIronmanPlugin#onGameTick}
     * to avoid EDT/game-thread visibility races, then passed here via invokeLater.</p>
     */
    public void updateSkillLevels(Client client, int[] levels, int sailingLevel)
    {
        SwingUtilities.invokeLater(() ->
        {
            skillsTabPanel.updateLevels(levels, sailingLevel);
            tipsTabPanel.updateLevels(client, levels, sailingLevel);
        });
    }

    /**
     * Push the current Slayer task name and kill count to the Guide tab.
     * Call from the game thread — wraps Swing work in invokeLater internally.
     */
    public void updateSlayerTask(String taskName, int remaining)
    {
        SwingUtilities.invokeLater(() -> guideTabPanel.updateSlayerTask(taskName, remaining));
    }

    /**
     * Push a skill-XP snapshot to the Bank and Skills tabs.
     * Bank tab uses it for XP-projection summaries; Skills tab uses it for
     * the "XP to next level" display.  XP values are read on the game thread.
     */
    public void updateSkillXp(int[] xpValues)
    {
        bankTabPanel.updateSkillXp(xpValues);
        skillsTabPanel.updateSkillXp(xpValues);
    }

    /**
     * Full panel refresh on stat change (level-up / XP drop).
     * Wraps all tab refreshes in {@code invokeLater} so this is safe to call
     * from the game thread (e.g. from {@link SoloIronmanPlugin#onStatChanged}).
     */
    public void refresh(Client client, int sailingLevel)
    {
        SwingUtilities.invokeLater(() ->
        {
            guideTabPanel.refresh(client, sailingLevel);
            skillsTabPanel.refresh(client, sailingLevel);
            tipsTabPanel.refresh(client, sailingLevel);
        });
    }

    /**
     * Auto-select a skill in the Skills tab by name (case-insensitive).
     * Called on login to pre-select the skill relevant to the next milestone.
     */
    public void autoSelectSkill(String skillName)
    {
        SwingUtilities.invokeLater(() -> skillsTabPanel.selectSkill(skillName));
    }

    /** Called by {@link SoloIronmanPlugin#shutDown()} to stop the farm timer thread. */
    public void stopFarmTimer()
    {
        guideTabPanel.stopFarmTimer();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Wraps a tab panel in a scroll pane with a thin (8 px) scrollbar and
     * no horizontal scroll — cards must fill the panel width.
     *
     * <p>The tab panel is placed inside a {@link ScrollablePanel} whose
     * {@code getScrollableTracksViewportWidth()} returns {@code true}. This
     * forces the {@link JScrollPane} viewport to constrain the content width
     * to the visible area, preventing {@link javax.swing.JTextArea} (used by
     * {@link StrategistCard}'s expand area) from reporting a wider preferred
     * size than the viewport and causing horizontal overflow.</p>
     */
    private JScrollPane scrollWrap(JPanel panel)
    {
        // Outer ScrollablePanel tells the JScrollPane to track viewport width
        ScrollablePanel wrapper = new ScrollablePanel(new BorderLayout());
        wrapper.setBackground(C_MAIN_BG);
        // NORTH anchors content to top so it doesn't stretch vertically
        wrapper.add(panel, BorderLayout.NORTH);

        JScrollPane sp = new JScrollPane(wrapper,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        sp.getViewport().setBackground(C_MAIN_BG);
        return sp;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ScrollablePanel inner class
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A JPanel that implements {@link Scrollable} and reports
     * {@code getScrollableTracksViewportWidth() = true}.
     *
     * <p>When used as the direct child of a {@link JScrollPane} viewport,
     * this forces all descendant components to stay within the viewport width
     * rather than expanding it.  Without this, {@code JTextArea.getPreferredSize()}
     * returns the full unwrapped text width regardless of
     * {@code setLineWrap(true)}, which makes {@code BoxLayout(Y_AXIS)} parents
     * request more width than the viewport and create a horizontal scroll bar.</p>
     */
    private static class ScrollablePanel extends JPanel implements Scrollable
    {
        ScrollablePanel(LayoutManager layout)
        {
            super(layout);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int orientation, int direction)
        {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int orientation, int direction)
        {
            return 64;
        }

        /** Constrain content width to the viewport — this is the critical fix. */
        @Override
        public boolean getScrollableTracksViewportWidth()  { return true;  }

        /** Allow vertical scroll — don't stretch content to viewport height. */
        @Override
        public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
