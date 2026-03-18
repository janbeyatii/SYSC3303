package app;

import fireincident.DroneSubsystem;
import fireincident.IDroneSchedulerChannel;
import fireincident.UDPDroneChannel;

/**
 * Entry point for running a drone as its own process (Iteration 3 – Task 3 only).
 * Usage: java app.DroneMain &lt;droneId&gt; &lt;schedulerHost&gt; &lt;schedulerPort&gt; [timeScale] [agentCapacity]
 * Example: java -cp bin app.DroneMain 1 127.0.0.1 5000
 * Example: java -cp bin app.DroneMain 1 127.0.0.1 5000 0.001 100
 */
public class DroneMain {

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
