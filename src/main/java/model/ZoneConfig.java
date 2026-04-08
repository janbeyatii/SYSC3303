package model;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads zone coordinates from a CSV file and provides distance calculations.
 * <p>
 * Supported formats:
 * <ul>
 *   <li><b>Rectangle</b>: {@code Zone ID,x1,y1,x2,y2} (header row, then one zone per line)</li>
 *   <li><b>Start/End</b>: {@code Zone ID,Zone Start,Zone End} with pairs like {@code (x;y),(x;y)}
 *       (e.g. course final {@code final_zone_file_w26.csv})</li>
 * </ul>
 * Coordinates are in meters. Distance is Euclidean between zone centers.
 * If the file does not define zone 0 (base/refill), a default base rectangle is added.
 */
public class ZoneConfig {
    private static final String DEFAULT_ZONES_PATH = "data/final_zone_file_w26.csv";

    private static final Pattern PAREN_COORD_PAIR = Pattern.compile(
            "\\(\\s*([0-9.eE+-]+)\\s*;\\s*([0-9.eE+-]+)\\s*\\)");

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
                throw new IllegalArgumentException("Zone file must have a header row containing \"Zone\"");
            }
            String h = header.toLowerCase();
            boolean startEndFormat = h.contains("zone start") && h.contains("zone end");

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (startEndFormat) {
                    parseStartEndLine(line);
                } else {
                    parseRectLine(line);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load zone config from " + filePath + ": " + e.getMessage(), e);
        }
        ensureBaseZone();
        if (zonesById.isEmpty()) {
            throw new IllegalStateException("No zones loaded from " + filePath);
        }
    }

    /** Parses {@code Zone ID,(x1;y1),(x2;y2)} style lines (e.g. {@code 1,(0;0),(900;900)}). */
    private void parseStartEndLine(String line) {
        int comma = line.indexOf(',');
        if (comma < 0) return;
        int zoneId;
        try {
            zoneId = Integer.parseInt(line.substring(0, comma).trim());
        } catch (NumberFormatException e) {
            return;
        }
        Matcher m = PAREN_COORD_PAIR.matcher(line);
        if (!m.find()) return;
        double x1 = Double.parseDouble(m.group(1));
        double y1 = Double.parseDouble(m.group(2));
        if (!m.find()) return;
        double x2 = Double.parseDouble(m.group(1));
        double y2 = Double.parseDouble(m.group(2));
        zonesById.put(zoneId, new Zone(zoneId, x1, y1, x2, y2));
    }

    /** Parses {@code Zone ID,x1,y1,x2,y2} (comma-separated numbers). */
    private void parseRectLine(String line) {
        String[] parts = line.split(",");
        if (parts.length < 5) return;
        try {
            int zoneId = Integer.parseInt(parts[0].trim());
            double x1 = Double.parseDouble(parts[1].trim());
            double y1 = Double.parseDouble(parts[2].trim());
            double x2 = Double.parseDouble(parts[3].trim());
            double y2 = Double.parseDouble(parts[4].trim());
            zonesById.put(zoneId, new Zone(zoneId, x1, y1, x2, y2));
        } catch (NumberFormatException ignored) {
        }
    }

    /** When zone 0 is not in the file, add a default base/refill rectangle (south-west of typical grid). */
    private void ensureBaseZone() {
        if (!zonesById.containsKey(0)) {
            zonesById.put(0, new Zone(0, -350, -300, 0, 0));
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
