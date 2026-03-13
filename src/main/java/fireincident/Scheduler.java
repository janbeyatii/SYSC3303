package fireincident;

import model.Incident;
import udp.MessageType;
import udp.Ports;
import udp.UDPMessage;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Scheduler (server): receives incidents from the Fire Incident Subsystem,
 * queues them, and dispatches work to drones via {@link #requestWork(int)}.
 * When a drone reports completion, the scheduler notifies the original
 */
public class Scheduler implements Runnable {
    DatagramPacket sendPacket, receivePacket;
    DatagramSocket sendSocket, receiveSocket;

    private static class Job {
        final Incident incident;
        Job(Incident incident) {
            this.incident = incident;
        }
    }

    public enum FireState { PENDING, ASSIGNED, COMPLETED }
    public enum SchedulerState { IDLE, HAS_PENDING, DRONE_BUSY, WAITING_FOR_INCIDENT }

    private SchedulerState schedulerState = SchedulerState.IDLE;
    private final Map<String, FireState> fireStates = new HashMap<>();

    //waiting incidents of FIFO queue
    private final LinkedList<Job> queue = new LinkedList<>();
    // track assigned incidents
    private final Map<Integer, Job> inProgressByZone = new HashMap<>();
    private final List<SchedulerListener> listeners = new ArrayList<>();
    private final Map<Integer, String> droneStateById = new HashMap<>();
    private final Map<Integer, Integer> droneZoneById = new HashMap<>();
    private final LinkedList<Integer> idleDrones = new LinkedList<>();

    private volatile boolean running = true;
    public Scheduler() {
        try {
            sendSocket = new DatagramSocket();
            receiveSocket = new DatagramSocket(Ports.SCHEDULER);
            System.out.println("[Scheduler] Bound to port " + Ports.SCHEDULER);
            System.out.println("[Scheduler] Waiting for packets...\n");
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
    @Override
    public void run() {
        while (running) {
            try {
                byte[] data = new byte[UDPMessage.MAX_SIZE];
                receivePacket = new DatagramPacket(data, data.length);
                receiveSocket.receive(receivePacket);
                System.out.println("[Scheduler] Packet received:");
                System.out.println("From host: " + receivePacket.getAddress());
                System.out.println("Host port: " + receivePacket.getPort());
                int len = receivePacket.getLength();
                System.out.println("Length: " + len);
                System.out.print("Containing: ");
                String received = new String(data, 0, len);
                System.out.println(received + "\n");
                UDPMessage msg = UDPMessage.fromString(received.trim());
                handleMessage(msg);
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
        }
        System.out.println("[Scheduler] Stopped.");
    }
    private synchronized void handleMessage(UDPMessage msg) throws IOException {
        switch (msg.getType()) {
            case INCIDENT_REPORT:handleIncidentReport(msg);break;
            case DRONE_ARRIVED:handleDroneArrived(msg);break;
            case DRONE_DROPPED_AGENT:handleDroneDroppedAgent(msg);break;
            case DRONE_RETURNING:handleDroneReturning(msg);break;
            case DRONE_IDLE:handleDroneIdle(msg);break;
            case DRONE_STATE:handleDroneState(msg);break;
            case SHUTDOWN:
                running = false;
                break;
            default:
                System.out.println("[Scheduler] Unknown packet: " + msg.getType());
        }
    }

    private void handleIncidentReport(UDPMessage msg) throws IOException {
        String time = msg.getField(0);
        int zoneId = Integer.parseInt(msg.getField(1));
        String eventType = msg.getField(2);
        int severity = Integer.parseInt(msg.getField(3));
        Incident incident = new Incident(time, zoneId, eventType, severity);
        queue.addLast(new Job(incident));
        fireStates.put(incident.getKey(), FireState.PENDING);
        schedulerState = SchedulerState.HAS_PENDING;
        fireLog("[Scheduler] Queued incident: " + incident);
        fireIncidentQueued(incident);
        dispatchPending();
    }

    private void handleDroneArrived(UDPMessage msg) {
        int droneId = Integer.parseInt(msg.getField(0));
        int zoneId = Integer.parseInt(msg.getField(2));
        String key = msg.getField(1) + "|" + msg.getField(2) + "|" + msg.getField(3);
        fireLog("[Scheduler] Drone " + droneId + " arrived at zone for " + key);
        updateDroneState(droneId, "ARRIVED", zoneId);
    }

    private void handleDroneDroppedAgent(UDPMessage msg) throws IOException {
        int droneId = Integer.parseInt(msg.getField(0));
        int zoneId = Integer.parseInt(msg.getField(2));
        String key = msg.getField(1) + "|" + msg.getField(2) + "|" + msg.getField(3);
        Job job = inProgressByZone.remove(zoneId);
        fireLog("[Scheduler] Drone " + droneId + " finished at: " + key);
        if (job != null) {
            fireStates.put(job.incident.getKey(), FireState.COMPLETED);
            fireIncidentCompleted(droneId, job.incident);
            sendToPort(UDPMessage.incidentCompleted(job.incident), Ports.FIRE_IS);
        }
        schedulerState = queue.isEmpty() ? SchedulerState.IDLE : SchedulerState.HAS_PENDING;
        dispatchPending();
    }

    private void handleDroneReturning(UDPMessage msg) {
        int droneId = Integer.parseInt(msg.getField(0));
        fireLog("[Scheduler] Drone " + droneId + " returning to base.");
        updateDroneState(droneId, DroneState.RETURNING.name(), null);
    }

    private void handleDroneIdle(UDPMessage msg) throws IOException {
        int droneId = Integer.parseInt(msg.getField(0));
        idleDrones.add(droneId);
        fireLog("[Scheduler] Drone " + droneId + " is idle.");
        updateDroneState(droneId, DroneState.IDLE.name(), null);
        dispatchPending();
    }

    private void handleDroneState(UDPMessage msg) {
        int droneId = Integer.parseInt(msg.getField(0));
        String state = msg.getField(1);
        String zoneStr = msg.getField(2);
        Integer zoneId = zoneStr.isEmpty() ? null : Integer.parseInt(zoneStr);
        updateDroneState(droneId, state, zoneId);
    }

    private void dispatchPending() throws IOException {
        while (!queue.isEmpty() && !idleDrones.isEmpty()) {
            Job job = queue.removeFirst();
            int droneId = idleDrones.removeFirst();
            inProgressByZone.put(job.incident.getZoneId(), job);
            fireStates.put(job.incident.getKey(), FireState.ASSIGNED);
            schedulerState = SchedulerState.DRONE_BUSY;
            sendToPort(UDPMessage.dispatchDrone(droneId, job.incident), Ports.DRONE_SS);
            fireLog("[Scheduler] Dispatched Drone " + droneId + " to " + job.incident);
            fireIncidentDispatched(droneId, job.incident);
        }
    }

    private void sendToPort(UDPMessage message, int port) throws IOException {
        byte[] data = message.toBytes();
        sendPacket = new DatagramPacket(data, data.length, InetAddress.getLocalHost(), port);
        System.out.println("[Scheduler] Sending packet:");
        System.out.println("To host: " + sendPacket.getAddress());
        System.out.println("Destination host port: " + sendPacket.getPort());
        System.out.println("Length: " + sendPacket.getLength());
        System.out.print("Containing: ");
        System.out.println(new String(sendPacket.getData(), 0, sendPacket.getLength()) + "\n");
        sendSocket.send(sendPacket);
    }

    private static Integer zoneFromKey(String key) {
        String[] parts = key.split("\\|", -1);
        if (parts.length >= 2) {
            try { return Integer.parseInt(parts[1]); }
            catch (NumberFormatException ignored) {}
        }
        return null;
    }

    public synchronized void addListener(SchedulerListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public synchronized void updateDroneState(int droneId, String state, Integer zoneId) {
        if (state != null) droneStateById.put(droneId, state);
        if (zoneId != null) droneZoneById.put(droneId, zoneId);
        fireDroneStateChanged(droneId, state, zoneId);
    }

    public synchronized int getQueueSize() {
        return queue.size();
    }

    public synchronized int getInProgressCount() {
        return inProgressByZone.isEmpty() ? 0 : 1;
    }

    public synchronized FireState getFireState(Incident incident) {
        return fireStates.get(incident.getKey());
    }

    public synchronized SchedulerState getSchedulerState() {
        return schedulerState;
    }

    private void fireIncidentQueued(Incident incident) {
        for (SchedulerListener l : listeners) l.onIncidentQueued(incident);
    }

    private void fireIncidentDispatched(int droneId, Incident incident) {
        for (SchedulerListener l : listeners) l.onIncidentDispatched(droneId, incident);
    }

    private void fireIncidentCompleted(int droneId, Incident incident) {
        for (SchedulerListener l : listeners) l.onIncidentCompleted(droneId, incident);
    }

    private void fireDroneStateChanged(int droneId, String state, Integer zoneId) {
        for (SchedulerListener l : listeners) l.onDroneStateChanged(droneId, state, zoneId);
    }

    private void fireLog(String message) {
        System.out.println(message);
        for (SchedulerListener l : listeners) l.onLog(message);
    }
    public synchronized Incident peekNextIncident() {
        return queue.isEmpty() ? null : queue.getFirst().incident;
    }

    public synchronized Incident requestWork(int droneId) {
        updateDroneState(droneId, DroneState.IDLE.name(), null);
        while (queue.isEmpty()) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        Job job = queue.removeFirst();
        inProgressByZone.put(job.incident.getZoneId(), job);
        fireStates.put(job.incident.getKey(), FireState.ASSIGNED);
        schedulerState = SchedulerState.DRONE_BUSY;
        fireLog("[Scheduler] Dispatched incident to Drone " + droneId + ": " + job.incident);
        fireIncidentDispatched(droneId, job.incident);
        return job.incident;
    }

    public synchronized void reportCompletion(int droneId, Incident incident) {
        inProgressByZone.remove(incident.getZoneId());
        fireStates.put(incident.getKey(), FireState.COMPLETED);
        fireLog("[Scheduler] Completion from Drone " + droneId + ": " + incident);
        fireIncidentCompleted(droneId, incident);
        schedulerState = queue.isEmpty() ? SchedulerState.IDLE : SchedulerState.HAS_PENDING;
        notifyAll();
    }

    public synchronized void reportArrival(int droneId, Incident incident) {
        fireLog("[Scheduler] Drone " + droneId + " arrived at zone " + incident.getZoneId());
        updateDroneState(droneId, "ARRIVED", incident.getZoneId());
    }

    public synchronized void reportReturnToBase(int droneId) {
        fireLog("[Scheduler] Drone " + droneId + " returned to base");
        updateDroneState(droneId, DroneState.IDLE.name(), null);
    }
}
