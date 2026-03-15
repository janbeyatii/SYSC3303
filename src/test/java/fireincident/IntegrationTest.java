package fireincident;

import model.Incident;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import fireincident.udp.MessageType;
import fireincident.udp.Ports;
import fireincident.udp.UDPMessage;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IntegrationTest {

    private Scheduler scheduler;
    private Thread schedulerThread;

    @Before
    public void setUp() {
        try {
            scheduler = new Scheduler(true);
        } catch (IllegalStateException e) {
            Assume.assumeNoException(e);
        }
        schedulerThread = new Thread(scheduler, "Scheduler");
        schedulerThread.start();
    }

    @After
    public void tearDown() throws Exception {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (schedulerThread != null) {
            schedulerThread.join(1000);
        }
    }

    @Test
    public void testUdpCommunicationWithSimulatedDrone() throws Exception {
        Incident incident = new Incident("14:03:15", 4, "FIRE_DETECTED", 30);

        try (DatagramSocket droneSocket = new DatagramSocket(Ports.DRONE_SS);
             DatagramSocket sender = new DatagramSocket()) {
            droneSocket.setSoTimeout(5000);

            send(sender, UDPMessage.droneState(1, DroneState.IDLE.name(), 1));
            send(sender, UDPMessage.droneIdle(1));
            send(sender, UDPMessage.incidentReport(incident));

            DatagramPacket dispatchPacket = new DatagramPacket(new byte[UDPMessage.MAX_SIZE], UDPMessage.MAX_SIZE);
            droneSocket.receive(dispatchPacket);
            UDPMessage dispatch = UDPMessage.fromBytes(dispatchPacket.getData(), dispatchPacket.getLength());

            assertEquals(MessageType.DISPATCH_DRONE, dispatch.getType());
            assertEquals("1", dispatch.getField(0));
            assertEquals(String.valueOf(incident.getZoneId()), dispatch.getField(2));

            send(sender, UDPMessage.droneArrived(1, incident));
            send(sender, UDPMessage.droneDroppedAgent(1, incident));
            send(sender, UDPMessage.droneReturning(1));
            send(sender, UDPMessage.droneIdle(1));

            assertTrue(waitForCompletion(incident, 3000));
            assertEquals(Scheduler.FireState.COMPLETED, scheduler.getFireState(incident));
            assertEquals(0, scheduler.getQueueSize());
            assertEquals(0, scheduler.getInProgressCount());
            assertEquals(DroneState.IDLE.name(), scheduler.getDroneState(1));
        }
    }

    private void send(DatagramSocket socket, UDPMessage message) throws Exception {
        byte[] data = message.toBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, java.net.InetAddress.getLocalHost(), Ports.SCHEDULER);
        socket.send(packet);
    }

    private boolean waitForCompletion(Incident incident, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (scheduler.getFireState(incident) == Scheduler.FireState.COMPLETED) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }
}
