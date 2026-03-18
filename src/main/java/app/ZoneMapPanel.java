package app;

import model.Zone;
import model.ZoneConfig;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Panel that displays zones from zones.csv with proportional sizes, plus drones.
 * Zone layout and sizes match the coordinate bounds (x1,y1,x2,y2) from the CSV.
 * Supports animated movement: drones are shown moving through zones to/from base.
 */
public class ZoneMapPanel extends JPanel {

    private static final int BASE_ZONE = 0;
    private static final int PADDING = 8;
    /** Virtual canvas size for coordinate mapping - ensures proportions are preserved. */
    private static final int CANVAS_W = 400;
    private static final int CANVAS_H = 400;

    private final ZoneConfig zoneConfig;
    private Map<Integer, Integer> droneZones = new HashMap<>();
    private Map<Integer, String> droneStates = new HashMap<>();
    private Map<Integer, DroneAnimation> droneAnimations = new HashMap<>();
    private Map<Integer, String> zoneIncidentStatus = new HashMap<>();

    private final Timer animationTimer;

    /** World bounds (meters) from zone config. */
    private double worldMinX, worldMinY, worldMaxX, worldMaxY;

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

        /** 0.0 to 1.0 */
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
        setBorder(BorderFactory.createTitledBorder("Zone Map"));
        setPreferredSize(new Dimension(380, 380));
        setMinimumSize(new Dimension(200, 200));
        setBackground(new Color(0xf5f5f5));
        animationTimer = new Timer(120, e -> repaint());
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

    /** Compute current display zone for a drone (including animation). Returns zone id. */
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

    /** Map world (meters) to virtual canvas pixels (CANVAS_W x CANVAS_H). */
    private int toCanvasX(double worldX) {
        double w = worldMaxX - worldMinX;
        if (w <= 0) return PADDING;
        return PADDING + (int) ((worldX - worldMinX) / w * (CANVAS_W - 2 * PADDING));
    }

    private int toCanvasY(double worldY) {
        double h = worldMaxY - worldMinY;
        if (h <= 0) return PADDING;
        return PADDING + (int) ((worldY - worldMinY) / h * (CANVAS_H - 2 * PADDING));
    }

    /** Get zone rect in virtual canvas coordinates. */
    private Rectangle zoneToCanvas(Zone z) {
        int x1 = toCanvasX(z.getX1());
        int y1 = toCanvasY(z.getY1());
        int x2 = toCanvasX(z.getX2());
        int y2 = toCanvasY(z.getY2());
        return new Rectangle(
                Math.min(x1, x2),
                Math.min(y1, y2),
                Math.abs(x2 - x1),
                Math.abs(y2 - y1)
        );
    }

    /** Get zone center in virtual canvas coordinates. */
    private Point zoneCenterToCanvas(int zoneId) {
        Zone z = zoneConfig.getZoneOrNull(zoneId);
        if (z == null) return new Point(CANVAS_W / 2, CANVAS_H / 2);
        return new Point(toCanvasX(z.getCenterX()), toCanvasY(z.getCenterY()));
    }

    /** Interpolated canvas position for a drone (handles animation). */
    private Point getDroneCanvasPos(int droneId, long nowMs) {
        DroneAnimation anim = droneAnimations.get(droneId);
        if (anim != null && anim.isActive(nowMs)) {
            double p = anim.progress(nowMs);
            Zone from = zoneConfig.getZoneOrNull(anim.fromZone);
            Zone to = zoneConfig.getZoneOrNull(anim.toZone);
            if (from != null && to != null) {
                double wx = from.getCenterX() + (to.getCenterX() - from.getCenterX()) * p;
                double wy = from.getCenterY() + (to.getCenterY() - from.getCenterY()) * p;
                return new Point(toCanvasX(wx), toCanvasY(wy));
            }
        }
        Integer zoneId = droneZones.get(droneId);
        return zoneCenterToCanvas(zoneId != null ? zoneId : BASE_ZONE);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        long nowMs = System.currentTimeMillis();

        // Scale from virtual canvas (CANVAS_W x CANVAS_H) to panel - preserves zone proportions
        double scaleX = w > 0 ? (double) w / CANVAS_W : 1.0;
        double scaleY = h > 0 ? (double) h / CANVAS_H : 1.0;
        g2.scale(scaleX, scaleY);

        // Draw each zone from config (proportional sizes from zones.csv)
        for (Integer zoneId : zoneConfig.getZoneIds()) {
            Zone z = zoneConfig.getZoneOrNull(zoneId);
            if (z == null) continue;
            Rectangle r = zoneToCanvas(z);
            if (r.width < 2 || r.height < 2) continue;

            String status = zoneIncidentStatus.get(zoneId);
            Color bg = cellBackground(status);
            g2.setColor(bg);
            g2.fillRect(r.x + 1, r.y + 1, r.width - 2, r.height - 2);

            g2.setColor(new Color(0x444444));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRect(r.x + 1, r.y + 1, r.width - 2, r.height - 2);

            g2.setColor(Color.DARK_GRAY);
            g2.setFont(getFont().deriveFont(Font.BOLD, Math.min(14f, Math.max(8f, r.height / 4f))));
            FontMetrics fm = g2.getFontMetrics();
            String label = zoneId == BASE_ZONE ? "BASE" : String.valueOf(zoneId);
            g2.drawString(label, r.x + (r.width - fm.stringWidth(label)) / 2, r.y + r.height / 2 - 4);

            if (status != null && !status.isEmpty()) {
                g2.setFont(getFont().deriveFont(Font.PLAIN, Math.min(10f, r.height / 5f)));
                FontMetrics fmSmall = g2.getFontMetrics();
                g2.setColor(status.equals("COMPLETED") ? new Color(0x1b5e20) : Color.DARK_GRAY);
                String statusShort = status.equals("DISPATCHED") ? "FIRE" : status.equals("QUEUED") ? "QUEUED" : "DONE";
                g2.drawString(statusShort, r.x + (r.width - fmSmall.stringWidth(statusShort)) / 2, r.y + r.height / 2 + fmSmall.getAscent());
            }
        }

        // Draw drones (at zone centers or interpolated during animation)
        paintDrones(g2, nowMs);

        // Stop timer when no animation left
        boolean anyActive = false;
        for (DroneAnimation a : droneAnimations.values()) {
            if (a.isActive(nowMs)) { anyActive = true; break; }
        }
        if (!anyActive && animationTimer.isRunning()) animationTimer.stop();
    }

    private void paintDrones(Graphics2D g2, long nowMs) {
        if (droneZones.isEmpty()) return;

        g2.setFont(getFont().deriveFont(Font.BOLD, 10f));
        int badgeW = 28;
        int badgeH = 16;

        // Group drones by display zone to avoid overlap
        Map<Integer, List<Integer>> dronesByZone = new HashMap<>();
        for (Integer droneId : droneZones.keySet()) {
            int zoneId = getDisplayZone(droneId, nowMs);
            dronesByZone.computeIfAbsent(zoneId, k -> new ArrayList<>()).add(droneId);
        }

        for (Map.Entry<Integer, List<Integer>> e : dronesByZone.entrySet()) {
            List<Integer> drones = e.getValue();
            int zoneId = e.getKey();
            Point base = zoneCenterToCanvas(zoneId);
            int startX = base.x - (drones.size() * (badgeW + 4)) / 2;
            for (int i = 0; i < drones.size(); i++) {
                int droneId = drones.get(i);
                Point pos = getDroneCanvasPos(droneId, nowMs);
                int dx = (drones.size() == 1) ? pos.x - badgeW / 2 : startX + i * (badgeW + 4);
                int dy = pos.y - badgeH / 2;
                String state = droneStates.get(droneId);
                boolean enRoute = "EN_ROUTE".equals(state) || "RETURNING".equals(state);
                g2.setColor(enRoute ? new Color(0xff9800) : new Color(0x1976d2));
                g2.fillRoundRect(dx, dy, badgeW, badgeH, 4, 4);
                g2.setColor(Color.WHITE);
                g2.drawString("D" + droneId, dx + 4, dy + 12);
            }
        }
    }

    private Color cellBackground(String status) {
        if (status == null) return new Color(0xe8e8e8);
        switch (status) {
            case "QUEUED":
                return new Color(0xffecb3);
            case "DISPATCHED":
                return new Color(0xffcdd2);
            case "COMPLETED":
                return new Color(0xc8e6c9);
            default:
                return new Color(0xe8e8e8);
        }
    }
}
