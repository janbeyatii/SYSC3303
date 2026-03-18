package app;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Spawns drone processes at startup from config.
 * Per project spec: configurable input to start system with, no manual add during simulation.
 */
public class DroneLauncher {

    /**
     * Spawns numDrones processes running DroneMain.
     * @return list of started processes (may be smaller if some fail)
     */
    public static List<Process> spawnDrones(SimConfig config) {
        return spawnDrones(config.getNumDrones(), config.getSchedulerHost(),
                config.getSchedulerPort(), config.getDroneTimeScale(), config.getAgentCapacity());
    }

    public static List<Process> spawnDrones(int numDrones, String host, int port, double timeScale, int agentCapacity) {
        List<Process> processes = new ArrayList<>();
        String cp = System.getProperty("java.class.path", "bin");
        File dir = new File(System.getProperty("user.dir", "."));

        for (int id = 1; id <= numDrones; id++) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "java", "-cp", cp,
                        "app.DroneMain",
                        String.valueOf(id), host, String.valueOf(port),
                        String.valueOf(timeScale), String.valueOf(agentCapacity)
                );
                pb.directory(dir);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                processes.add(p);
                System.out.println("[DroneLauncher] Started drone process " + id + " (connects to " + host + ":" + port + ")");
            } catch (IOException e) {
                System.err.println("[DroneLauncher] Failed to start drone " + id + ": " + e.getMessage());
            }
        }
        return processes;
    }
}
