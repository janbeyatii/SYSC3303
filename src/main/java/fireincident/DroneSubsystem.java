package fireincident;

import model.DroneTelemetry;
import model.Incident;
import model.ZoneConfig;
import fireincident.udp.MessageType;
import fireincident.udp.Ports;
import fireincident.udp.UDPMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Simulates one drone: travel, suppression (agent use), return to base, and battery timing.
 * <p>
 * Modes: <b>in-process</b> with a {@link Scheduler} reference; <b>UDP push</b> with datagrams to
 * the scheduler; <b>UDP pull</b> via {@link IDroneSchedulerChannel} (standalone {@link app.DroneMain}).
 * Iteration 4 adds fault injection when {@link Incident} metadata matches this drone or event key.
 */
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
    private static final double FAULT_TIMEOUT_MARGIN_SECONDS = 15.0;
    private static final double FAULT_TIMEOUT_MULTIPLIER = 1.5;
    private static final double FAULT_TIMEOUT_OVERRUN_SECONDS = 5.0;
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
        void updateTelemetry(DroneTelemetry t);
    }

    private static class DroneFaultException extends Exception{
        DroneFaultException(String message){
            super(message);
        }
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
            @Override public void updateTelemetry(DroneTelemetry t) { sendToScheduler(UDPMessage.droneStateTelemetry(t)); }
        };
        sendToScheduler(UDPMessage.droneIdle(droneId));
        reporter.updateState(DroneState.IDLE.name(), null);
        while (!Thread.currentThread().isInterrupted()) {
            Incident incident = waitForDispatch();
            if (incident == null) break;
            try {
                int zone = executeIncidentFrom(incident, reporter, 0);
                returnToBaseAndRefill(reporter, zone, incident);
                sendToScheduler(UDPMessage.droneIdle(droneId));
                reporter.updateState(DroneState.IDLE.name(), null);
            } catch (DroneFaultException e) {
                System.err.println("[Drone " + droneId + "] " + e.getMessage());
                break;
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
            @Override public void updateTelemetry(DroneTelemetry t) { channel.updateDroneTelemetry(t); }
        };
        reporter.updateState(DroneState.IDLE.name(), null);
        int currentZone = 0;
        while (!Thread.currentThread().isInterrupted()) {
            Incident incident = channel.requestWork(droneId, currentZone, agentRemaining);
            if (incident == null) {
                Incident peek = channel.peekNextIncident();
                if (peek != null && peek.getSeverity() > agentRemaining) {
                    try {
                        returnToBaseAndRefill(reporter, currentZone, peek);
                        currentZone = 0;
                        reporter.updateState(DroneState.IDLE.name(), null);
                    } catch (DroneFaultException e) {
                        System.err.println("[Drone " + droneId + "] " + e.getMessage());
                        break;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            try {
                Incident lastIncident = incident;
                currentZone = executeIncidentFrom(incident, reporter, currentZone);
                while (agentRemaining > 0) {
                    Incident preview = channel.peekNextIncident();
                    if (preview == null || preview.getSeverity() > agentRemaining) break;
                    Incident next = channel.requestWork(droneId, currentZone, agentRemaining);
                    if (next == null) break;
                    lastIncident = next;
                    currentZone = executeIncidentFrom(next, reporter, currentZone);
                }
                returnToBaseAndRefill(reporter, currentZone, lastIncident);
                currentZone = 0;
                reporter.updateState(DroneState.IDLE.name(), null);
            } catch (DroneFaultException e) {
                System.err.println("[Drone " + droneId + "] " + e.getMessage());
                break;
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
            @Override public void updateTelemetry(DroneTelemetry t) { scheduler.applyDroneTelemetry(t); }
        };
        reporter.updateState(DroneState.IDLE.name(), null);
        int currentZone = 0;
        while (!Thread.currentThread().isInterrupted()) {
            Incident incident = scheduler.requestWork(droneId, currentZone, agentRemaining);
            if (incident == null) break;
            try {
                Incident lastIncident = incident;
                currentZone = executeIncidentFrom(incident, reporter, currentZone);
                while (agentRemaining > 0) {
                    Incident preview = scheduler.peekNextIncident();
                    if (preview == null || preview.getSeverity() > agentRemaining) break;
                    Incident next = scheduler.requestWork(droneId, currentZone, agentRemaining);
                    if (next == null) break;
                    lastIncident = next;
                    currentZone = executeIncidentFrom(next, reporter, currentZone);
                }
                returnToBaseAndRefill(reporter, currentZone, lastIncident);
                currentZone = 0;
                reporter.updateState(DroneState.IDLE.name(), null);
            } catch (DroneFaultException e) {
                System.err.println("[Drone " + droneId + "] " + e.getMessage());
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** Execute one incident from given zone. Returns the zone where drone ends (incident zone). */
    private int executeIncidentFrom(Incident incident, IncidentReporter reporter, int fromZone) throws InterruptedException, DroneFaultException{
        System.out.println("[Drone " + droneId + "] Assigned incident: " + incident);
        double travelTime = calculateTravelTime(fromZone, incident.getZoneId());
        System.out.println("[Drone " + droneId + "] Traveling to incident (from zone " + fromZone + "). Time: " + travelTime + " seconds");
        reporter.updateState(DroneState.EN_ROUTE.name(), incident.getZoneId());
        double actualTravelTime = hasTravelFault(incident) ? faultDurationSeconds(travelTime) : travelTime;
        if (runTravelPhaseWithTelemetry(reporter, incident, fromZone, travelTime, actualTravelTime)) {
            handleFault(reporter, incident.getZoneId(), "Drone " + droneId + " timed out while traveling to zone " + incident.getZoneId(), false);
        }
        if (hasArrivalSensorFault(incident) && runTimedPhase(1.0, faultDurationSeconds(1.0))) {
            handleFault(reporter, incident.getZoneId(), "Arrival sensor timeout detected for zone " + incident.getZoneId(), false);
        }
        reporter.reportArrival(incident);
        int litresNeeded = incident.getSeverity();
        int litresUsed = Math.min(litresNeeded, agentRemaining);
        agentRemaining -= litresUsed;
        double extinguishTime = calculateExtinguishTime(litresUsed);
        System.out.println("[Drone " + droneId + "] Extinguishing fire. Used: " + litresUsed + "L. Time: " + extinguishTime + " seconds");
        reporter.updateState(DroneState.EXTINGUISHING.name(), incident.getZoneId());
        double suppressionDuration = hasSuppressionHardFault(incident) ? faultDurationSeconds(extinguishTime) : extinguishTime;
        if (runTimedPhase(extinguishTime, suppressionDuration)) {
            handleFault(reporter, incident.getZoneId(), "Hard fault detected while suppressing at zone " + incident.getZoneId(), true);
        }
        reporter.reportCompletion(incident);
        System.out.println("[Drone " + droneId + "] Incident completed: " + incident);
        return incident.getZoneId();
    }

    private void returnToBaseAndRefill(IncidentReporter reporter, int fromZone, Incident incident) throws InterruptedException, DroneFaultException{
        double returnTime = calculateTravelTime(fromZone, 0);
        reporter.updateState(DroneState.RETURNING.name(), null);
        reporter.reportReturnToBase();
        double actualReturnTime = hasReturnFault(incident) ? faultDurationSeconds(returnTime) : returnTime;
        if (runReturnPhaseWithTelemetry(reporter, fromZone, returnTime, actualReturnTime)) {
            handleFault(reporter, null, "Drone " + droneId + " timed out while returning to base", false);
        }
        agentRemaining = maxAgent;
        batteryRemaining = MAX_BATTERY;
        reporter.updateTelemetry(snap(DroneState.IDLE.name(), 0, null, null));
    }
    private Incident waitForDispatch() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                byte[] data = new byte[UDPMessage.MAX_SIZE];
                receivePacket = new DatagramPacket(data, data.length);
                receiveSocket.receive(receivePacket);
                int len = receivePacket.getLength();
                String received = new String(data, 0, len, StandardCharsets.UTF_8);
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

    private DroneTelemetry snap(String state, Integer zoneId, Integer destZone, Double distM) {
        return new DroneTelemetry(droneId, state, zoneId, agentRemaining, maxAgent,
                batteryRemaining, MAX_BATTERY, destZone, distM);
    }

    private boolean runTravelPhaseWithTelemetry(IncidentReporter reporter, Incident incident, int fromZone,
            double expectedTravelSeconds, double actualTravelSeconds) throws InterruptedException {
        double totalDist = zoneConfig.getDistanceMeters(fromZone, incident.getZoneId());
        int dest = incident.getZoneId();
        if (actualTravelSeconds <= 1e-9) {
            reporter.updateTelemetry(snap(DroneState.EN_ROUTE.name(), dest, dest, 0.0));
            sleepSeconds(0);
            return false;
        }
        double elapsed = 0;
        double step = Math.max(0.25, Math.min(5.0, actualTravelSeconds / 40.0));
        while (true) {
            double distRem = totalDist * (1.0 - elapsed / actualTravelSeconds);
            if (distRem < 0) distRem = 0;
            reporter.updateTelemetry(snap(DroneState.EN_ROUTE.name(), dest, dest, distRem));
            if (elapsed >= actualTravelSeconds - 1e-9) break;
            double next = Math.min(step, actualTravelSeconds - elapsed);
            useBattery(next);
            sleepSeconds(next);
            elapsed += next;
        }
        return actualTravelSeconds > faultDeadlineSeconds(expectedTravelSeconds);
    }

    private boolean runReturnPhaseWithTelemetry(IncidentReporter reporter, int fromZone,
            double expectedReturnSeconds, double actualReturnSeconds) throws InterruptedException {
        double totalDist = zoneConfig.getDistanceMeters(fromZone, 0);
        if (actualReturnSeconds <= 1e-9) {
            reporter.updateTelemetry(snap(DroneState.RETURNING.name(), fromZone, 0, 0.0));
            sleepSeconds(0);
            return false;
        }
        double elapsed = 0;
        double step = Math.max(0.25, Math.min(5.0, actualReturnSeconds / 40.0));
        while (true) {
            double distRem = totalDist * (1.0 - elapsed / actualReturnSeconds);
            if (distRem < 0) distRem = 0;
            reporter.updateTelemetry(snap(DroneState.RETURNING.name(), fromZone, 0, distRem));
            if (elapsed >= actualReturnSeconds - 1e-9) break;
            double next = Math.min(step, actualReturnSeconds - elapsed);
            useBattery(next);
            sleepSeconds(next);
            elapsed += next;
        }
        return actualReturnSeconds > faultDeadlineSeconds(expectedReturnSeconds);
    }

    private boolean hasTravelFault(Incident incident){
        return isEventFault(incident) && faultContains(incident, "STUCK");
    }
    private boolean hasArrivalSensorFault(Incident incident){
        return matchesFaultTarget(incident) && (faultContains(incident, "ARRIVAL") || faultContains(incident, "SENSOR"));
    }
    private boolean hasSuppressionHardFault(Incident incident){
        return matchesFaultTarget(incident) && (faultContains(incident, "NOZZLE") || faultContains(incident, "BAY") || faultContains(incident, "DOOR"));
    }
    private boolean hasReturnFault(Incident incident){
        return isDroneFault(incident) && faultContains(incident, "STUCK");
    }
    private boolean isEventFault(Incident incident){
        return matchesFaultTarget(incident) && "EVENT".equalsIgnoreCase(incident.getFaultTargetType());
    }
    private boolean isDroneFault(Incident incident){
        return matchesFaultTarget(incident) && "DRONE".equalsIgnoreCase(incident.getFaultTargetType());
    }
    private boolean matchesFaultTarget(Incident incident){
        if (incident == null || Incident.NO_FAULT.equalsIgnoreCase(incident.getFaultType())) {
            return false;
        }
        String targetType = incident.getFaultTargetType();
        String targetId = incident.getFaultTargetId();
        if ("EVENT".equalsIgnoreCase(targetType)) {
            return incident.getKey().equalsIgnoreCase(targetId);
        }
        if ("DRONE".equalsIgnoreCase(targetType)) {
            return ("D" + droneId).equalsIgnoreCase(targetId) || String.valueOf(droneId).equalsIgnoreCase(targetId);
        }
        return false;
    }
    private boolean faultContains(Incident incident, String token){
        return incident != null && incident.getFaultType() != null
                && incident.getFaultType().toUpperCase().contains(token);
    }
    private boolean runTimedPhase(double expectedSeconds, double actualSeconds) throws InterruptedException{
        useBattery(actualSeconds);
        sleepSeconds(actualSeconds);
        return actualSeconds > faultDeadlineSeconds(expectedSeconds);
    }
    private double faultDurationSeconds(double expectedSeconds){
        return faultDeadlineSeconds(expectedSeconds) + FAULT_TIMEOUT_OVERRUN_SECONDS;
    }
    private double faultDeadlineSeconds(double expectedSeconds){
        return expectedSeconds + Math.max(FAULT_TIMEOUT_MARGIN_SECONDS, expectedSeconds * FAULT_TIMEOUT_MULTIPLIER);
    }
    public void handleFault(IncidentReporter reporter, Integer zoneId, String message, boolean hardFault) throws DroneFaultException {
        reporter.updateState(DroneState.FAULTED.name(), zoneId);
        reporter.updateState(hardFault ? DroneState.OFFLINE.name() : DroneState.UNAVAILABLE.name(), zoneId);
        if (scheduler != null) {
            scheduler.onDroneFaultDetected(droneId, message, hardFault);
        }
        throw new DroneFaultException(message);
    }
    private void useBattery(double seconds) {
        batteryRemaining = Math.max(0, batteryRemaining - seconds);
    }
    private void sleepSeconds(double seconds) throws InterruptedException {
        long ms = Math.max(1, (long) (seconds * 1000 * timeScale));
        Thread.sleep(ms);
    }
}
