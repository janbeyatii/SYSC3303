package app.theme;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Shared colors, spacing, and typography for the scheduler dashboard (Swing).
 */
public final class DashboardTheme {

    public static final int PAD_SM = 6;
    public static final int PAD_MD = 10;
    public static final int PAD_LG = 14;
    public static final int RADIUS_CARD = 10;

    /** Window / chrome */
    public static final Color APP_BACKGROUND = new Color(0xF0F4F8);
    public static final Color PANEL_ELEVATED = new Color(0xFFFFFF);
    public static final Color TEXT_MUTED = new Color(0x64748B);
    public static final Color TEXT_PRIMARY = new Color(0x1E293B);
    public static final Color ACCENT = new Color(0x0EA5E9);
    public static final Color ACCENT_SOFT = new Color(0xE0F2FE);

    /** Map canvas */
    public static final Color MAP_CANVAS_BG = new Color(0xE2E8F0);
    public static final Color MAP_GRID_LINE = new Color(0xCBD5E1);

    /** Map / zone backgrounds (muted, modern) */
    public static final Color ZONE_NEUTRAL = new Color(0xF1F5F9);
    public static final Color ZONE_NEUTRAL_DARK = new Color(0xE2E8F0);
    public static final Color ZONE_QUEUED = new Color(0xFEF3C7);
    public static final Color ZONE_QUEUED_DARK = new Color(0xFDE68A);
    public static final Color ZONE_ACTIVE = new Color(0xFECACA);
    public static final Color ZONE_ACTIVE_DARK = new Color(0xFCA5A5);
    public static final Color ZONE_DONE = new Color(0xBBF7D0);
    public static final Color ZONE_DONE_DARK = new Color(0x86EFAC);

    /** Drone marker colors */
    public static final Color DRONE_DEFAULT = new Color(0x0369A1);
    public static final Color DRONE_EN_ROUTE = new Color(0xEA580C);
    public static final Color DRONE_OFFLINE = new Color(0xB91C1C);
    public static final Color DRONE_UNAVAILABLE = new Color(0xCA8A04);
    public static final Color DRONE_FAULTED = new Color(0x7C3AED);

    /** Status badge backgrounds (soft) */
    public static final Color BADGE_QUEUED_BG = new Color(0xFFFBEB);
    public static final Color BADGE_QUEUED_FG = new Color(0xC2410C);
    public static final Color BADGE_DISPATCHED_BG = new Color(0xFEF2F2);
    public static final Color BADGE_DISPATCHED_FG = new Color(0xB91C1C);
    public static final Color BADGE_COMPLETED_BG = new Color(0xECFDF5);
    public static final Color BADGE_COMPLETED_FG = new Color(0x166534);

    public static final Color BADGE_DRONE_IDLE_BG = new Color(0xEFF6FF);
    public static final Color BADGE_DRONE_IDLE_FG = new Color(0x1D4ED8);
    public static final Color BADGE_DRONE_BUSY_BG = new Color(0xFFF7ED);
    public static final Color BADGE_DRONE_BUSY_FG = new Color(0xC2410C);
    public static final Color BADGE_DRONE_FAULT_BG = new Color(0xFEF2F2);
    public static final Color BADGE_DRONE_FAULT_FG = new Color(0xB91C1C);

    /** Alert banner (faults / warnings) */
    public static final Color ALERT_FAULT_BG = new Color(0xFEF2F2);
    public static final Color ALERT_FAULT_FG = new Color(0x991B1B);
    public static final Color ALERT_FAULT_BORDER = new Color(0xDC2626);
    public static final Color ALERT_WARN_BG = new Color(0xFFFBEB);
    public static final Color ALERT_WARN_FG = new Color(0xB45309);
    public static final Color ALERT_WARN_BORDER = new Color(0xF59E0B);
    public static final Color ALERT_INFO_BG = new Color(0xEFF6FF);
    public static final Color ALERT_INFO_FG = new Color(0x1D4ED8);
    public static final Color ALERT_INFO_BORDER = new Color(0x3B82F6);

    /** Styled event log */
    public static final Color LOG_PANEL_BG = new Color(0xF8FAFC);
    public static final Color LOG_BORDER = new Color(0xCBD5E1);
    public static final Color LOG_TEXT_DEFAULT = new Color(0x334155);
    public static final Color LOG_TEXT_FAULT = new Color(0xB91C1C);
    public static final Color LOG_TEXT_WARN = new Color(0xC2410C);
    public static final Color LOG_TEXT_OK = new Color(0x15803D);
    public static final Color LOG_TEXT_INFO = new Color(0x1D4ED8);
    public static final Color LOG_TEXT_GUI = new Color(0x6B21A8);

    /** Table zebra + fault row tint */
    public static final Color TABLE_ROW_ALT = new Color(0xF8FAFC);
    public static final Color TABLE_ROW_FAULT = new Color(0xFFF1F2);

    /** KPI: fault count hot */
    public static final Color KPI_FAULT_BG = new Color(0xFEF2F2);
    public static final Color KPI_FAULT_FG = new Color(0xB91C1C);
    public static final Color KPI_FAULT_BORDER = new Color(0xFECACA);

    private DashboardTheme() {}

    /** Prefer Segoe UI / system UI font when available. */
    public static Font uiFont(float size) {
        Font f = resolveUiFace();
        return f.deriveFont(size);
    }

    private static Font resolveUiFace() {
        String[] candidates = {"Segoe UI", "Segoe UI Variable", "Inter", Font.SANS_SERIF};
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        String[] available = ge.getAvailableFontFamilyNames();
        for (String c : candidates) {
            for (String a : available) {
                if (a.equalsIgnoreCase(c)) {
                    return new Font(a, Font.PLAIN, 12);
                }
            }
        }
        Font base = UIManager.getFont("Label.font");
        return base != null ? base : new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    }

    public static Font monoFont(float size) {
        String[] mono = {"JetBrains Mono", "Cascadia Code", "Consolas", Font.MONOSPACED};
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        String[] available = ge.getAvailableFontFamilyNames();
        for (String c : mono) {
            for (String a : available) {
                if (a.equalsIgnoreCase(c)) {
                    return new Font(a, Font.PLAIN, Math.max(10, (int) size));
                }
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, (int) size);
    }

    public static Border cardBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xE2E8F0), 1, true),
                new EmptyBorder(PAD_MD, PAD_MD, PAD_MD, PAD_MD));
    }

    public static JPanel wrapCard(JComponent inner) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(cardBorder());
        p.setOpaque(true);
        p.setBackground(PANEL_ELEVATED);
        p.add(inner, BorderLayout.CENTER);
        return p;
    }
}
