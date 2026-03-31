package app.ui;

import app.theme.DashboardTheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;

/**
 * Renders incident status or drone state as a rounded pill for readability.
 */
public final class BadgeCellRenderer extends DefaultTableCellRenderer {

    public enum Kind {
        INCIDENT_STATUS,
        DRONE_STATE
    }

    private final Kind kind;

    public BadgeCellRenderer(Kind kind) {
        this.kind = kind;
        setHorizontalAlignment(SwingConstants.CENTER);
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                   boolean hasFocus, int row, int column) {
        JLabel c = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        String text = value == null ? "" : value.toString();
        c.setText(text);
        c.setOpaque(true);
        c.setBorder(new EmptyBorder(2, 8, 2, 8));
        if (isSelected) {
            c.setBackground(table.getSelectionBackground());
            c.setForeground(table.getSelectionForeground());
            return c;
        }
        Color bg;
        Color fg;
        if (kind == Kind.INCIDENT_STATUS) {
            switch (text) {
                case "QUEUED":
                    bg = DashboardTheme.BADGE_QUEUED_BG;
                    fg = DashboardTheme.BADGE_QUEUED_FG;
                    break;
                case "DISPATCHED":
                    bg = DashboardTheme.BADGE_DISPATCHED_BG;
                    fg = DashboardTheme.BADGE_DISPATCHED_FG;
                    break;
                case "COMPLETED":
                    bg = DashboardTheme.BADGE_COMPLETED_BG;
                    fg = DashboardTheme.BADGE_COMPLETED_FG;
                    break;
                default:
                    bg = table.getBackground();
                    fg = table.getForeground();
            }
        } else {
            String u = text.toUpperCase();
            if ("IDLE".equals(u)) {
                bg = DashboardTheme.BADGE_DRONE_IDLE_BG;
                fg = DashboardTheme.BADGE_DRONE_IDLE_FG;
            } else if ("OFFLINE".equals(u) || "FAULTED".equals(u)) {
                bg = DashboardTheme.BADGE_DRONE_FAULT_BG;
                fg = DashboardTheme.BADGE_DRONE_FAULT_FG;
            } else if ("UNAVAILABLE".equals(u)) {
                bg = new Color(0xFFFDE7);
                fg = new Color(0xF57F17);
            } else if ("EN_ROUTE".equals(u) || "RETURNING".equals(u) || "ARRIVED".equals(u) || "EXTINGUISHING".equals(u)) {
                bg = DashboardTheme.BADGE_DRONE_BUSY_BG;
                fg = DashboardTheme.BADGE_DRONE_BUSY_FG;
            } else {
                bg = table.getBackground();
                fg = table.getForeground();
            }
        }
        c.setBackground(bg);
        c.setForeground(fg);
        return c;
    }
}
