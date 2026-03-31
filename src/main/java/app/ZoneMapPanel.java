package app;

import app.theme.DashboardTheme;
import model.Zone;
import model.ZoneConfig;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Panel that draws zones from {@code zones.csv} using <b>uniform world scaling</b> (meters),
 * so relative sizes match x1,y1,x2,y2 (e.g. BASE is smaller than fire grid cells when the CSV says so).
 */
public class ZoneMapPanel extends JPanel {

    private static final int BASE_ZONE = 0;
    private static final int MARGIN = 14;
    private static final float ZONE_CORNER = 14f;

    private final ZoneConfig zoneConfig;
    private Map<Integer, Integer> droneZones = new HashMap<>();
    private Map<Integer, String> droneStates = new HashMap<>();
    private Map<Integer, DroneAnimation> droneAnimations = new HashMap<>();
    private Map<Integer, String> zoneIncidentStatus = new HashMap<>();

    private final Timer animationTimer;

    private double worldMinX;
    private double worldMinY;
    private double worldMaxX;
    private double worldMaxY;

    public static final class DroneAnimation {
        final int fromZone;
        final int toZone;
        final long startMs;
        final long durationMs;

        public DroneAnimation(int fromZone, int toZone, long startMs, long durationMs) {
            this.fromZone = fromZone;
            this.toZone = toZone;
            this.startMs = startMs;
            this.durationMs = durationMs;
        }

        boolean isActive(long nowMs) {
            return durationMs > 0 && (nowMs - startMs) < durationMs;
        }

        double progress(long nowMs) {
            if (durationMs <= 0) return 1.0;
            long elapsed = nowMs - startMs;
            if (elapsed >= durationMs) return 1.0;
            return (double) elapsed / durationMs;
        }
    }

    public ZoneMapPanel() {
        this(new ZoneConfig());
    }

    public ZoneMapPanel(ZoneConfig zoneConfig) {
        this.zoneConfig = zoneConfig != null ? zoneConfig : new ZoneConfig();
        setBorder(new EmptyBorder(6, 6, 6, 6));
        setOpaque(true);
        setBackground(DashboardTheme.MAP_CANVAS_BG);
        animationTimer = new Timer(48, e -> repaint());
        computeWorldBounds();
    }

    private void computeWorldBounds() {
        worldMinX = Double.MAX_VALUE;
        worldMinY = Double.MAX_VALUE;
        worldMaxX = -Double.MAX_VALUE;
        worldMaxY = -Double.MAX_VALUE;
        for (Integer zoneId : zoneConfig.getZoneIds()) {
            Zone z = zoneConfig.getZoneOrNull(zoneId);
            if (z == null) continue;
            worldMinX = Math.min(worldMinX, z.getX1());
            worldMinY = Math.min(worldMinY, z.getY1());
            worldMaxX = Math.max(worldMaxX, z.getX2());
            worldMaxY = Math.max(worldMaxY, z.getY2());
        }
        if (worldMinX > worldMaxX) {
            worldMinX = 0;
            worldMaxX = 2100;
            worldMinY = 0;
            worldMaxY = 1800;
        }
    }

    private double worldWidth() {
        return Math.max(1e-6, worldMaxX - worldMinX);
    }

    private double worldHeight() {
        return Math.max(1e-6, worldMaxY - worldMinY);
    }

    /**
     * Uniform scale + centered: preserves aspect ratio of the world (meters).
     */
    private double computeUniformScale(int panelW, int panelH) {
        double ww = worldWidth();
        double wh = worldHeight();
        double innerW = Math.max(1, panelW - 2 * MARGIN);
        double innerH = Math.max(1, panelH - 2 * MARGIN);
        return Math.min(innerW / ww, innerH / wh);
    }

    private double offsetX(int panelW, double scale) {
        double ww = worldWidth();
        return MARGIN + (panelW - 2 * MARGIN - ww * scale) / 2.0;
    }

    private double offsetY(int panelH, double scale) {
        double wh = worldHeight();
        return MARGIN + (panelH - 2 * MARGIN - wh * scale) / 2.0;
    }

    private double worldToScreenX(double wx, double ox, double scale) {
        return ox + (wx - worldMinX) * scale;
    }

    private double worldToScreenY(double wy, double oy, double scale) {
        return oy + (wy - worldMinY) * scale;
    }

    private RoundRectangle2D worldRectToRoundRect(Zone z, double ox, double oy, double scale) {
        double x1 = worldToScreenX(z.getX1(), ox, scale);
        double y1 = worldToScreenY(z.getY1(), oy, scale);
        double x2 = worldToScreenX(z.getX2(), ox, scale);
        double y2 = worldToScreenY(z.getY2(), oy, scale);
        double rx = Math.min(x1, x2);
        double ry = Math.min(y1, y2);
        double rw = Math.abs(x2 - x1);
        double rh = Math.abs(y2 - y1);
        float arc = Math.min(ZONE_CORNER, (float) Math.min(rw, rh) / 4f);
        return new RoundRectangle2D.Double(rx, ry, rw, rh, arc, arc);
    }

    private Point2D worldPointToScreen(double wx, double wy, double ox, double oy, double scale) {
        return new Point2D.Double(worldToScreenX(wx, ox, scale), worldToScreenY(wy, oy, scale));
    }

    public void setDroneZones(Map<Integer, Integer> droneZones) {
        this.droneZones = droneZones != null ? new HashMap<>(droneZones) : new HashMap<>();
    }

    public void setDroneStates(Map<Integer, String> droneStates) {
        this.droneStates = droneStates != null ? new HashMap<>(droneStates) : new HashMap<>();
    }

    public void setDroneAnimations(Map<Integer, DroneAnimation> droneAnimations) {
        this.droneAnimations = droneAnimations != null ? new HashMap<>(droneAnimations) : new HashMap<>();
        boolean anyActive = false;
        long now = System.currentTimeMillis();
        for (DroneAnimation a : this.droneAnimations.values()) {
            if (a.isActive(now)) {
                anyActive = true;
                break;
            }
        }
        if (anyActive && !animationTimer.isRunning()) animationTimer.start();
    }

    public void setZoneIncidentStatus(Map<Integer, String> zoneIncidentStatus) {
        this.zoneIncidentStatus = zoneIncidentStatus != null ? new HashMap<>(zoneIncidentStatus) : new HashMap<>();
    }

    private int getDisplayZone(int droneId, long nowMs) {
        DroneAnimation anim = droneAnimations.get(droneId);
        if (anim != null && anim.isActive(nowMs)) {
            double p = anim.progress(nowMs);
            double z = anim.fromZone + (anim.toZone - anim.fromZone) * p;
            return (int) Math.round(z);
        }
        Integer z = droneZones.get(droneId);
        return (z != null) ? z : BASE_ZONE;
    }

    private Point2D zoneCenterScreen(int zoneId, double ox, double oy, double scale) {
        Zone z = zoneConfig.getZoneOrNull(zoneId);
        if (z == null) {
            return new Point2D.Double(
                    worldToScreenX((worldMinX + worldMaxX) / 2, ox, scale),
                    worldToScreenY((worldMinY + worldMaxY) / 2, oy, scale));
        }
        return worldPointToScreen(z.getCenterX(), z.getCenterY(), ox, oy, scale);
    }

    private Point2D getDroneScreenPos(int droneId, long nowMs, double ox, double oy, double scale) {
        DroneAnimation anim = droneAnimations.get(droneId);
        if (anim != null && anim.isActive(nowMs)) {
            double p = anim.progress(nowMs);
            Zone from = zoneConfig.getZoneOrNull(anim.fromZone);
            Zone to = zoneConfig.getZoneOrNull(anim.toZone);
            if (from != null && to != null) {
                double wx = from.getCenterX() + (to.getCenterX() - from.getCenterX()) * p;
                double wy = from.getCenterY() + (to.getCenterY() - from.getCenterY()) * p;
                return worldPointToScreen(wx, wy, ox, oy, scale);
            }
        }
        Integer zoneId = droneZones.get(droneId);
        return zoneCenterScreen(zoneId != null ? zoneId : BASE_ZONE, ox, oy, scale);
    }

    private void paintRouteLines(Graphics2D g2, long nowMs, double ox, double oy, double scale) {
        float pulse = (float) (0.42 + 0.22 * Math.sin(nowMs / 280.0));
        g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Map.Entry<Integer, DroneAnimation> e : droneAnimations.entrySet()) {
            DroneAnimation anim = e.getValue();
            if (!anim.isActive(nowMs)) continue;
            Point2D a = zoneCenterScreen(anim.fromZone, ox, oy, scale);
            Point2D b = zoneCenterScreen(anim.toZone, ox, oy, scale);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, pulse));
            g2.setColor(new Color(14, 165, 233, 180));
            g2.drawLine((int) a.getX(), (int) a.getY(), (int) b.getX(), (int) b.getY());
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
            g2.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine((int) a.getX(), (int) a.getY(), (int) b.getX(), (int) b.getY());
            g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setComposite(AlphaComposite.SrcOver);
            double p = anim.progress(nowMs);
            int mx = (int) (a.getX() + (b.getX() - a.getX()) * p);
            int my = (int) (a.getY() + (b.getY() - a.getY()) * p);
            g2.setColor(new Color(0x0369A1));
            g2.fillOval(mx - 5, my - 5, 10, 10);
            g2.setColor(Color.WHITE);
            g2.fillOval(mx - 2, my - 2, 4, 4);
        }
    }

    private void fillZoneGradient(Graphics2D g2, RoundRectangle2D rr, Color top, Color bottom) {
        GradientPaint gp = new GradientPaint(
                (float) rr.getX(), (float) rr.getY(), top,
                (float) (rr.getX() + rr.getWidth()), (float) (rr.getY() + rr.getHeight()), bottom);
        g2.setPaint(gp);
        g2.fill(rr);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int w = getWidth();
        int h = getHeight();
        long nowMs = System.currentTimeMillis();

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

        double scale = computeUniformScale(w, h);
        double ox = offsetX(w, scale);
        double oy = offsetY(h, scale);

        g2.setColor(getBackground());
        g2.fillRect(0, 0, w, h);

        double ww = worldWidth() * scale;
        double wh = worldHeight() * scale;
        g2.setColor(new Color(0xFFFFFF));
        g2.fillRoundRect((int) ox - 2, (int) oy - 2, (int) ww + 4, (int) wh + 4, 4, 4);
        g2.setColor(new Color(0xCBD5E1));
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect((int) ox - 2, (int) oy - 2, (int) ww + 4, (int) wh + 4, 4, 4);

        for (Integer zoneId : zoneConfig.getZoneIds()) {
            Zone z = zoneConfig.getZoneOrNull(zoneId);
            if (z == null) continue;
            RoundRectangle2D rr = worldRectToRoundRect(z, ox, oy, scale);
            if (rr.getWidth() < 2 || rr.getHeight() < 2) continue;

            String status = zoneIncidentStatus.get(zoneId);
            Color c1 = zoneTopColor(status);
            Color c2 = zoneBottomColor(status);

            fillZoneGradient(g2, rr, c1, c2);

            g2.setColor(new Color(148, 163, 184, 120));
            g2.setStroke(new BasicStroke(1.2f));
            g2.draw(rr);

            Font labelFont = DashboardTheme.uiFont(Math.min(15f, (float) Math.max(10, rr.getHeight() / 5)));
            g2.setFont(labelFont);
            FontMetrics fm = g2.getFontMetrics();
            String label = zoneId == BASE_ZONE ? "BASE" : String.valueOf(zoneId);
            float cx = (float) (rr.getX() + rr.getWidth() / 2 - fm.stringWidth(label) / 2);
            float cy = (float) (rr.getY() + rr.getHeight() / 2 - fm.getHeight() / 2 + fm.getAscent());
            g2.setColor(new Color(0x1E293B));
            g2.drawString(label, cx, cy);

            int mw = (int) (Math.abs(z.getX2() - z.getX1()));
            int mh = (int) (Math.abs(z.getY2() - z.getY1()));
            String dim = mw + "×" + mh + " m";
            Font small = DashboardTheme.uiFont(Math.min(11f, (float) Math.max(8, rr.getHeight() / 7)));
            g2.setFont(small);
            FontMetrics fms = g2.getFontMetrics();
            g2.setColor(new Color(0x64748B));
            float dx = (float) (rr.getX() + rr.getWidth() / 2 - fms.stringWidth(dim) / 2);
            float dy = (float) (rr.getY() + rr.getHeight() - fms.getDescent() - 4);
            if (rr.getHeight() > 28) {
                g2.drawString(dim, dx, dy);
            }

            if (status != null && !status.isEmpty()) {
                String statusShort = status.equals("DISPATCHED") ? "FIRE" : status.equals("QUEUED") ? "QUEUED" : "DONE";
                g2.setFont(DashboardTheme.uiFont(Math.min(11f, (float) Math.max(8, rr.getHeight() / 6))));
                FontMetrics fmS = g2.getFontMetrics();
                g2.setColor(status.equals("COMPLETED") ? new Color(0x166534) : new Color(0x475569));
                float sx = (float) (rr.getX() + rr.getWidth() / 2 - fmS.stringWidth(statusShort) / 2);
                float sy = cy + fm.getDescent() + 6 + fmS.getAscent();
                if (sy < rr.getY() + rr.getHeight() - 8) {
                    g2.drawString(statusShort, sx, sy);
                }
            }
        }

        paintRouteLines(g2, nowMs, ox, oy, scale);
        paintDrones(g2, nowMs, ox, oy, scale);

        boolean anyActive = false;
        for (DroneAnimation a : droneAnimations.values()) {
            if (a.isActive(nowMs)) {
                anyActive = true;
                break;
            }
        }
        if (!anyActive && animationTimer.isRunning()) animationTimer.stop();

        g2.dispose();
    }

    private void paintDrones(Graphics2D g2, long nowMs, double ox, double oy, double scale) {
        if (droneZones.isEmpty()) return;

        g2.setFont(DashboardTheme.uiFont(10f));
        int badgeW = 34;
        int badgeH = 18;

        Map<Integer, List<Integer>> dronesByZone = new HashMap<>();
        for (Integer droneId : droneZones.keySet()) {
            int zoneId = getDisplayZone(droneId, nowMs);
            dronesByZone.computeIfAbsent(zoneId, k -> new ArrayList<>()).add(droneId);
        }

        for (Map.Entry<Integer, List<Integer>> e : dronesByZone.entrySet()) {
            List<Integer> drones = e.getValue();
            Point2D anchor = zoneCenterScreen(e.getKey(), ox, oy, scale);
            int startX = (int) (anchor.getX() - (drones.size() * (badgeW + 4)) / 2);
            for (int i = 0; i < drones.size(); i++) {
                int droneId = drones.get(i);
                Point2D pos = getDroneScreenPos(droneId, nowMs, ox, oy, scale);
                int dx = (drones.size() == 1) ? (int) (pos.getX() - badgeW / 2) : startX + i * (badgeW + 4);
                int dy = (int) (pos.getY() - badgeH / 2);
                String state = droneStates.get(droneId);

                Color droneColor = droneColorForState(state);
                g2.setColor(new Color(0, 0, 0, 40));
                g2.fillRoundRect(dx + 2, dy + 2, badgeW, badgeH, 8, 8);
                g2.setColor(droneColor);
                g2.fillRoundRect(dx, dy, badgeW, badgeH, 8, 8);
                g2.setColor(new Color(0xFFFFFF));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(dx, dy, badgeW, badgeH, 8, 8);
                g2.setColor(Color.WHITE);
                g2.drawString("D" + droneId, dx + 6, dy + 14);

                String caption = captionForDrone(droneId, state, nowMs);
                g2.setColor(new Color(0x0F172A));
                g2.setFont(DashboardTheme.uiFont(9f));
                g2.drawString(caption, dx + badgeW + 5, dy + 13);
            }
        }
    }

    private String captionForDrone(int droneId, String state, long nowMs) {
        if (state == null) return "";
        DroneAnimation anim = droneAnimations.get(droneId);
        switch (state) {
            case "EN_ROUTE":
                if (anim != null && anim.isActive(nowMs))
                    return "\u2192 Z" + anim.toZone;
                return "\u2192 Z" + droneZones.getOrDefault(droneId, 0);
            case "RETURNING":
                return "\u2192 BASE";
            case "ARRIVED":
            case "EXTINGUISHING":
                return "@ Z" + droneZones.getOrDefault(droneId, 0);
            case "IDLE":
                return "@ BASE";
            default:
                return state;
        }
    }

    private static Color droneColorForState(String state) {
        if (state == null) return DashboardTheme.DRONE_DEFAULT;
        switch (state) {
            case "OFFLINE":
                return DashboardTheme.DRONE_OFFLINE;
            case "UNAVAILABLE":
                return DashboardTheme.DRONE_UNAVAILABLE;
            case "FAULTED":
                return DashboardTheme.DRONE_FAULTED;
            case "EN_ROUTE":
            case "RETURNING":
                return DashboardTheme.DRONE_EN_ROUTE;
            default:
                return DashboardTheme.DRONE_DEFAULT;
        }
    }

    private Color zoneTopColor(String status) {
        if (status == null) return DashboardTheme.ZONE_NEUTRAL;
        switch (status) {
            case "QUEUED":
                return DashboardTheme.ZONE_QUEUED;
            case "DISPATCHED":
                return DashboardTheme.ZONE_ACTIVE;
            case "COMPLETED":
                return DashboardTheme.ZONE_DONE;
            default:
                return DashboardTheme.ZONE_NEUTRAL;
        }
    }

    private Color zoneBottomColor(String status) {
        if (status == null) return DashboardTheme.ZONE_NEUTRAL_DARK;
        switch (status) {
            case "QUEUED":
                return DashboardTheme.ZONE_QUEUED_DARK;
            case "DISPATCHED":
                return DashboardTheme.ZONE_ACTIVE_DARK;
            case "COMPLETED":
                return DashboardTheme.ZONE_DONE_DARK;
            default:
                return DashboardTheme.ZONE_NEUTRAL_DARK;
        }
    }

}
