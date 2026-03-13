package fireincident;

import model.Incident;
import udp.MessageType;
import udp.Ports;
import udp.UDPMessage;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class FIFOTestRunner {

    public static void main(String[] args) throws Exception {
        Scheduler scheduler = new Scheduler();
        Thread schedulerThread = new Thread(scheduler, "Scheduler");
        schedulerThread.setDaemon(true);
        schedulerThread.start();

        DroneSubsystem drone = new DroneSubsystem(1, 0.001);
        Thread droneThread = new Thread(drone, "Drone-1");
        droneThread.setDaemon(true);
        droneThread.start();

        Thread.sleep(200);

        Incident i1 = new Incident("00:00:01", 3, "FIRE", 10);
        Incident i2 = new Incident("00:00:02", 1, "FIRE", 20);
        Incident i3 = new Incident("00:00:03", 2, "FIRE", 30);

        DatagramSocket testSocket = new DatagramSocket();
        DatagramSocket listenSocket = new DatagramSocket(Ports.FIRE_IS);
        sendIncident(testSocket, i1);
        sendIncident(testSocket, i2);
        sendIncident(testSocket, i3);
        int completedCount = 0;
        String[] completedKeys = new String[3];
        System.out.println("[TEST] Waiting for completions...");

        listenSocket.setSoTimeout(10000);
        while (completedCount < 3) {
            byte[] data = new byte[UDPMessage.MAX_SIZE];
            DatagramPacket packet = new DatagramPacket(data, data.length);
            listenSocket.receive(packet);
            String received = new String(data, 0, packet.getLength()).trim();
            UDPMessage msg = UDPMessage.fromString(received);
            if (msg.getType() == MessageType.INCIDENT_COMPLETED) {
                completedKeys[completedCount] = msg.getField(0) + "|" + msg.getField(1) + "|" + msg.getField(2);                System.out.println("[TEST] Completed " + (completedCount + 1) + ": " + completedKeys[completedCount]);
                completedCount++;
            }
        }
        if (completedCount != 3) {
            throw new RuntimeException("[TEST] FAIL - only " + completedCount + " of 3 incidents completed");
        }
        if (!completedKeys[0].equals(i1.getKey())) throw new RuntimeException("[TEST] FAIL - not FIFO first, got: " + completedKeys[0]);
        if (!completedKeys[1].equals(i2.getKey())) throw new RuntimeException("[TEST] FAIL - not FIFO second, got: " + completedKeys[1]);
        if (!completedKeys[2].equals(i3.getKey())) throw new RuntimeException("[TEST] FAIL - not FIFO third, got: " + completedKeys[2]);

        System.out.println("[TEST] PASS - all 3 incidents completed in FIFO order");

        testSocket.close();
        listenSocket.close();
    }
    private static void sendIncident(DatagramSocket socket, Incident incident) throws Exception {
        byte[] data = UDPMessage.incidentReport(incident).toBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length,
                InetAddress.getLocalHost(), Ports.SCHEDULER);
        socket.send(packet);
        System.out.println("[TEST] Sent incident: " + incident.getKey());
        Thread.sleep(50);
    }
}