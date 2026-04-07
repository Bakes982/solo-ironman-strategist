package com.soloironman;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Reusable Quest-Helper–style card for the Solo Ironman Strategist plugin.
 *
 * <h3>Visual structure</h3>
 * <pre>
 * ┌──┬────────────────────────────────────────────────────┐
 * │  │  TITLE (dim, small-caps)              [right opt] │  ← BorderLayout.CENTER
 * │  │  Value line (bold white)                          │
 * │  ├────────────────────────────────────────────────────┤
 * │  │  Expand text (JTextArea, line-wrapped)            │  ← BorderLayout.SOUTH
 * └──┴────────────────────────────────────────────────────┘
 *  ▲
 *  3 px MatteBorder (category colour)
 * </pre>
 *
 * <h3>Expand/collapse</h3>
 * The {@link JTextArea} anchored to {@link BorderLayout#SOUTH} is the most stable
 * expand pattern in RuneLite panels: it respects line-wrap without squishing the
 * card width, and toggling {@code setVisible} combined with
 * {@link #propagateRevalidate()} forces every ancestor layout manager to
 * re-measure its children.
 *
 * <h3>Category colours</h3>
 * <ul>
 *   <li>{@link Category#COMBAT}     — Red    (#F87171)</li>
 *   <li>{@link Category#SKILLING}   — Green  (#4ADE80)</li>
 *   <li>{@link Category#GEAR}       — Blue   (#93C5FD)</li>
 *   <li>{@link Category#FOUNDATION} — Amber  (#FF9800)</li>
 * </ul>
 */
public class StrategistCard extends JPanel
{
    // ── Category → left border colour ─────────────────────────────────────────
    public enum Category
    {
        COMBAT      (new Color(248, 113, 113)),  // Red
        SKILLING    (new Color( 74, 222, 128)),  // Green
        GEAR        (new Color(147, 197, 253)),  // Blue
        FOUNDATION  (new Color(255, 152,   0));  // Amber

        public final Color color;
        Category(Color c) { this.color = c; }
    }

    // ── Shared colour constants ────────────────────────────────────────────────
    private static final Color BG_NORMAL = ColorScheme.DARKER_GRAY_COLOR;
    private static final Color BG_HOVER  = ColorScheme.DARK_GRAY_HOVER_COLOR;
    private static final Color FG_DIM    = new Color(150, 150, 150);
    private static final Color FG_WHITE  = Color.WHITE;
    private static final Color FG_BODY   = new Color(200, 200, 200);

    // ── Component refs kept for live updates ───────────────────────────────────
    private final JLabel    valueLabel;
    private final JPanel    textStack;   // title + value + optional subtitle rows
    private final JTextArea expandArea;
    private       boolean   expanded   = false;
    private final boolean   expandable;

    // ─────────────────────────────────────────────────────────────────────────
    //  Constructors
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Non-expandable metric card — ideal for live data like "Supply Forecast".
     * Hold a reference and call {@link #updateValue} to push new values.
     */
    public StrategistCard(String title, String value, Category category)
    {
        this(title, value, null, category, null);
    }

    /**
     * Expandable card — reveals {@code expandText} in a wrapped text area on click.
     */
    public StrategistCard(String title, String value, String expandText, Category category)
    {
        this(title, value, expandText, category, null);
    }

    /**
     * Full constructor supporting an optional right-side component.
     *
     * @param title          dim small-caps label at the top of the card
     * @param value          bold white main value / headline text
     * @param expandText     multi-line body shown on expand (null/empty → no expand)
     * @param category       left-border colour category
     * @param rightComponent optional right component, e.g. {@link JCheckBox} for checklists
     */
    public StrategistCard(String title, String value, String expandText,
                          Category category, JComponent rightComponent)
    {
        super(new BorderLayout());
        // Required so BoxLayout(Y_AXIS) parents left-align this card rather
        // than centering it.  Must be called before any child is added.
        setAlignmentX(Component.LEFT_ALIGNMENT);
        this.expandable = expandText != null && !expandText.isEmpty();

        // ── Card border: 3px colour strip + inner padding ─────────────────────
        setBackground(BG_NORMAL);
        setBorder(new CompoundBorder(
                new MatteBorder(0, 3, 0, 0, category.color),
                new EmptyBorder(10, 12, 10, 12)
        ));

        // ── Header: title + value stacked vertically ──────────────────────────
        // Use a BoxLayout text-stack so labels are left-aligned and stack cleanly.
        // Saved as a field so addSubtitle() can inject rows after construction.
        this.textStack = new JPanel();
        this.textStack.setLayout(new BoxLayout(this.textStack, BoxLayout.Y_AXIS));
        textStack.setOpaque(false);

        JLabel titleLbl = new JLabel(title.toUpperCase());
        titleLbl.setFont(FontManager.getRunescapeSmallFont());
        titleLbl.setForeground(FG_DIM);
        titleLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        this.valueLabel = new JLabel(value == null ? "" : value);
        this.valueLabel.setFont(FontManager.getRunescapeBoldFont());
        this.valueLabel.setForeground(FG_WHITE);
        this.valueLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        textStack.add(titleLbl);
        textStack.add(Box.createRigidArea(new Dimension(0, 3)));
        textStack.add(this.valueLabel);

        // Wrap the text-stack in a BorderLayout header so the right component
        // gets placed in EAST without affecting the title/value stack width.
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        header.add(textStack, BorderLayout.CENTER);
        if (rightComponent != null)
        {
            rightComponent.setOpaque(false);
            header.add(rightComponent, BorderLayout.EAST);
        }

        add(header, BorderLayout.CENTER);

        // ── Expand area (SOUTH) ───────────────────────────────────────────────
        // JTextArea with line-wrap is placed in SOUTH so it grows vertically
        // without ever exceeding the panel width. setVisible(false) hides it
        // until the user clicks the card.
        this.expandArea = new JTextArea(expandText == null ? "" : expandText);
        this.expandArea.setFont(FontManager.getRunescapeSmallFont());
        this.expandArea.setForeground(FG_BODY);
        this.expandArea.setBackground(BG_NORMAL);
        this.expandArea.setLineWrap(true);       // ← wrap at panel width
        this.expandArea.setWrapStyleWord(true);  // ← break on word boundaries
        this.expandArea.setEditable(false);
        this.expandArea.setFocusable(false);
        this.expandArea.setOpaque(true);         // explicit so BG colour applies
        this.expandArea.setBorder(new EmptyBorder(8, 0, 0, 0));
        this.expandArea.setVisible(false);       // hidden until expand

        add(this.expandArea, BorderLayout.SOUTH);

        // ── Mouse interaction ─────────────────────────────────────────────────
        // Hover is applied to all cards; click-to-expand only when expandable.
        MouseAdapter adapter = expandable
                ? new MouseAdapter()
                  {
                      @Override public void mouseClicked(MouseEvent e) { toggleExpand(); }
                      @Override public void mouseEntered(MouseEvent e) { tint(BG_HOVER);  }
                      @Override public void mouseExited(MouseEvent e)  { tint(BG_NORMAL); }
                  }
                : new MouseAdapter()
                  {
                      @Override public void mouseEntered(MouseEvent e) { tint(BG_HOVER);  }
                      @Override public void mouseExited(MouseEvent e)  { tint(BG_NORMAL); }
                  };

        // Attach to this panel AND its immediate children so hover fires
        // regardless of which sub-component the cursor is over.
        addMouseListener(adapter);
        header.addMouseListener(adapter);
        textStack.addMouseListener(adapter);

        if (expandable) setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API — live update methods (safe to call from EDT via invokeLater)
    // ─────────────────────────────────────────────────────────────────────────

    /** Replace the bold value line. */
    public void updateValue(String text)
    {
        valueLabel.setText(text == null ? "" : text);
    }

    /** Replace the bold value line and change its colour (e.g. red for low supply). */
    public void updateValue(String text, Color color)
    {
        valueLabel.setText(text == null ? "" : text);
        valueLabel.setForeground(color != null ? color : FG_WHITE);
    }

    /** Reset value label colour to the default white. */
    public void resetValueColor()
    {
        valueLabel.setForeground(FG_WHITE);
    }

    /**
     * Programmatically expand or collapse the card (e.g. to auto-open the first
     * checklist item on panel load).
     */
    public void setExpanded(boolean expand)
    {
        if (!expandable || this.expanded == expand) return;
        this.expanded = expand;
        expandArea.setVisible(expand);
        propagateRevalidate();
    }

    /** @return {@code true} if the expand area is currently visible. */
    public boolean isExpanded()   { return expanded;   }

    /** @return {@code true} if this card has expandable text. */
    public boolean isExpandable() { return expandable; }

    /**
     * Inject a custom component as a subtitle row directly below the value label.
     * The subtitle is always visible (not hidden by expand/collapse).
     * Typically used to insert a coloured requirements panel into money-making cards.
     *
     * <p>Must be called on the EDT before the card is added to a container,
     * or wrapped in {@code SwingUtilities.invokeLater} if called later.</p>
     *
     * @param subtitle the component to add as a subtitle row (e.g. a JPanel of coloured chips)
     */
    public void addSubtitle(JComponent subtitle)
    {
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitle.setOpaque(false);
        textStack.add(Box.createRigidArea(new Dimension(0, 4)));
        textStack.add(subtitle);
        textStack.revalidate();
        textStack.repaint();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Static factory helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Non-expandable card for live metric data (supply forecast, farm timer, etc.).
     * Retain the returned reference to call {@link #updateValue} with fresh data.
     *
     * @param title    e.g. {@code "SUPPLY FORECAST"}
     * @param value    e.g. {@code "148 Pots (12.3 hrs)"}
     * @param category border colour
     */
    public static StrategistCard metric(String title, String value, Category category)
    {
        return new StrategistCard(title, value, category);
    }

    /**
     * Expandable checklist item with an optional right-side checkbox.
     *
     * <p>Usage in {@code GuideTabPanel}:
     * <pre>{@code
     * JCheckBox cb = new JCheckBox();
     * cb.addActionListener(e -> onMilestoneToggled(milestone.id, cb.isSelected()));
     * StrategistCard card = StrategistCard.milestone(
     *         milestone.title, milestone.tip,
     *         categoryFor(milestone.category), cb);
     * }</pre>
     *
     * @param title    milestone name (e.g. {@code "Dragon Defender"})
     * @param tipText  multi-line tip shown on expand
     * @param category border colour
     * @param checkbox right-side checkbox (pass {@code null} to omit)
     */
    public static StrategistCard milestone(String title, String tipText,
                                            Category category, JCheckBox checkbox)
    {
        // Value line shows a subtle hint that the card is clickable, tinted to
        // the category accent.  updateValue() calls from the checklist (e.g.
        // "✓ Done today") will override this with their own explicit colour.
        StrategistCard card = new StrategistCard(title, "▸ tap to see tip", tipText,
                                                  category, checkbox);
        card.updateValue("▸ tap to see tip", accentValue(category));
        return card;
    }

    /**
     * Expandable tip card — for efficiency tips in the TIPS tab.
     *
     * <p>The "▸ tap to expand" hint is tinted with a muted version of the
     * category colour so it visually belongs to its card type at a glance,
     * while remaining clearly secondary to any value pushed by
     * {@link #updateValue(String, Color)}.</p>
     *
     * @param shortTitle e.g. {@code "Hunter 70"}
     * @param fullTip    multi-sentence tip shown on expand
     * @param category   border colour
     */
    public static StrategistCard tip(String shortTitle, String fullTip, Category category)
    {
        StrategistCard card = new StrategistCard(shortTitle, "▸ tap to expand", fullTip, category);
        card.updateValue("▸ tap to expand", accentValue(category));
        return card;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a muted tint of {@code cat.color} suitable for secondary text.
     *
     * <p>Blends 38% of the category colour toward the card background
     * ({@link #BG_NORMAL} ≈ rgb(30,30,30) in RuneLite).  The result is
     * readable but clearly subordinate — the 3 px border remains the
     * dominant colour signal, and the hint text recedes naturally.</p>
     *
     * <p>Blend formula: {@code result = cat × 0.38 + bg × 0.62}</p>
     */
    private static Color accentValue(Category cat)
    {
        // BG_NORMAL is DARKER_GRAY_COLOR ≈ rgb(30, 30, 30)
        final int bgR = 30, bgG = 30, bgB = 30;
        final float t = 0.38f;  // weight toward category colour
        int r = Math.round(cat.color.getRed()   * t + bgR * (1 - t));
        int g = Math.round(cat.color.getGreen() * t + bgG * (1 - t));
        int b = Math.round(cat.color.getBlue()  * t + bgB * (1 - t));
        return new Color(
                Math.min(255, Math.max(0, r)),
                Math.min(255, Math.max(0, g)),
                Math.min(255, Math.max(0, b))
        );
    }

    private void toggleExpand()
    {
        expanded = !expanded;
        expandArea.setVisible(expanded);
        propagateRevalidate();
    }

    private void tint(Color bg)
    {
        setBackground(bg);
        expandArea.setBackground(bg);
    }

    /**
     * Walk the full Swing ancestor chain calling {@code revalidate()} and
     * {@code repaint()} on every parent.
     *
     * <p>Calling only {@code revalidate()} on <em>this</em> card is not sufficient:
     * {@link BoxLayout} and {@link java.awt.GridBagLayout} parents must also
     * re-measure their children after the expand area changes visibility, or the
     * card will render at the wrong (shrunk) width.</p>
     */
    private void propagateRevalidate()
    {
        Container c = this;
        while (c != null)
        {
            c.revalidate();
            c.repaint();
            c = c.getParent();
        }
    }
}
