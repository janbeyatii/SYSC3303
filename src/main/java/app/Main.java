package app;

import fireincident.Scheduler;

import javax.swing.*;

/**
 * All-in-one launcher: Scheduler + GUI + spawns N drone processes from config.
 * Per project spec: drones run as separate processes (not threads), count from config.
 */
public class Main {
    private static final String DEFAULT_CSV = "data/Sample_event_file.csv";

    public static void main(String[] args) {
        String csvPath = (args != null && args.length > 0 && args[0] != null && !args[0].isEmpty())
                ? args[0].trim()
                : DEFAULT_CSV;

        SimConfig config = new SimConfig();

        Scheduler scheduler = new Scheduler(true);
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
