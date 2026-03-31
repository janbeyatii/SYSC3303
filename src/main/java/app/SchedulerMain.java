package app;

import com.formdev.flatlaf.FlatLightLaf;
import fireincident.Scheduler;
import fireincident.udp.Ports;

import javax.swing.*;

/**
 * Entry point for Scheduler + GUI with configurable drone fleet (Iteration 3 – distributed execution).
 * Reads data/config.properties for numDrones, droneTimeScale, etc. Spawns that many DroneMain
 * processes at startup. Per project spec: no manual add-drones button during simulation.
 * <p>
 * Usage: java app.SchedulerMain [defaultCsvPath]
 * <p>
 * Then run FireIncidentMain in another process to send incidents, or use the GUI Start button.
 *
 * @param args optional default incident CSV path for the GUI text field
 */
public class SchedulerMain {

    private static final String DEFAULT_CSV = "data/iteration4/iter4_fault_mixed.csv";

    public static void main(String[] args) {
        String csvPath = (args != null && args.length > 0 && args[0] != null && !args[0].isEmpty())
                ? args[0].trim()
                : DEFAULT_CSV;

        SimConfig config = new SimConfig();

        Scheduler scheduler = new Scheduler(true);
        Thread schedulerThread = new Thread(scheduler, "Scheduler");
        schedulerThread.setDaemon(true);
        schedulerThread.start();

        System.out.println("[SchedulerMain] Scheduler running on port " + Ports.SCHEDULER);
        if (skipDroneLauncherFromEnv()) {
            System.out.println("[SchedulerMain] SKIP_DRONE_LAUNCHER is set — not spawning drones (use run_distributed.bat or start DroneMain yourself).");
        } else {
            System.out.println("[SchedulerMain] Spawning " + config.getNumDrones() + " drone processes from config...");
            DroneLauncher.spawnDrones(config);
        }
        System.out.println("[SchedulerMain] Run FireIncidentMain separately, or use the GUI \"Restart simulation\" button.");

        SwingUtilities.invokeLater(() -> {
            try {
                FlatLightLaf.setup();
                UIManager.put("Component.focusWidth", 0);
                UIManager.put("Button.arc", 8);
                UIManager.put("Component.arc", 8);
                UIManager.put("TextComponent.arc", 6);
            } catch (Exception e) {
                System.err.println("[SchedulerMain] FlatLaf not applied: " + e.getMessage());
            }
            SchedulerGUI gui = new SchedulerGUI(scheduler, csvPath, config);
            gui.setVisible(true);
        });
    }

    /**
     * When {@code SKIP_DRONE_LAUNCHER=1} (e.g. {@code run_distributed.bat} already started {@link app.DroneMain} processes),
     * do not spawn a second set of drones from this JVM.
     */
    private static boolean skipDroneLauncherFromEnv() {
        String v = System.getenv("SKIP_DRONE_LAUNCHER");
        return v != null && ("1".equals(v.trim()) || "true".equalsIgnoreCase(v.trim()));
    }
}
