package app;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Loads simulation configuration from config.properties.
 * Drone count is configurable at startup (no manual add during simulation per project spec).
 */
public class SimConfig {
    private static final String DEFAULT_CONFIG_PATH = "data/config.properties";

    private final int numDrones;
    private final double droneTimeScale;
    private final String schedulerHost;
    private final int schedulerPort;
    private final int agentCapacity;

    public SimConfig() {
        this(DEFAULT_CONFIG_PATH);
    }

    public SimConfig(String configPath) {
        Properties props = new Properties();
        Path path = Paths.get(configPath);
        if (!path.toFile().exists()) {
            path = Paths.get(System.getProperty("user.dir", "."), configPath);
        }
        if (path.toFile().exists()) {
            try (FileInputStream fis = new FileInputStream(path.toFile())) {
                props.load(fis);
            } catch (IOException e) {
                System.err.println("[SimConfig] Could not load " + configPath + ": " + e.getMessage());
            }
        }
        this.numDrones = Math.max(1, Integer.parseInt(props.getProperty("numDrones", "10")));
        this.droneTimeScale = parseTimeScale(props.getProperty("droneTimeScale", "0.01"));
        this.schedulerHost = props.getProperty("schedulerHost", "127.0.0.1");
        this.schedulerPort = Integer.parseInt(props.getProperty("schedulerPort", "5000"));
        this.agentCapacity = Math.max(10, Math.min(100, Integer.parseInt(props.getProperty("agentCapacity", "100"))));
    }

    private static double parseTimeScale(String s) {
        try {
            double v = Double.parseDouble(s.trim());
            return v > 0 && v <= 1.0 ? v : 0.01;
        } catch (NumberFormatException e) {
            return 0.01;
        }
    }

    public int getNumDrones() {
        return numDrones;
    }

    public double getDroneTimeScale() {
        return droneTimeScale;
    }

    public String getSchedulerHost() {
        return schedulerHost;
    }

    public int getSchedulerPort() {
        return schedulerPort;
    }

    /** Agent (water/foam) capacity per drone in litres (spec: 10–100 L). */
    public int getAgentCapacity() {
        return agentCapacity;
    }
}
