package model;

/**
 * Snapshot of a drone's operational state for GUI / diagnostics (agent, battery, distance).
 */
public record DroneTelemetry(
        int droneId,
        String state,
        Integer zoneId,
        int agentRemainingLitres,
        int agentCapacityLitres,
        double batteryRemainingSeconds,
        double batteryMaxSeconds,
        Integer destinationZoneId,
        Double distanceToDestinationMeters
) {
}
