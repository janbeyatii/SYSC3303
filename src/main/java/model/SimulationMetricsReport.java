package model;

import java.util.Collections;
import java.util.List;

/**
 * Wall-clock performance snapshot for a simulation run (Iteration 5): mission span, response times,
 * drone idle/flight totals, and summed dispatch distances.
 */
public final class SimulationMetricsReport {
    private final long missionWallMs;
    private final int incidentsCompleted;
    private final double avgResponseWallMs;
    private final double totalDispatchDistanceMeters;
    private final double avgIdleWallMsPerDrone;
    private final double avgFlightWallMsPerDrone;

    public SimulationMetricsReport(
            long missionWallMs,
            int incidentsCompleted,
            double avgResponseWallMs,
            double totalDispatchDistanceMeters,
            double avgIdleWallMsPerDrone,
            double avgFlightWallMsPerDrone) {
        this.missionWallMs = missionWallMs;
        this.incidentsCompleted = incidentsCompleted;
        this.avgResponseWallMs = avgResponseWallMs;
        this.totalDispatchDistanceMeters = totalDispatchDistanceMeters;
        this.avgIdleWallMsPerDrone = avgIdleWallMsPerDrone;
        this.avgFlightWallMsPerDrone = avgFlightWallMsPerDrone;
    }

    public long missionWallMs() {
        return missionWallMs;
    }

    public int incidentsCompleted() {
        return incidentsCompleted;
    }

    public double avgResponseWallMs() {
        return avgResponseWallMs;
    }

    public double totalDispatchDistanceMeters() {
        return totalDispatchDistanceMeters;
    }

    public double avgIdleWallMsPerDrone() {
        return avgIdleWallMsPerDrone;
    }

    public double avgFlightWallMsPerDrone() {
        return avgFlightWallMsPerDrone;
    }

    public String toLogString() {
        return String.format(
                "missionWallMs=%d completed=%d avgResponseMs=%.1f totalDispatchM=%.1f avgIdleMsPerDrone=%.1f avgFlightMsPerDrone=%.1f",
                missionWallMs, incidentsCompleted, avgResponseWallMs, totalDispatchDistanceMeters,
                avgIdleWallMsPerDrone, avgFlightWallMsPerDrone);
    }

    public String toDetailedString(List<Integer> droneIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Simulation performance (wall clock) ===\n");
        sb.append(String.format("Time from first incident queued to last completed: %d ms (%.2f s)\n",
                missionWallMs, missionWallMs / 1000.0));
        sb.append(String.format("Incidents completed: %d\n", incidentsCompleted));
        if (incidentsCompleted > 0) {
            sb.append(String.format("Avg detect→extinguished (queued→completed): %.1f ms (%.2f s)\n",
                    avgResponseWallMs, avgResponseWallMs / 1000.0));
        }
        sb.append(String.format("Total dispatch distance (all assigned legs, zone center→incident): %.1f m\n",
                totalDispatchDistanceMeters));
        sb.append(String.format("Avg drone idle time (IDLE state): %.1f ms\n", avgIdleWallMsPerDrone));
        sb.append(String.format("Avg drone flight time (EN_ROUTE + RETURNING): %.1f ms\n", avgFlightWallMsPerDrone));
        if (droneIds != null && !droneIds.isEmpty()) {
            sb.append("Drones in sample: ").append(droneIds).append("\n");
        }
        sb.append("Note: minimize mission time, response time, and dispatch distance; balance idle vs flight operationally.\n");
        return sb.toString();
    }

    public static SimulationMetricsReport empty() {
        return new SimulationMetricsReport(0, 0, 0, 0, 0, 0);
    }

    public static List<Integer> sortedIds(java.util.Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        java.util.ArrayList<Integer> list = new java.util.ArrayList<>(ids);
        Collections.sort(list);
        return list;
    }
}
