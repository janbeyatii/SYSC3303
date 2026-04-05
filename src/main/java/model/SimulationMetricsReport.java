package model;

import java.util.Collections;
import java.util.List;

/**
 * Performance snapshot for Iteration 5: wall-clock timings, approximate simulated times
 * (using configured drone wall-time scale), dispatch distance, and CSV event-time span.
 */
public final class SimulationMetricsReport {
    private final long missionWallMs;
    private final int incidentsCompleted;
    private final double avgResponseWallMs;
    private final double totalDispatchDistanceMeters;
    private final double avgIdleWallMsPerDrone;
    private final double avgFlightWallMsPerDrone;
    /** missionWallMs/1000/scale — approximates simulated mission duration if drone sleeps use this scale. */
    private final double approxMissionSimSeconds;
    private final double approxAvgResponseSimSeconds;
    private final double approxAvgIdleSimSeconds;
    private final double approxAvgFlightSimSeconds;
    /** Seconds between latest and earliest parsed CSV Time column in the run; -1 if unavailable. */
    private final int scenarioCsvTimeSpanSeconds;
    private final double droneWallTimeScaleUsed;

    public SimulationMetricsReport(
            long missionWallMs,
            int incidentsCompleted,
            double avgResponseWallMs,
            double totalDispatchDistanceMeters,
            double avgIdleWallMsPerDrone,
            double avgFlightWallMsPerDrone,
            double droneWallTimeScale,
            int scenarioCsvTimeSpanSeconds) {
        this.missionWallMs = missionWallMs;
        this.incidentsCompleted = incidentsCompleted;
        this.avgResponseWallMs = avgResponseWallMs;
        this.totalDispatchDistanceMeters = totalDispatchDistanceMeters;
        this.avgIdleWallMsPerDrone = avgIdleWallMsPerDrone;
        this.avgFlightWallMsPerDrone = avgFlightWallMsPerDrone;
        double ts = droneWallTimeScale > 0 ? droneWallTimeScale : 1.0;
        this.droneWallTimeScaleUsed = ts;
        this.scenarioCsvTimeSpanSeconds = scenarioCsvTimeSpanSeconds;
        this.approxMissionSimSeconds = (missionWallMs / 1000.0) / ts;
        this.approxAvgResponseSimSeconds = (avgResponseWallMs / 1000.0) / ts;
        this.approxAvgIdleSimSeconds = (avgIdleWallMsPerDrone / 1000.0) / ts;
        this.approxAvgFlightSimSeconds = (avgFlightWallMsPerDrone / 1000.0) / ts;
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

    public double approxMissionSimSeconds() {
        return approxMissionSimSeconds;
    }

    public double approxAvgResponseSimSeconds() {
        return approxAvgResponseSimSeconds;
    }

    public double approxAvgIdleSimSeconds() {
        return approxAvgIdleSimSeconds;
    }

    public double approxAvgFlightSimSeconds() {
        return approxAvgFlightSimSeconds;
    }

    public int scenarioCsvTimeSpanSeconds() {
        return scenarioCsvTimeSpanSeconds;
    }

    public double droneWallTimeScaleUsed() {
        return droneWallTimeScaleUsed;
    }

    public String toLogString() {
        return String.format(
                "missionWallMs=%d approxMissionSimS=%.2f completed=%d avgResponseMs=%.1f avgResponseSimS=%.2f totalDispatchM=%.1f avgIdleMsPerDrone=%.1f avgFlightMsPerDrone=%.1f csvTimeSpanS=%d scale=%.4f",
                missionWallMs, approxMissionSimSeconds, incidentsCompleted, avgResponseWallMs, approxAvgResponseSimSeconds,
                totalDispatchDistanceMeters, avgIdleWallMsPerDrone, avgFlightWallMsPerDrone,
                scenarioCsvTimeSpanSeconds, droneWallTimeScaleUsed);
    }

    public String toDetailedString(List<Integer> droneIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Simulation performance ===\n");
        sb.append("(Wall times are measured on the machine; approx simulated s = wall s / droneTimeScale from config.)\n");
        sb.append(String.format("Drone time scale used: %.6f (from data/config.properties)\n", droneWallTimeScaleUsed));
        sb.append(String.format("Mission wall time (first queued → last completed): %d ms (%.2f s)\n",
                missionWallMs, missionWallMs / 1000.0));
        sb.append(String.format("Approx. mission simulated time (wall / scale): %.2f s\n", approxMissionSimSeconds));
        if (scenarioCsvTimeSpanSeconds >= 0) {
            sb.append(String.format("CSV Time column span (max−min parsed times in this run): %d s\n",
                    scenarioCsvTimeSpanSeconds));
        } else {
            sb.append("CSV Time column span: n/a (could not parse times)\n");
        }
        sb.append(String.format("Incidents completed: %d\n", incidentsCompleted));
        if (incidentsCompleted > 0) {
            sb.append(String.format("Avg response wall (scheduler received incident → completed): %.1f ms (%.2f s)\n",
                    avgResponseWallMs, avgResponseWallMs / 1000.0));
            sb.append(String.format("Avg response approx. sim: %.2f s\n", approxAvgResponseSimSeconds));
            sb.append("(Detect instant for that metric = when the scheduler enqueues the incident, i.e. UDP/FIS delivery.)\n");
        }
        sb.append(String.format("Total dispatch distance (assigned legs, zone center→incident): %.1f m\n",
                totalDispatchDistanceMeters));
        sb.append(String.format("Avg drone idle (IDLE state) wall: %.1f ms | approx. sim: %.2f s\n",
                avgIdleWallMsPerDrone, approxAvgIdleSimSeconds));
        sb.append(String.format("Avg drone flight (EN_ROUTE+RETURNING) wall: %.1f ms | approx. sim: %.2f s\n",
                avgFlightWallMsPerDrone, approxAvgFlightSimSeconds));
        if (droneIds != null && !droneIds.isEmpty()) {
            sb.append("Drones in sample: ").append(droneIds).append("\n");
        }
        sb.append("Operational goal: minimize mission time, response time, and dispatch distance.\n");
        return sb.toString();
    }

    public static SimulationMetricsReport empty() {
        return new SimulationMetricsReport(0, 0, 0, 0, 0, 0, 1.0, -1);
    }

    public static List<Integer> sortedIds(java.util.Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        java.util.ArrayList<Integer> list = new java.util.ArrayList<>(ids);
        Collections.sort(list);
        return list;
    }
}
