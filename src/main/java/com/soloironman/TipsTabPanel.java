package com.soloironman;

import net.runelite.api.Client;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;
import java.util.Map;

/**
 * TIPS tab — contextual 2026-meta efficiency tips gated by the player's skill levels.
 *
 * <p>Tips are loaded from {@code ironman_meta.json} via
 * {@link MetaDataProvider#getActiveTipEntries}.  Each tip is rendered as an
 * expandable {@link StrategistCard}: the headline is a clean
 * {@code "Skill Level+"} label (e.g. {@code "Agility 60+"}) and the full tip
 * text is revealed on click.
 *
 * <p>Category colouring is inferred from keywords in the tip text — combat-related
 * tips get a red border, skilling tips get green, gear/sailing tips get blue, and
 * everything else is amber (foundation).
 *
 * <p>The list refreshes whenever the player levels up ({@link #refresh}) or logs in.
 */
@Singleton
public class TipsTabPanel extends JPanel
{
    // ── Colours ───────────────────────────────────────────────────────────────
    private static final Color C_MAIN_BG = ColorScheme.DARK_GRAY_COLOR;
    private static final Color C_DIM     = new Color(150, 150, 150);

    // ── Dynamic tips panel ────────────────────────────────────────────────────
    private final JPanel tipsContentPanel;
    private final JPanel moneyContentPanel;

    private final MetaDataProvider metaDataProvider;

    /** Game-thread snapshot of skill levels — set via {@link #updateLevels}. */
    private int[] cachedLevels;
    private int   cachedSailingLevel = 1;
    private net.runelite.api.Client cachedClient;

    @Inject
    public TipsTabPanel(MetaDataProvider metaDataProvider)
    {
        super();
        this.metaDataProvider = metaDataProvider;

        tipsContentPanel = new JPanel();
        tipsContentPanel.setLayout(new BoxLayout(tipsContentPanel, BoxLayout.Y_AXIS));
        tipsContentPanel.setBackground(C_MAIN_BG);
        tipsContentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        moneyContentPanel = new JPanel();
        moneyContentPanel.setLayout(new BoxLayout(moneyContentPanel, BoxLayout.Y_AXIS));
        moneyContentPanel.setBackground(C_MAIN_BG);
        moneyContentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

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

        col.add(sectionLabel("2026 META TIPS"));
        col.add(tipsContentPanel);

        col.add(Box.createRigidArea(new Dimension(0, 8)));
        col.add(moneyLabel("MONEY MAKING"));
        col.add(moneyContentPanel);

        add(col, BorderLayout.NORTH);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public update API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Push a fresh skill-level snapshot and rebuild the tips list.
     * The {@code client} reference is cached so that money-making quest-requirement
     * checks in {@link MetaDataProvider#getActiveMoneyMakingMethods} actually fire.
     * Call this from the EDT whenever levels change (login, level-up).
     */
    public void updateLevels(Client client, int[] levels, int sailingLevel)
    {
        if (client != null) this.cachedClient = client;
        this.cachedLevels       = levels;
        this.cachedSailingLevel = sailingLevel;
        rebuildTips();
    }

    /**
     * Legacy refresh via Client — kept for compatibility.
     * Prefer {@link #updateLevels} which uses a game-thread-safe snapshot.
     */
    public void refresh(Client client, int sailingLevel)
    {
        if (client != null) cachedClient = client;
        // Fall back to cached levels if available; client path kept for compat
        if (cachedLevels != null)
        {
            cachedSailingLevel = sailingLevel;
            rebuildTips();
            return;
        }
        if (client == null) return;
        // Build a one-off snapshot (may be slightly stale from EDT)
        net.runelite.api.Skill[] skills = net.runelite.api.Skill.values();
        int[] snapshot = new int[skills.length];
        for (net.runelite.api.Skill s : skills)
            snapshot[s.ordinal()] = client.getRealSkillLevel(s);
        cachedLevels      = snapshot;
        cachedSailingLevel = sailingLevel;
        rebuildTips();
    }

    private void rebuildTips()
    {
        List<MetaDataProvider.EfficiencyTip> tips =
                metaDataProvider.getActiveTipEntries(cachedLevels, cachedSailingLevel);
        tipsContentPanel.removeAll();

        if (tips.isEmpty())
        {
            tipsContentPanel.add(dimLabel("No tips available at your current levels."));
            tipsContentPanel.add(dimLabel("Keep levelling — tips unlock as you progress."));
        }
        else
        {
            for (MetaDataProvider.EfficiencyTip tip : tips)
            {
                // Generate a clean title like "Agility 60+" rather than the old
                // "[AGILITY 60+] Use Stamina Poti…" prefix-and-truncate approach.
                String title = buildTipTitle(tip.triggerSkill, tip.triggerLevel);
                StrategistCard.Category cat = categorizeTip(tip.tip);

                StrategistCard card = StrategistCard.tip(title, tip.tip, cat);
                tipsContentPanel.add(card);
                tipsContentPanel.add(Box.createRigidArea(new Dimension(0, 4)));
            }
        }

        tipsContentPanel.revalidate();
        tipsContentPanel.repaint();
        rebuildMoney();
    }

    private void rebuildMoney()
    {
        List<MetaDataProvider.MoneyMakingMethod> methods =
                metaDataProvider.getActiveMoneyMakingMethods(
                    cachedLevels, cachedSailingLevel, cachedClient);
        moneyContentPanel.removeAll();

        if (methods.isEmpty())
        {
            moneyContentPanel.add(dimLabel(cachedLevels == null
                ? "Log in to see money making recommendations."
                : "No methods unlocked yet — keep levelling."));
        }
        else
        {
            for (MetaDataProvider.MoneyMakingMethod m : methods)
            {
                StrategistCard.Category cat = categorizeMoneyTip(m.category);
                // GP/hr is in the expand body so it's seen when the card opens
                String body = "~" + formatGp(m.gpHr) + " GP/hr\n\n" + m.tip;
                StrategistCard card = StrategistCard.tip(m.title, body, cat);

                // Inject the coloured reqs panel as a subtitle (always visible)
                JPanel reqsPanel = buildReqsPanel(m, cachedLevels, cachedSailingLevel,
                                                   cachedClient);
                card.addSubtitle(reqsPanel);

                moneyContentPanel.add(card);
                moneyContentPanel.add(Box.createRigidArea(new Dimension(0, 4)));
            }
        }

        moneyContentPanel.revalidate();
        moneyContentPanel.repaint();
    }

    /** Formats a GP/hr integer as a compact string: 1500000 → "1.5M", 650000 → "650K". */
    private static String formatGp(int gp)
    {
        if (gp >= 1_000_000)
        {
            double m = gp / 1_000_000.0;
            return (m == Math.floor(m)) ? (int) m + "M" : String.format("%.1fM", m);
        }
        if (gp >= 1_000)
        {
            double k = gp / 1_000.0;
            return (k == Math.floor(k)) ? (int) k + "K" : String.format("%.0fK", k);
        }
        return String.valueOf(gp);
    }

    /**
     * Builds a compact "Reqs: RC 77, Mining 38, Agility 73" line from a method's
     * skill and quest requirements. Skills are title-cased and abbreviated to 4 chars
     * (e.g. RUNECRAFT → "RC", AGILITY → "Agil") for readability in a small card.
     */
    static String buildReqsLine(MetaDataProvider.MoneyMakingMethod m)
    {
        StringBuilder sb = new StringBuilder("Reqs: ");
        boolean any = false;

        if (m.reqs != null && !m.reqs.isEmpty())
        {
            for (Map.Entry<String, Integer> entry : m.reqs.entrySet())
            {
                if (any) sb.append(", ");
                sb.append(abbreviateSkill(entry.getKey())).append(" ").append(entry.getValue());
                any = true;
            }
        }

        if (m.reqQuests != null)
        {
            for (String q : m.reqQuests)
            {
                if (any) sb.append(", ");
                // Convert SNAKE_CASE quest name to title case for display
                sb.append(questDisplayName(q));
                any = true;
            }
        }

        if (!any) sb.append("None");
        return sb.toString();
    }

    // ── Colours for req chips ─────────────────────────────────────────────────
    private static final Color C_REQ_MET     = new Color( 74, 222, 128);  // green
    private static final Color C_REQ_MISSING = new Color(248, 113, 113);  // red
    private static final Color C_REQ_LABEL   = new Color(150, 150, 150);  // dim prefix

    /**
     * Builds a horizontal row of coloured label chips showing each requirement.
     * <ul>
     *   <li>Green — player meets this skill/quest requirement</li>
     *   <li>Red   — player is missing this requirement</li>
     * </ul>
     *
     * <p>Uses a {@link FlowLayout} so chips wrap naturally on narrow panels.</p>
     */
    static JPanel buildReqsPanel(MetaDataProvider.MoneyMakingMethod m,
                                  int[] skillLevels, int sailingLevel,
                                  net.runelite.api.Client client)
    {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        row.setOpaque(false);

        // "Reqs:" prefix label
        JLabel prefix = new JLabel("Reqs:");
        prefix.setFont(FontManager.getRunescapeSmallFont());
        prefix.setForeground(C_REQ_LABEL);
        row.add(prefix);

        boolean any = false;

        // ── Skill requirements ────────────────────────────────────────────────
        if (m.reqs != null)
        {
            for (Map.Entry<String, Integer> entry : m.reqs.entrySet())
            {
                String skillKey = entry.getKey().toUpperCase();
                int    required = entry.getValue();
                boolean met;

                if ("SAILING".equals(skillKey))
                {
                    met = sailingLevel >= required;
                }
                else
                {
                    String enumName = "RUNECRAFTING".equals(skillKey) ? "RUNECRAFT" : skillKey;
                    int have = 1;
                    try
                    {
                        net.runelite.api.Skill s = net.runelite.api.Skill.valueOf(enumName);
                        have = (skillLevels != null && s.ordinal() < skillLevels.length)
                               ? skillLevels[s.ordinal()] : 1;
                    }
                    catch (IllegalArgumentException ignored) { }
                    met = have >= required;
                }

                String chip = abbreviateSkill(skillKey) + " " + required;
                row.add(chipLabel(chip, met ? C_REQ_MET : C_REQ_MISSING));
                any = true;
            }
        }

        // ── Quest requirements ────────────────────────────────────────────────
        if (m.reqQuests != null)
        {
            for (String questName : m.reqQuests)
            {
                boolean met = false;
                if (client != null)
                {
                    try
                    {
                        net.runelite.api.Quest quest =
                            net.runelite.api.Quest.valueOf(questName.toUpperCase());
                        met = quest.getState(client)
                              == net.runelite.api.QuestState.FINISHED;
                    }
                    catch (IllegalArgumentException ignored) { }
                }
                row.add(chipLabel(questDisplayName(questName), met ? C_REQ_MET : C_REQ_MISSING));
                any = true;
            }
        }

        if (!any)
        {
            JLabel none = new JLabel("None");
            none.setFont(FontManager.getRunescapeSmallFont());
            none.setForeground(C_REQ_MET);
            row.add(none);
        }

        return row;
    }

    /** A small rounded label chip in the given colour. */
    private static JLabel chipLabel(String text, Color fg)
    {
        JLabel l = new JLabel(text);
        l.setFont(FontManager.getRunescapeSmallFont());
        l.setForeground(fg);
        return l;
    }

    /** Shortens a skill name to a readable abbreviation: "RUNECRAFT" → "RC", "AGILITY" → "Agil" etc. */
    private static String abbreviateSkill(String skill)
    {
        if (skill == null) return "?";
        switch (skill.toUpperCase())
        {
            case "ATTACK":       return "Atk";
            case "STRENGTH":     return "Str";
            case "DEFENCE":      return "Def";
            case "HITPOINTS":    return "HP";
            case "RANGED":       return "Rng";
            case "PRAYER":       return "Pray";
            case "MAGIC":        return "Mage";
            case "COOKING":      return "Cook";
            case "WOODCUTTING":  return "WC";
            case "FLETCHING":    return "Fletch";
            case "FISHING":      return "Fish";
            case "FIREMAKING":   return "FM";
            case "CRAFTING":     return "Craft";
            case "SMITHING":     return "Smith";
            case "MINING":       return "Mine";
            case "HERBLORE":     return "Herb";
            case "AGILITY":      return "Agil";
            case "THIEVING":     return "Thiev";
            case "SLAYER":       return "Slay";
            case "FARMING":      return "Farm";
            case "RUNECRAFT":
            case "RUNECRAFTING": return "RC";
            case "HUNTER":       return "Hunt";
            case "CONSTRUCTION": return "Con";
            case "SAILING":      return "Sail";
            default:
                // Title-case fallback for unknown skills
                String s = skill.toLowerCase();
                return Character.toUpperCase(s.charAt(0)) + s.substring(1, Math.min(s.length(), 5));
        }
    }

    /** Converts "RECIPE_OF_DISASTER" → "Recipe of Disaster" for quest names. */
    private static String questDisplayName(String raw)
    {
        if (raw == null || raw.isEmpty()) return raw;
        String[] words = raw.replace('_', ' ').toLowerCase().split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++)
        {
            if (i > 0) sb.append(' ');
            if (words[i].length() > 0)
                sb.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
        }
        return sb.toString();
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

    private JLabel moneyLabel(String text)
    {
        JLabel l = new JLabel(text);
        l.setFont(FontManager.getRunescapeSmallFont());
        l.setForeground(new Color(74, 222, 128));  // green — GP = growth
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
    //  Utility — tip title and classification
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a clean card title from the tip's trigger skill and level.
     * Example: {@code triggerSkill="AGILITY", triggerLevel=60} → {@code "Agility 60+"}
     */
    static String buildTipTitle(String triggerSkill, int triggerLevel)
    {
        if (triggerSkill == null || triggerSkill.isEmpty()) return "Meta Tip";
        String skill = triggerSkill.substring(0, 1).toUpperCase()
                     + triggerSkill.substring(1).toLowerCase();
        return skill + " " + triggerLevel + "+";
    }

    /**
     * Extracts a short title from tip text (legacy — prefer {@link #buildTipTitle}).
     * Strategy: use the text before the first colon or period if within 40 chars,
     * otherwise truncate at 40 chars.
     */
    static String extractTipTitle(String tip)
    {
        if (tip == null || tip.isEmpty()) return "Meta Tip";

        int colon  = tip.indexOf(':');
        int period = tip.indexOf('.');

        // Use whichever delimiter comes first, as long as it's within 40 chars
        int cut = -1;
        if (colon  > 0 && colon  <= 40) cut = colon;
        if (period > 0 && period <= 40 && (cut < 0 || period < cut)) cut = period;

        if (cut > 0) return tip.substring(0, cut).trim();
        if (tip.length() > 40) return tip.substring(0, 40).trim() + "…";
        return tip.trim();
    }

    /**
     * Assigns a {@link StrategistCard.Category} based on keywords in the tip text.
     * This is a best-effort heuristic — the JSON doesn't tag tips with categories.
     */
    static StrategistCard.Category categorizeTip(String tip)
    {
        if (tip == null) return StrategistCard.Category.FOUNDATION;
        String lower = tip.toLowerCase();

        if (containsAny(lower, "slayer", "combat", "prayer", "melee",
                               "ranged", "magic", "defence", "attack",
                               "strength", "hitpoints", "moons of peril",
                               "colosseum", "boss", "kills"))
        {
            return StrategistCard.Category.COMBAT;
        }

        if (containsAny(lower, "farming", "herblore", "cooking", "fishing",
                               "woodcutting", "mining", "hunter", "thieving",
                               "agility", "fletching", "crafting", "smithing",
                               "firemaking", "runecraft", "construction", "birdhouse",
                               "herb run", "farm run"))
        {
            return StrategistCard.Category.SKILLING;
        }

        if (containsAny(lower, "gear", "equipment", "weapon", "armour", "armor",
                               "crossbow", "bow", "shield", "sailing",
                               "graceful", "rogue", "defender"))
        {
            return StrategistCard.Category.GEAR;
        }

        return StrategistCard.Category.FOUNDATION;
    }

    private static boolean containsAny(String text, String... keywords)
    {
        for (String k : keywords) if (text.contains(k)) return true;
        return false;
    }

    /** Maps the JSON category string from money_making_tips to a card category. */
    static StrategistCard.Category categorizeMoneyTip(String category)
    {
        if (category == null) return StrategistCard.Category.FOUNDATION;
        switch (category.toUpperCase())
        {
            case "COMBAT":   return StrategistCard.Category.COMBAT;
            case "SKILLING": return StrategistCard.Category.SKILLING;
            case "GEAR":     return StrategistCard.Category.GEAR;
            default:         return StrategistCard.Category.FOUNDATION;
        }
    }
}
