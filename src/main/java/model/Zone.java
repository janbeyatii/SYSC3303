package model;

/**
 * Represents a rectangular zone with coordinates in meters.
 * Zones are read from the zone coordinates file (e.g. zones.csv).
 */
public class Zone {
    private final int zoneId;
    private final double x1, y1, x2, y2;

    public Zone(int zoneId, double x1, double y1, double x2, double y2) {
        this.zoneId = zoneId;
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
    }

    public int getZoneId() {
        return zoneId;
    }

    public double getX1() { return x1; }
    public double getY1() { return y1; }
    public double getX2() { return x2; }
    public double getY2() { return y2; }

    /** Center X of the rectangle (meters). */
    public double getCenterX() {
        return (x1 + x2) / 2.0;
    }

    /** Center Y of the rectangle (meters). */
    public double getCenterY() {
        return (y1 + y2) / 2.0;
    }

    /** Euclidean distance from this zone's center to another zone's center (meters). */
    public double distanceTo(Zone other) {
        double dx = getCenterX() - other.getCenterX();
        double dy = getCenterY() - other.getCenterY();
        return Math.sqrt(dx * dx + dy * dy);
    }
}
