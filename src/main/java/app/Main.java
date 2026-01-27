package app;

import fireincident.FireIncidentSubsystem;
import fireincident.TestScheduler;
import fireincident.Scheduler;
import fireincident.DroneSubsystem;

/**
 * Main entry point for the program.
 * Sets everything up and starts reading incidents from the CSV file.
 */
public class Main {
    public static void main(String[] args) {
        String csvFilePath = "data/Sample_event_file.csv";
        
        if (args.length > 0) {
            csvFilePath = args[0];
        }

        System.out.println("=== Fire Incident Subsystem - Iteration 1: Fire Incident + Scheduler + Drone ===");
        System.out.println("Reading incidents from: " + csvFilePath);
        System.out.println();

        // Using a test scheduler for now (real scheduler not implemented yet)
        TestScheduler scheduler = new TestScheduler();

        FireIncidentSubsystem subsystem = new FireIncidentSubsystem(csvFilePath, scheduler);

        subsystem.processIncidents();

        System.out.println();
        System.out.println("=== Processing Complete ===");
    }
}
