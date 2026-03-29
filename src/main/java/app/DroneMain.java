package app;

import fireincident.DroneSubsystem;
import fireincident.IDroneSchedulerChannel;
import fireincident.UDPDroneChannel;

/**
 * Entry point for one drone as its own OS process (UDP to scheduler).
 * <p>
 * Usage: {@code java -cp &lt;classpath&gt; app.DroneMain <droneId> <schedulerHost> <schedulerPort> [timeScale] [agentCapacity]}
 *
 * @see fireincident.DroneSubsystem
 */
public class DroneMain {

    /**
     * @param args droneId, scheduler host, scheduler port, optional time scale and agent capacity (litres)
     */
    public static void main(String[] args) {
        if (args == null || args.length < 3) {
            printUsage();
            System.exit(1);
        }

        int droneId = 0;
        try {
            droneId = Integer.parseInt(args[0].trim());
            if (droneId < 1) throw new NumberFormatException("drone ID must be >= 1");
        } catch (NumberFormatException e) {
            System.err.println("Invalid drone ID: " + args[0]);
            printUsage();
            System.exit(1);
        }

        String host = args[1].trim();
        int port;
        try {
            port = Integer.parseInt(args[2].trim());
        } catch (NumberFormatException e) {
            System.err.println("Invalid port: " + args[2]);
            System.exit(1);
            return;
        }

        double timeScale = 1.0;
        if (args.length >= 4) {
            try {
                timeScale = Double.parseDouble(args[3].trim());
                if (timeScale <= 0) timeScale = 1.0;
            } catch (NumberFormatException ignored) {
            }
        }

        int agentCapacity = 100;
        if (args.length >= 5) {
            try {
                agentCapacity = Integer.parseInt(args[4].trim());
                if (agentCapacity < 10) agentCapacity = 10;
                if (agentCapacity > 100) agentCapacity = 100;
            } catch (NumberFormatException ignored) {
            }
        }

        IDroneSchedulerChannel channel = new UDPDroneChannel(host, port, droneId);
        System.out.println("[DroneMain] Drone " + droneId + " connecting to " + host + ":" + port + " (timeScale=" + timeScale + ", capacity=" + agentCapacity + "L)");

        DroneSubsystem drone = new DroneSubsystem(droneId, channel, timeScale, agentCapacity);
        Thread t = new Thread(drone, "Drone-" + droneId);
        t.setDaemon(false);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            t.interrupt();
        }
    }

    private static void printUsage() {
        System.err.println("Usage: java app.DroneMain <droneId> <schedulerHost> <schedulerPort> [timeScale] [agentCapacity]");
    }
}
