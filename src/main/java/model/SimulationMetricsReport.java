package model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Final demo performance metrics computed from wall-clock milliseconds. */
public final class SimulationMetricsReport {
    private final long missionWallMs;
    private final int eventsCompleted;
    private final double avgResponseWallMs;
    private final long maxResponseWallMs;
    private final double avgCompletionWallMs;
    private final long maxCompletionWallMs;
    private final Map<Integer, Double> droneUtilizationPctByDroneId;
    private final double avgQueueLength;
    private final int maxQueueLength;

    public SimulationMetricsReport(
            long missionWallMs,
            int eventsCompleted,
            double avgResponseWallMs,
            long maxResponseWallMs,
            double avgCompletionWallMs,
            long maxCompletionWallMs,
            Map<Integer, Double> droneUtilizationPctByDroneId,
            double avgQueueLength,
            int maxQueueLength) {
        this.missionWallMs = missionWallMs;
        this.eventsCompleted = eventsCompleted;
        this.avgResponseWallMs = avgResponseWallMs;
        this.maxResponseWallMs = maxResponseWallMs;
        this.avgCompletionWallMs = avgCompletionWallMs;
        this.maxCompletionWallMs = maxCompletionWallMs;
        this.droneUtilizationPctByDroneId = Collections.unmodifiableMap(
                new LinkedHashMap<>(droneUtilizationPctByDroneId == null ? Map.of() : droneUtilizationPctByDroneId));
        this.avgQueueLength = avgQueueLength;
        this.maxQueueLength = maxQueueLength;
    }

    public long missionWallMs() {
        return missionWallMs;
    }

    public int incidentsCompleted() {
        return eventsCompleted;
    }

    public int eventsCompleted() {
        return eventsCompleted;
    }

    public double avgResponseWallMs() {
        return avgResponseWallMs;
    }

    public long maxResponseWallMs() {
        return maxResponseWallMs;
    }

    public double avgCompletionWallMs() {
        return avgCompletionWallMs;
    }

    public long maxCompletionWallMs() {
        return maxCompletionWallMs;
    }

    public Map<Integer, Double> droneUtilizationPctByDroneId() {
        return droneUtilizationPctByDroneId;
    }

    public double avgQueueLength() {
        return avgQueueLength;
    }

    public int maxQueueLength() {
        return maxQueueLength;
    }

    public String toLogString() {
        return String.format(
                "missionWallMs=%d eventsCompleted=%d avgResponseMs=%.1f maxResponseMs=%d avgCompletionMs=%.1f maxCompletionMs=%d avgQueueLen=%.2f maxQueueLen=%d",
                missionWallMs, eventsCompleted, avgResponseWallMs, maxResponseWallMs, avgCompletionWallMs,
                maxCompletionWallMs, avgQueueLength, maxQueueLength);
    }

    public String toDetailedString(List<Integer> droneIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Performance metrics (wall-clock) ===\n");
        sb.append("All measurements use wall-clock milliseconds and are computed at simulation end.\n");
        sb.append(String.format("Run duration (first event queued -> last event completed): %d ms (%.2f s)\n",
                missionWallMs, missionWallMs / 1000.0));
        sb.append(String.format("Events completed: %d\n", eventsCompleted));
        if (eventsCompleted > 0) {
            sb.append(String.format("1) Average event response time (created -> first arrival): %.1f ms (%.2f s)\n",
                    avgResponseWallMs, avgResponseWallMs / 1000.0));
            sb.append(String.format("2) Maximum event response time: %d ms (%.2f s)\n",
                    maxResponseWallMs, maxResponseWallMs / 1000.0));
            sb.append(String.format("3) Average event completion time (created -> completed): %.1f ms (%.2f s)\n",
                    avgCompletionWallMs, avgCompletionWallMs / 1000.0));
            sb.append(String.format("4) Maximum event completion time: %d ms (%.2f s)\n",
                    maxCompletionWallMs, maxCompletionWallMs / 1000.0));
        }
        sb.append("5) Drone utilization per drone (active = travelling + servicing):\n");
        List<Integer> idsToShow = droneIds;
        if (idsToShow == null || idsToShow.isEmpty()) {
            idsToShow = sortedIds(droneUtilizationPctByDroneId.keySet());
        }
        for (Integer id : idsToShow) {
            double pct = droneUtilizationPctByDroneId.getOrDefault(id, 0.0);
            sb.append(String.format("   Drone %d: %.1f%%\n", id, pct));
        }
        sb.append(String.format("6) Average queue length (optional): %.2f\n", avgQueueLength));
        sb.append(String.format("7) Maximum queue length (optional): %d\n", maxQueueLength));
        return sb.toString();
    }

    public static SimulationMetricsReport empty() {
        return new SimulationMetricsReport(0, 0, 0, 0, 0, 0, Map.of(), 0, 0);
    }

    public static List<Integer> sortedIds(java.util.Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        java.util.ArrayList<Integer> list = new java.util.ArrayList<>(ids);
        Collections.sort(list);
        return list;
    }
}
