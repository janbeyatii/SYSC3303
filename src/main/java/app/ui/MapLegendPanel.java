package app.ui;

import app.theme.DashboardTheme;

import javax.swing.*;
import java.awt.*;

/**
 * Map color key: zone states and drone markers. Lives outside {@link app.ZoneMapPanel}.
 */
public final class MapLegendPanel extends JPanel {

    public MapLegendPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        add(row("Zone 0 — refill / base", new Color(0x94A3B8)));
        add(Box.createVerticalStrut(6));
        add(row("Queued", DashboardTheme.ZONE_QUEUED));
        add(Box.createVerticalStrut(6));
        add(row("Active fire", DashboardTheme.ZONE_ACTIVE));
        add(Box.createVerticalStrut(6));
        add(row("Cleared", DashboardTheme.ZONE_DONE));
        add(Box.createVerticalStrut(6));
        add(row("En route / return", DashboardTheme.DRONE_EN_ROUTE));
        add(Box.createVerticalStrut(6));
        add(row("Fault / offline", DashboardTheme.DRONE_OFFLINE));

        JLabel foot = new JLabel("Zone sizes from zones.csv (meters)");
        foot.setFont(DashboardTheme.uiFont(9f));
        foot.setForeground(DashboardTheme.TEXT_MUTED);
        foot.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(Box.createVerticalStrut(10));
        add(foot);
    }

    private static JPanel row(String text, Color color) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel swatch = new JPanel();
        swatch.setOpaque(true);
        swatch.setBackground(color);
        swatch.setPreferredSize(new Dimension(16, 12));
        swatch.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x64748B), 1, true),
                BorderFactory.createEmptyBorder(0, 0, 0, 0)));

        JLabel lbl = new JLabel(text);
        lbl.setFont(DashboardTheme.uiFont(11f));
        lbl.setForeground(DashboardTheme.TEXT_PRIMARY);

        row.add(swatch);
        row.add(lbl);
        return row;
    }
}
