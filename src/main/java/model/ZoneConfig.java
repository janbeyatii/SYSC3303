package model;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads zone coordinates from a CSV file and provides distance calculations.
 * File format: Zone ID,x1,y1,x2,y2 (header row, then one zone per line)
 * Coordinates are in meters. Distance is Euclidean between zone centers.
 */
public class ZoneConfig {
    private static final String DEFAULT_ZONES_PATH = "data/zones.csv";

    private final Map<Integer, Zone> zonesById = new HashMap<>();

    public ZoneConfig() {
        this(DEFAULT_ZONES_PATH);
    }

    public ZoneConfig(String filePath) {
        loadFromFile(filePath);
    }

    private void loadFromFile(String filePath) {
        Path path = Paths.get(filePath);
        if (!path.toFile().exists()) {
            path = Paths.get(System.getProperty("user.dir", "."), filePath);
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(path.toFile()))) {
            String header = reader.readLine();
            if (header == null || !header.toLowerCase().contains("zone")) {
                throw new IllegalArgumentException("Zone file must have header: Zone ID,x1,y1,x2,y2");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length < 5) continue;
                int zoneId = Integer.parseInt(parts[0].trim());
                double x1 = Double.parseDouble(parts[1].trim());
                double y1 = Double.parseDouble(parts[2].trim());
                double x2 = Double.parseDouble(parts[3].trim());
                double y2 = Double.parseDouble(parts[4].trim());
                zonesById.put(zoneId, new Zone(zoneId, x1, y1, x2, y2));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load zone config from " + filePath + ": " + e.getMessage(), e);
        }
    }

    public Zone getZone(int zoneId) {
        Zone z = zonesById.get(zoneId);
        if (z == null) {
            throw new IllegalArgumentException("Unknown zone ID: " + zoneId);
        }
        return z;
    }

    /** Returns distance in meters between the centers of two zones. */
    public double getDistanceMeters(int fromZoneId, int toZoneId) {
        Zone from = getZone(fromZoneId);
        Zone to = getZone(toZoneId);
        return from.distanceTo(to);
    }

    /** Returns true if the zone config has the given zone. */
    public boolean hasZone(int zoneId) {
        return zonesById.containsKey(zoneId);
    }

    /** Returns zone by id, or null if not found. */
    public Zone getZoneOrNull(int zoneId) {
        return zonesById.get(zoneId);
    }

    /** Returns all zone IDs for iteration (e.g. for map rendering). */
    public java.util.Set<Integer> getZoneIds() {
        return java.util.Collections.unmodifiableSet(zonesById.keySet());
    }
}
