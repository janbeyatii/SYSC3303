package fireincident;
import model.Incident;
import model.ZoneConfig;
import fireincident.udp.MessageType;
import fireincident.udp.Ports;
import fireincident.udp.UDPMessage;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;


public class DroneSubsystem implements Runnable {
    /** Set to true for verbose packet-level debug logging. */
    private static final boolean DEBUG_PACKETS = false;

    private static final double CRUISE_SPEED = 10.0;
    private static final int TAKEOFF_TIME = 8;
    private static final int LANDING_TIME = 10;
    private static final int ACCEL_TIME = 3;
    private static final int DECEL_TIME = 3;
    private static final double NOZZLE_OPEN_TIME = 0.5;
    private static final double NOZZLE_CLOSE_TIME = 0.5;
    private static final double RELEASE_RATE = 190.0 / 60.0;
    private static final double MAX_BATTERY = 900;
    private final int maxAgent;
    private int agentRemaining;
    private double batteryRemaining = MAX_BATTERY;
    private final int droneId;
    private final double timeScale;
    private final Scheduler scheduler;
    private final IDroneSchedulerChannel channel;
    private final ZoneConfig zoneConfig;
    DatagramPacket sendPacket, receivePacket;
    DatagramSocket sendSocket, receiveSocket;

    public DroneSubsystem(int droneId) {
        this(droneId, null, null, null, 1.0, 100);
    }
    public DroneSubsystem(int droneId, double timeScale) {
        this(droneId, null, null, null, timeScale, 100);
    }
    public DroneSubsystem(int droneId, Scheduler scheduler, double timeScale) {
        this(droneId, scheduler, null, null, timeScale, 100);
    }
    /** Use this for independent process (e.g. DroneMain): drone talks to scheduler via channel (UDP pull). */
    public DroneSubsystem(int droneId, IDroneSchedulerChannel channel, double timeScale) {
        this(droneId, null, channel, null, timeScale, 100);
    }
    /** With explicit zone config (for custom zone file path). */
    public DroneSubsystem(int droneId, IDroneSchedulerChannel channel, ZoneConfig zoneConfig, double timeScale) {
        this(droneId, null, channel, zoneConfig, timeScale, 100);
    }
    /** With explicit agent capacity (configurable per spec). */
    public DroneSubsystem(int droneId, IDroneSchedulerChannel channel, double timeScale, int agentCapacity) {
        this(droneId, null, channel, null, timeScale, agentCapacity > 0 ? agentCapacity : 100);
    }

    private DroneSubsystem(int droneId, Scheduler scheduler, IDroneSchedulerChannel channel, ZoneConfig zoneConfig, double timeScale, int agentCapacity) {
        this.droneId = droneId;
        this.scheduler = scheduler;
        this.channel = channel;
        this.zoneConfig = zoneConfig != null ? zoneConfig : new ZoneConfig();
        this.timeScale = timeScale <= 0 ? 1.0 : timeScale;
        this.maxAgent = agentCapacity > 0 ? agentCapacity : 100;
        this.agentRemaining = this.maxAgent;
        if (scheduler != null || channel != null) {
            return;
        }
        try {
            sendSocket = new DatagramSocket();
            receiveSocket = new DatagramSocket(Ports.DRONE_SS);
            System.out.println("[Drone " + droneId + "] Listening on port " + Ports.DRONE_SS);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize drone sockets", e);
        }
    }

    /** Abstraction for reporting incident progress (UDP push, channel, or in-process). */
    private interface IncidentReporter {
        void reportArrival(Incident incident);
        void reportCompletion(Incident incident);
        void reportReturnToBase();
        void updateState(String state, Integer zoneId);
    }

    @Override
    public void run() {
        if (channel != null) {
            runWithChannel();
            return;
        }
        if (scheduler != null) {
            runInProcess();
            return;
        }
        IncidentReporter reporter = new IncidentReporter() {
            @Override public void reportArrival(Incident incident) { sendToScheduler(UDPMessage.droneArrived(droneId, incident)); }
            @Override public void reportCompletion(Incident incident) { sendToScheduler(UDPMessage.droneDroppedAgent(droneId, incident)); }
            @Override public void reportReturnToBase() { sendToScheduler(UDPMessage.droneReturning(droneId)); }
            @Override public void updateState(String state, Integer zoneId) { DroneSubsystem.this.updateDroneState(state, zoneId); }
        };
        sendToScheduler(UDPMessage.droneIdle(droneId));
        reporter.updateState(DroneState.IDLE.name(), null);
        while (!Thread.currentThread().isInterrupted()) {
            Incident incident = waitForDispatch();
            if (incident == null) break;
            try {
                int zone = executeIncidentFrom(incident, reporter, 0);
                returnToBaseAndRefill(reporter, zone);
                sendToScheduler(UDPMessage.droneIdle(droneId));
                reporter.updateState(DroneState.IDLE.name(), null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void runWithChannel() {
        IncidentReporter reporter = new IncidentReporter() {
            @Override public void reportArrival(Incident incident) { channel.reportArrival(droneId, incident); }
            @Override public void reportCompletion(Incident incident) { channel.reportCompletion(droneId, incident); }
            @Override public void reportReturnToBase() { channel.reportReturnToBase(droneId); }
            @Override public void updateState(String state, Integer zoneId) { channel.updateDroneState(droneId, state, zoneId); }
        };
        reporter.updateState(DroneState.IDLE.name(), null);
        int currentZone = 0;
        while (!Thread.currentThread().isInterrupted()) {
            Incident incident = channel.requestWork(droneId, currentZone, agentRemaining);
            if (incident == null) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            try {
                currentZone = executeIncidentFrom(incident, reporter, currentZone);
                while (agentRemaining > 0) {
                    Incident next = channel.requestWork(droneId, currentZone, agentRemaining);
                    if (next == null) break;
                    currentZone = executeIncidentFrom(next, reporter, currentZone);
                }
                returnToBaseAndRefill(reporter, currentZone);
                currentZone = 0;
                reporter.updateState(DroneState.IDLE.name(), null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void runInProcess() {
        IncidentReporter reporter = new IncidentReporter() {
            @Override public void reportArrival(Incident incident) { scheduler.reportArrival(droneId, incident); }
            @Override public void reportCompletion(Incident incident) { scheduler.reportCompletion(droneId, incident); }
            @Override public void reportReturnToBase() { scheduler.reportReturnToBase(droneId); }
            @Override public void updateState(String state, Integer zoneId) { updateInProcessDroneState(state, zoneId); }
        };
        reporter.updateState(DroneState.IDLE.name(), null);
        int currentZone = 0;
        while (!Thread.currentThread().isInterrupted()) {
            Incident incident = scheduler.requestWork(droneId, currentZone, agentRemaining);
            if (incident == null) break;
            try {
                currentZone = executeIncidentFrom(incident, reporter, currentZone);
                while (agentRemaining > 0) {
                    Incident next = scheduler.requestWork(droneId, currentZone, agentRemaining);
                    if (next == null) break;
                    currentZone = executeIncidentFrom(next, reporter, currentZone);
                }
                returnToBaseAndRefill(reporter, currentZone);
                currentZone = 0;
                reporter.updateState(DroneState.IDLE.name(), null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** Execute one incident from given zone. Returns the zone where drone ends (incident zone). */
    private int executeIncidentFrom(Incident incident, IncidentReporter reporter, int fromZone) throws InterruptedException {
        System.out.println("[Drone " + droneId + "] Assigned incident: " + incident);
        double travelTime = calculateTravelTime(fromZone, incident.getZoneId());
        System.out.println("[Drone " + droneId + "] Traveling to incident (from zone " + fromZone + "). Time: " + travelTime + " seconds");
        reporter.updateState(DroneState.EN_ROUTE.name(), incident.getZoneId());
        useBattery(travelTime);
        sleepSeconds(travelTime);
        reporter.reportArrival(incident);
        int litresNeeded = incident.getSeverity();
        int litresUsed = Math.min(litresNeeded, agentRemaining);
        agentRemaining -= litresUsed;
        double extinguishTime = calculateExtinguishTime(litresUsed);
        System.out.println("[Drone " + droneId + "] Extinguishing fire. Used: " + litresUsed + "L. Time: " + extinguishTime + " seconds");
        reporter.updateState(DroneState.EXTINGUISHING.name(), incident.getZoneId());
        useBattery(extinguishTime);
        sleepSeconds(extinguishTime);
        reporter.reportCompletion(incident);
        System.out.println("[Drone " + droneId + "] Incident completed: " + incident);
        return incident.getZoneId();
    }

    private void returnToBaseAndRefill(IncidentReporter reporter, int fromZone) throws InterruptedException {
        double returnTime = calculateTravelTime(fromZone, 0);
        reporter.updateState(DroneState.RETURNING.name(), null);
        reporter.reportReturnToBase();
        useBattery(returnTime);
        sleepSeconds(returnTime);
        agentRemaining = maxAgent;
        batteryRemaining = MAX_BATTERY;
    }
    private Incident waitForDispatch() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                byte[] data = new byte[UDPMessage.MAX_SIZE];
                receivePacket = new DatagramPacket(data, data.length);
                receiveSocket.receive(receivePacket);
                int len = receivePacket.getLength();
                String received = new String(data, 0, len);
                if (DEBUG_PACKETS) {
                    System.out.println("[Drone " + droneId + "] Received from " + receivePacket.getAddress() + ":" + receivePacket.getPort() + ": " + received.trim());
                }
                UDPMessage msg = UDPMessage.fromString(received.trim());
                if (msg.getType() == MessageType.DISPATCH_DRONE) {
                    int target = Integer.parseInt(msg.getField(0));
                    if (target == droneId) {
                        return msg.toIncident();
                    }
                } else if (msg.getType() == MessageType.SHUTDOWN) {
                    System.out.println("[Drone " + droneId + "] Shutdown received.");
                    return null;
                }
            } catch (IOException e) {
                System.err.println("[Drone " + droneId + "] Receive error: " + e.getMessage());
            }
        }
        return null;
    }
    private void sendToScheduler(UDPMessage message) {
        try {
            byte[] msg = message.toBytes();
            sendPacket = new DatagramPacket(msg, msg.length, InetAddress.getLocalHost(), Ports.SCHEDULER);
            if (DEBUG_PACKETS) {
                System.out.println("[Drone " + droneId + "] Sending to " + Ports.SCHEDULER + ": " + new String(msg));
            }
            sendSocket.send(sendPacket);
        } catch (IOException e) {
            System.err.println("[Drone " + droneId + "] Send error: " + e.getMessage());
        }
    }
    private void updateDroneState(String state, Integer zoneId) {
        sendToScheduler(UDPMessage.droneState(droneId, state, zoneId));
    }
    private void updateInProcessDroneState(String state, Integer zoneId) {
        scheduler.updateDroneState(droneId, state, zoneId);
    }
    /** Travel time in seconds from one zone to another. Uses zone coordinates for distance. */
    private double calculateTravelTime(int fromZone, int toZone) {
        double distanceMeters = zoneConfig.getDistanceMeters(fromZone, toZone);
        double cruiseTime = distanceMeters / CRUISE_SPEED;
        return TAKEOFF_TIME + ACCEL_TIME + cruiseTime + DECEL_TIME + LANDING_TIME;
    }
    private double calculateExtinguishTime(int litresUsed) {
        return NOZZLE_OPEN_TIME + (litresUsed / RELEASE_RATE) + NOZZLE_CLOSE_TIME;
    }
    private void useBattery(double seconds) {
        batteryRemaining = Math.max(0, batteryRemaining - seconds);
    }
    private void sleepSeconds(double seconds) throws InterruptedException {
        long ms = Math.max(1, (long) (seconds * 1000 * timeScale));
        Thread.sleep(ms);
    }
}
