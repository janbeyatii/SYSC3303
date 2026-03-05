package app;

import fireincident.FireIncidentSubsystem;
import fireincident.Scheduler;
import fireincident.DroneSubsystem;
import fireincident.InProcessDroneChannel;

import javax.swing.*;

/**
 * Main entry point for the program.
 * Sets everything up and starts reading incidents from the CSV file.
 * Optional: pass CSV path as first argument (e.g. run.bat passes it).
 */
public class Main {
    private static final String DEFAULT_CSV = "data/Sample_event_file.csv";

    public static void main(String[] args) {
        String csvPath = (args != null && args.length > 0 && args[0] != null && !args[0].isEmpty())
                ? args[0].trim()
                : DEFAULT_CSV;

        Scheduler scheduler = new Scheduler();

        // Drone uses channel (in-process for single JVM; UDP when distributed)
        Thread drone1 = new Thread(new DroneSubsystem(1, new InProcessDroneChannel(scheduler)), "Drone-1");
        drone1.start();

        // ADD: launch GUI on Swing EDT; use csvPath from command line if provided
        final String defaultCsv = csvPath;
        SwingUtilities.invokeLater(() -> {
            SchedulerGUI gui = new SchedulerGUI(scheduler, defaultCsv);
            gui.setVisible(true);
        });
    }
}
