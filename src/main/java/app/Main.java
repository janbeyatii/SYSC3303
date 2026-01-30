package app;

import fireincident.FireIncidentSubsystem;
import fireincident.TestScheduler;
import fireincident.Scheduler;
import fireincident.DroneSubsystem;

import javax.swing.*;

/**
 * Main entry point for the program.
 * Sets everything up and starts reading incidents from the CSV file.
 */
public class Main {
    public static void main(String[] args) {
        Scheduler scheduler = new Scheduler();

        // ADD/KEEP: start at least 1 drone for Iteration 1
        Thread drone1 = new Thread(new DroneSubsystem(1, scheduler), "Drone-1");
        drone1.start();

        // ADD: launch GUI on Swing EDT
        SwingUtilities.invokeLater(() -> {
            SchedulerGUI gui = new SchedulerGUI(scheduler);
            gui.setVisible(true);
        });
    }
}
