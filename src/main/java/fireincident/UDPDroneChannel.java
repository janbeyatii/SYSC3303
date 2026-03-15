package fireincident;

import fireincident.udp.DronePacketBuilder;
import fireincident.udp.DronePacketParser;
import model.Incident;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * UDP implementation of {@link IDroneSchedulerChannel} (Iteration 3 – Task 3 only).
 * Sends request packets to the scheduler process and parses responses.
 * The scheduler must run a UDP server (Task 1) that implements the same protocol.
 */
public class UDPDroneChannel implements IDroneSchedulerChannel {

    private static final int SOCKET_TIMEOUT_MS = 10_000;
    private static final int MAX_PACKET_SIZE = 1024;

    private final String schedulerHost;
    private final int schedulerPort;
    private final int droneId;
    private final Lock sendReceiveLock = new ReentrantLock();

    public UDPDroneChannel(String schedulerHost, int schedulerPort, int droneId) {
        this.schedulerHost = schedulerHost;
        this.schedulerPort = schedulerPort;
        this.droneId = droneId;
    }

    @Override
    public Incident requestWork(int droneId) {
        sendReceiveLock.lock();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            byte[] req = DronePacketBuilder.requestWork(droneId);
            send(socket, req);
            byte[] resp = receive(socket);
            DronePacketParser.Response r = DronePacketParser.parse(resp);
            if ("ASSIGN".equals(r.type)) {
                return r.incident;
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException("UDP requestWork failed: " + e.getMessage(), e);
        } finally {
            sendReceiveLock.unlock();
        }
    }

    @Override
    public Incident peekNextIncident() {
        sendReceiveLock.lock();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            byte[] req = DronePacketBuilder.peekNext();
            send(socket, req);
            byte[] resp = receive(socket);
            DronePacketParser.Response r = DronePacketParser.parse(resp);
            if ("PEEK_RESP".equals(r.type)) {
                return r.incident;
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException("UDP peekNextIncident failed: " + e.getMessage(), e);
        } finally {
            sendReceiveLock.unlock();
        }
    }

    @Override
    public void reportArrival(int droneId, Incident incident) {
        sendReport(DronePacketBuilder.reportArrival(droneId, incident));
    }

    @Override
    public void reportCompletion(int droneId, Incident incident) {
        sendReport(DronePacketBuilder.reportCompletion(droneId, incident));
    }

    @Override
    public void reportReturnToBase(int droneId) {
        sendReport(DronePacketBuilder.reportReturnToBase(droneId));
    }

    @Override
    public void updateDroneState(int droneId, String state, Integer zoneId) {
        sendReport(DronePacketBuilder.reportState(droneId, state, zoneId));
    }

    private void sendReport(byte[] req) {
        sendReceiveLock.lock();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            send(socket, req);
            receive(socket); // expect ACK
        } catch (Exception e) {
            throw new RuntimeException("UDP report failed: " + e.getMessage(), e);
        } finally {
            sendReceiveLock.unlock();
        }
    }

    private void send(DatagramSocket socket, byte[] data) throws Exception {
        InetAddress addr = InetAddress.getByName(schedulerHost);
        DatagramPacket packet = new DatagramPacket(data, data.length, addr, schedulerPort);
        socket.send(packet);
    }

    private byte[] receive(DatagramSocket socket) throws Exception {
        byte[] buf = new byte[MAX_PACKET_SIZE];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        socket.receive(packet);
        byte[] copy = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), packet.getOffset(), copy, 0, packet.getLength());
        return copy;
    }
}
