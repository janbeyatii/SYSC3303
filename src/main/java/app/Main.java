package app;

import fireincident.Scheduler;
import fireincident.DroneSubsystem;

import javax.swing.*;

public class Main {
    private static final String DEFAULT_CSV = "data/Sample_event_file.csv";

    public static void main(String[] args) {
        String csvPath = (args != null && args.length > 0 && args[0] != null && !args[0].isEmpty())
                ? args[0].trim()
                : DEFAULT_CSV;
        Scheduler scheduler = new Scheduler(true);
        Thread schedulerThread = new Thread(scheduler, "Scheduler");
        schedulerThread.setDaemon(true);
        schedulerThread.start();
        Thread drone1 = new Thread(new DroneSubsystem(1), "Drone-1");
        drone1.start();
        final String defaultCsv = csvPath;
        SwingUtilities.invokeLater(() -> {
            SchedulerGUI gui = new SchedulerGUI(scheduler, defaultCsv);
            gui.setVisible(true);
        });
    }
}
