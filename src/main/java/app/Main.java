package app;

import fireincident.Scheduler;
import model.ZoneConfig;

import javax.swing.*;

/**
 * Convenience launcher: starts the {@link Scheduler} UDP thread, spawns {@code N} {@link DroneMain}
 * processes from {@link SimConfig}, and opens {@link SchedulerGUI}.
 * <p>
 * For strict multi-process demos (scheduler + drones + fire as separate programs), prefer
 * {@code run_distributed.bat} or run {@link SchedulerMain}, {@link DroneMain}, and {@link FireIncidentMain} manually.
 *
 * @param args optional incident file path; default {@value #DEFAULT_CSV}
 */
public class Main {
    private static final String DEFAULT_CSV = "data/iteration4/iter4_fault_mixed.csv";

    public static void main(String[] args) {
        String csvPath = (args != null && args.length > 0 && args[0] != null && !args[0].isEmpty())
                ? args[0].trim()
                : DEFAULT_CSV;

        SimConfig config = new SimConfig();

        Scheduler scheduler = new Scheduler(true, new ZoneConfig(), config.getDroneTimeScale());
        Thread schedulerThread = new Thread(scheduler, "Scheduler");
        schedulerThread.setDaemon(true);
        schedulerThread.start();

        System.out.println("[Main] Spawning " + config.getNumDrones() + " drone processes from config...");
        DroneLauncher.spawnDrones(config);

        final String defaultCsv = csvPath;
        SwingUtilities.invokeLater(() -> {
            SchedulerGUI gui = new SchedulerGUI(scheduler, defaultCsv);
            gui.setVisible(true);
        });
    }
}
