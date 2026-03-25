package app;

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
        System.out.println("[SchedulerMain] Spawning " + config.getNumDrones() + " drone processes from config...");
        DroneLauncher.spawnDrones(config);
        System.out.println("[SchedulerMain] Start FireIncidentMain in another process, or use GUI Start button.");

        SwingUtilities.invokeLater(() -> {
            SchedulerGUI gui = new SchedulerGUI(scheduler, csvPath);
            gui.setVisible(true);
        });
    }
}
