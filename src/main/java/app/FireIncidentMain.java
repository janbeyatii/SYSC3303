package app;

import fireincident.FireIncidentSubsystem;
import fireincident.udp.Ports;

/**
 * Entry point for running the Fire Incident Subsystem as its own process (Iteration 3).
 * Reads a CSV file and sends incidents to the Scheduler over UDP.
 * <p>
 * Usage: java app.FireIncidentMain &lt;path-to-csv&gt; [schedulerHost] [schedulerPort]
 * <p>
 * Examples:
 * <pre>
 *   java -cp bin app.FireIncidentMain data/Sample_event_file.csv
 *   java -cp bin app.FireIncidentMain data/Sample_event_file.csv 127.0.0.1 5000
 *   java -cp bin app.FireIncidentMain C:/path/to/events.csv 192.168.1.10 5000
 * </pre>
 */
public class FireIncidentMain {

    public static void main(String[] args) {
        if (args == null || args.length < 1 || args[0] == null || args[0].trim().isEmpty()) {
            printUsage();
            System.exit(1);
        }

        String csvPath = args[0].trim();
        String schedulerHost = args.length >= 2 && args[1] != null && !args[1].trim().isEmpty()
                ? args[1].trim()
                : "127.0.0.1";
        int schedulerPort = Ports.SCHEDULER;
        if (args.length >= 3 && args[2] != null && !args[2].trim().isEmpty()) {
            try {
                schedulerPort = Integer.parseInt(args[2].trim());
                if (schedulerPort <= 0 || schedulerPort > 65535) {
                    System.err.println("Invalid scheduler port (use 1-65535): " + args[2]);
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                System.err.println("Invalid scheduler port: " + args[2]);
                printUsage();
                System.exit(1);
            }
        }

        System.out.println("[FireIncidentMain] CSV: " + csvPath);
        System.out.println("[FireIncidentMain] Scheduler: " + schedulerHost + ":" + schedulerPort);

        FireIncidentSubsystem fis = new FireIncidentSubsystem(csvPath, schedulerHost, schedulerPort);
        fis.run();

        System.out.println("[FireIncidentMain] Done.");
    }

    private static void printUsage() {
        System.err.println("Usage: java app.FireIncidentMain <path-to-csv> [schedulerHost] [schedulerPort]");
        System.err.println("  path-to-csv    Required. Path to the incident CSV file.");
        System.err.println("  schedulerHost  Optional. Default: 127.0.0.1");
        System.err.println("  schedulerPort  Optional. Default: " + Ports.SCHEDULER);
    }
}
