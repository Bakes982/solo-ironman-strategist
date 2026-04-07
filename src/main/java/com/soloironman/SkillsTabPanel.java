package com.soloironman;

import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/**
 * SKILLS tab — training method guide for the player's current level.
 *
 * <p>The player selects a skill from the dropdown, and the panel shows:
 * <ul>
 *   <li>The best training method available at their current level (green card).</li>
 *   <li>The next training method they can unlock (blue card, if one exists).</li>
 * </ul>
 *
 * <p>Both method cards are expandable via {@link StrategistCard} — clicking reveals
 * the location detail and any efficiency tips from the JSON guide.
 *
 * <p>The method display refreshes automatically when the player levels up
 * ({@link net.runelite.api.events.StatChanged} → {@link #refresh}).
 */
@Singleton
public class SkillsTabPanel extends JPanel
{
    // ── Colours ───────────────────────────────────────────────────────────────
    private static final Color C_MAIN_BG = ColorScheme.DARK_GRAY_COLOR;
    private static final Color C_CARD_BG = ColorScheme.DARKER_GRAY_COLOR;
    private static final Color C_DIM     = new Color(150, 150, 150);

    // ── XP table — XP_TABLE[level] = cumulative XP required to reach that level ─
    private static final int[] XP_TABLE = buildXpTable();

    private static int[] buildXpTable()
    {
        int[] table = new int[100]; // indices 1-99; [0] unused
        double points = 0;
        for (int i = 1; i < 99; i++)
        {
            points += Math.floor(i + 300.0 * Math.pow(2.0, i / 7.0));
            table[i + 1] = (int) (points / 4);
        }
        return table;
    }

    private static int levelForXp(int xp)
    {
        for (int lvl = 99; lvl >= 2; lvl--)
            if (xp >= XP_TABLE[lvl]) return lvl;
        return 1;
    }

    // ── UI components ─────────────────────────────────────────────────────────
    private JComboBox<String> skillSelector;
    private JPanel            methodPanel;

    // ── State ─────────────────────────────────────────────────────────────────
    /**
     * Snapshot of skill levels, populated on the game thread by
     * {@link #updateLevels} and consumed on the EDT by {@link #refreshMethodDisplay}.
     * Index = {@link Skill#ordinal()}.  Never null after first login.
     */
    private int[] cachedLevels = new int[Skill.values().length];

    /**
     * Snapshot of raw skill XP values, pushed from the game thread alongside levels.
     * Used to compute "XP to next level" for display.  May be null before first tick.
     */
    private volatile int[] cachedXp;

    /** Manually configured Sailing level (Sailing is not a native RuneLite skill). */
    private int    lastSailingLevel = 1;

    private final SkillGuideProvider skillGuideProvider;

    @Inject
    public SkillsTabPanel(SkillGuideProvider skillGuideProvider)
    {
        super();
        this.skillGuideProvider = skillGuideProvider;
        buildUi();
    }

    private void buildUi()
    {
        setLayout(new BorderLayout());
        setBackground(C_MAIN_BG);

        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBackground(C_MAIN_BG);
        col.setBorder(new EmptyBorder(6, 8, 8, 8));

        // ── Skill selector ────────────────────────────────────────────────────
        col.add(sectionLabel("SELECT SKILL"));

        List<String> names = skillGuideProvider.getAllSkillDisplayNames();
        if (!names.isEmpty())
        {
            skillSelector = new JComboBox<>(names.toArray(new String[0]));
            skillSelector.setFont(FontManager.getRunescapeSmallFont());
            skillSelector.setBackground(C_CARD_BG);
            skillSelector.setForeground(Color.WHITE);
            skillSelector.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            skillSelector.setAlignmentX(Component.LEFT_ALIGNMENT);

            col.add(skillSelector);
            col.add(Box.createRigidArea(new Dimension(0, 8)));

            // Refresh method cards whenever the selection changes
            skillSelector.addActionListener(e -> refreshMethodDisplay());
        }
        else
        {
            col.add(dimLabel("No skill guides loaded."));
        }

        // ── Method display (rebuilt on selection/stat change) ─────────────────
        methodPanel = new JPanel();
        methodPanel.setLayout(new BoxLayout(methodPanel, BoxLayout.Y_AXIS));
        methodPanel.setBackground(C_MAIN_BG);
        methodPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        col.add(methodPanel);

        add(col, BorderLayout.NORTH);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public update API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Push a fresh snapshot of all skill levels and rebuild the method display.
     * The {@code levels} array is produced on the game thread (where
     * {@code client.getRealSkillLevel()} is safe) and consumed here on the EDT.
     * Must be called from the EDT (wrapped in {@code invokeLater} by the caller).
     */
    public void updateLevels(int[] levels, int sailingLevel)
    {
        this.cachedLevels    = levels;
        this.lastSailingLevel = sailingLevel;
        refreshMethodDisplay();
    }

    /**
     * Push a raw skill-XP snapshot for "XP to next level" display.
     * Safe to call from any thread — {@code cachedXp} is volatile (write is safe)
     * and the Swing redraw is dispatched to the EDT via {@code invokeLater}.
     */
    public void updateSkillXp(int[] xpValues)
    {
        this.cachedXp = xpValues;
        SwingUtilities.invokeLater(this::refreshMethodDisplay);
    }

    /**
     * Refresh method display when stats change (level-up).
     * Must be called from the EDT.
     * @deprecated Prefer {@link #updateLevels(int[], int)} which reads levels on the
     *             game thread to avoid EDT/game-thread visibility issues.
     */
    @Deprecated
    public void refresh(Client client, int sailingLevel)
    {
        // Build a snapshot from the client synchronously on whatever thread this
        // is called from. In practice this is the EDT via invokeLater, so reads
        // may lag one tick — updateLevels() is the preferred path.
        if (client != null)
        {
            int[] snapshot = new int[Skill.values().length];
            for (Skill s : Skill.values())
            {
                snapshot[s.ordinal()] = client.getRealSkillLevel(s);
            }
            this.cachedLevels = snapshot;
        }
        this.lastSailingLevel = sailingLevel;
        refreshMethodDisplay();
    }

    /**
     * Auto-select the named skill in the dropdown (case-insensitive).
     * Called on login to pre-select the skill matching the next milestone's
     * {@code trigger_skill}, so the player immediately sees relevant methods.
     * No-op if the skill name is not present in the list.
     */
    public void selectSkill(String skillName)
    {
        if (skillSelector == null || skillName == null || skillName.isEmpty()) return;
        for (int i = 0; i < skillSelector.getItemCount(); i++)
        {
            if (skillName.equalsIgnoreCase(skillSelector.getItemAt(i)))
            {
                skillSelector.setSelectedIndex(i);
                return;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Method display
    // ─────────────────────────────────────────────────────────────────────────

    private void refreshMethodDisplay()
    {
        if (methodPanel == null || skillSelector == null) return;

        String selected = (String) skillSelector.getSelectedItem();
        if (selected == null) return;

        // Determine real level from the game-thread snapshot (cachedLevels).
        // Sailing is not a native RuneLite skill — use the manually configured value.
        int playerLevel = 1;
        if ("SAILING".equalsIgnoreCase(selected))
        {
            playerLevel = lastSailingLevel;
        }
        else
        {
            Skill skill = nameToSkill(selected);
            if (skill != null && cachedLevels != null)
            {
                int lvl = cachedLevels[skill.ordinal()];
                if (lvl > 0) playerLevel = lvl;  // 0 = not yet populated; keep default 1
            }
        }

        methodPanel.removeAll();

        // ── Level + XP-to-next info bar ───────────────────────────────────────
        // Sailing is not a native RuneLite skill so XP tracking is unavailable.
        boolean isSailing = "SAILING".equalsIgnoreCase(selected);
        Skill resolvedSkill = isSailing ? null : nameToSkill(selected);

        if (!isSailing && resolvedSkill != null && cachedXp != null
                && resolvedSkill.ordinal() < cachedXp.length && playerLevel < 99)
        {
            int currentXp   = cachedXp[resolvedSkill.ordinal()];
            int nextLvlXp   = XP_TABLE[playerLevel + 1];
            int xpToNext    = Math.max(0, nextLvlXp - currentXp);
            int xpThisLevel = nextLvlXp - (playerLevel >= 2 ? XP_TABLE[playerLevel] : 0);
            int xpProgress  = xpThisLevel - xpToNext;
            // Simple percentage for the level progress bar character
            int pct = xpThisLevel > 0 ? (int) (100.0 * xpProgress / xpThisLevel) : 0;

            methodPanel.add(dimLabel("  Level " + playerLevel
                    + "  ·  " + fmt(xpToNext) + " xp to " + (playerLevel + 1)
                    + "  [" + pct + "%]"));
            methodPanel.add(Box.createRigidArea(new Dimension(0, 4)));
        }
        else if (playerLevel == 99)
        {
            methodPanel.add(dimLabel("  Level 99  ·  Maxed!"));
            methodPanel.add(Box.createRigidArea(new Dimension(0, 4)));
        }

        // ── Current best method ───────────────────────────────────────────────
        SkillGuideProvider.TrainingMethod best = skillGuideProvider.getBestMethod(selected, playerLevel);
        if (best != null)
        {
            methodPanel.add(sectionLabel("BEST AT LEVEL " + playerLevel));
            methodPanel.add(methodCard(best, StrategistCard.Category.SKILLING));
        }
        else
        {
            methodPanel.add(dimLabel("No method found for level " + playerLevel + "."));
        }

        // ── Next unlock ───────────────────────────────────────────────────────
        SkillGuideProvider.TrainingMethod next = skillGuideProvider.getNextMethod(selected, playerLevel);
        if (next != null)
        {
            methodPanel.add(Box.createRigidArea(new Dimension(0, 10)));
            methodPanel.add(sectionLabel("UNLOCKS AT LEVEL " + next.minLevel));
            methodPanel.add(methodCard(next, StrategistCard.Category.GEAR));
        }

        methodPanel.revalidate();
        methodPanel.repaint();
    }

    /**
     * Builds a {@link StrategistCard} for a training method.
     * Value line: location + XP/hr.
     * Expand text: the method's tip (if present) or location detail.
     */
    private StrategistCard methodCard(SkillGuideProvider.TrainingMethod m, StrategistCard.Category cat)
    {
        // xp_per_hour = 0 means questing, periodic runs, or passive activities where
        // a continuous XP/hr rate is meaningless — show "varies" instead of "0 xp/hr".
        String xpLabel    = m.xpPerHour > 0 ? formatXp(m.xpPerHour) + " xp/hr" : "varies";
        String valueLine  = m.location + "  ·  " + xpLabel;
        String expandText = (m.tip != null && !m.tip.isEmpty())
                ? m.tip
                : "Train at: " + m.location;

        StrategistCard card = new StrategistCard(m.name, valueLine, expandText, cat);
        // Auto-expand best method so the tip is immediately visible
        if (cat == StrategistCard.Category.SKILLING) card.setExpanded(true);
        return card;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private JLabel sectionLabel(String text)
    {
        JLabel l = new JLabel(text);
        l.setFont(FontManager.getRunescapeSmallFont());
        l.setForeground(C_DIM);
        l.setBorder(new EmptyBorder(10, 4, 6, 0));
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

    // ─────────────────────────────────────────────────────────────────────────
    //  Utility
    // ─────────────────────────────────────────────────────────────────────────

    private static Skill nameToSkill(String name)
    {
        if (name == null) return null;
        String upper = name.toUpperCase();
        // RuneLite enum is RUNECRAFT; skill guides use RUNECRAFTING display name
        if ("RUNECRAFTING".equals(upper)) upper = "RUNECRAFT";
        try { return Skill.valueOf(upper); }
        catch (Exception e) { return null; }
    }

    private static String formatXp(int xp)
    {
        if (xp >= 1_000_000) return String.format("%.1fM", xp / 1_000_000.0);
        if (xp >= 1_000)     return String.format("%,d", xp);
        return String.valueOf(xp);
    }

    /** Short alias used in the level/XP info bar. */
    private static String fmt(int xp) { return formatXp(xp); }
}
