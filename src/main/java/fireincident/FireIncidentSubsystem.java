package fireincident;

import model.Incident;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * Main class for Fire Incident Subsystem.
 * Reads CSV files, creates Incident objects, and sends them to the scheduler.
 * handles callbacks when incidents are completed.
 */
public class FireIncidentSubsystem implements IncidentCallback {
    private SchedulerInterface scheduler;
    private String csvFilePath;

    public FireIncidentSubsystem(String csvFilePath, SchedulerInterface scheduler) {
        this.csvFilePath = csvFilePath;
        this.scheduler = scheduler;
    }

    /**
     * Reads the CSV file and processes each incident.
     * Skips the first line (header) and sends each incident to the scheduler.
     */
    public void processIncidents() {
        try (BufferedReader reader = new BufferedReader(new FileReader(csvFilePath))) {
            // First line should be header, skip it
            String header = reader.readLine();
            if (header == null) {
                System.out.println("[FireIncidentSubsystem] CSV file is empty or has no header.");
                return;
            }
            System.out.println("[FireIncidentSubsystem] Skipping header: " + header);

            String line;
            int lineNumber = 2; 

            while ((line = reader.readLine()) != null) {
                try {
                    Incident incident = parseIncident(line, lineNumber);
                    if (incident != null) {
                        System.out.println("[FireIncidentSubsystem] Parsed incident: " + incident);
                        scheduler.receiveIncident(incident, this);
                    }
                } catch (Exception e) {
                    System.err.println("[FireIncidentSubsystem] Error parsing line " + lineNumber + ": " + e.getMessage());
                    System.err.println("[FireIncidentSubsystem] Line content: " + line);
                }
                lineNumber++;
            }

            System.out.println("[FireIncidentSubsystem] Finished processing CSV file. Total lines processed: " + (lineNumber - 2));

        } catch (IOException e) {
            System.err.println("[FireIncidentSubsystem] Error reading CSV file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Takes a line from the CSV and turns it into an Incident object.
     * Format should be: time,zoneId,eventType,severity
     * Severity can be a number (1-5) or words like "High", "Moderate", "Low"
     */
    private Incident parseIncident(String line, int lineNumber) {
        if (line == null || line.trim().isEmpty()) {
            System.out.println("[FireIncidentSubsystem] Skipping empty line " + lineNumber);
            return null;
        }

        String[] parts = line.split(",");
        if (parts.length < 4) {
            throw new IllegalArgumentException("Expected 4 fields but found " + parts.length);
        }

        try {
            String time = parts[0].trim();
            int zoneId = Integer.parseInt(parts[1].trim());
            String eventType = parts[2].trim();
            int severity = parseSeverity(parts[3].trim());

            return new Incident(time, zoneId, eventType, severity);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number format in line " + lineNumber + ": " + e.getMessage());
        }
    }

    /**
     * Converts severity to litres of water/foam needed (per spec: Low=10 L, Moderate=20 L, High=30 L).
     * Accepts words "High", "Moderate", "Low" or numeric litres 10, 20, 30.
     */
    private int parseSeverity(String severityStr) {
        String normalized = severityStr.trim().toLowerCase();
        switch (normalized) {
            case "high":
                return 30;
            case "moderate":
                return 20;
            case "low":
                return 10;
            default:
                break;
        }
        try {
            int litres = Integer.parseInt(severityStr.trim());
            if (litres == 10 || litres == 20 || litres == 30) {
                return litres;
            }
            throw new IllegalArgumentException("Severity must be 10, 20, or 30 (litres), or High/Moderate/Low, got: " + severityStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Unknown severity value: " + severityStr +
                    ". Expected High (30 L), Moderate (20 L), Low (10 L), or 10/20/30");
        }
    }

    /**
     * This gets called when the scheduler finishes handling an incident.
     * Right now it just prints a message.
     */
    @Override
    public void onIncidentCompleted(Incident incident) {
        System.out.println("[FireIncidentSubsystem] Received completion notification for: " + incident);
    }
}
