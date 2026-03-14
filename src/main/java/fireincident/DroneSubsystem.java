package fireincident;
import model.Incident;
import udp.MessageType;
import udp.Ports;
import udp.UDPMessage;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;


public class DroneSubsystem implements Runnable {
    private static final double CRUISE_SPEED = 10.0;
    private static final int TAKEOFF_TIME = 8;
    private static final int LANDING_TIME = 10;
    private static final int ACCEL_TIME = 3;
    private static final int DECEL_TIME = 3;
    private static final double NOZZLE_OPEN_TIME = 0.5;
    private static final double NOZZLE_CLOSE_TIME = 0.5;
    private static final double RELEASE_RATE = 190.0 / 60.0;
    private static final double MAX_AGENT = 100;
    private static final double MAX_BATTERY = 900;
    private int agentRemaining = (int) MAX_AGENT;
    private double batteryRemaining = MAX_BATTERY;
    private final int droneId;
    private final double timeScale;
    private final Scheduler scheduler;
    DatagramPacket sendPacket, receivePacket;
    DatagramSocket sendSocket, receiveSocket;
    public DroneSubsystem(int droneId) {
        this(droneId, (Scheduler) null, 1.0);
    }
    public DroneSubsystem(int droneId, double timeScale) {
        this(droneId, (Scheduler) null, timeScale);
    }
    public DroneSubsystem(int droneId, Scheduler scheduler, double timeScale) {
        this.droneId = droneId;
        this.scheduler = scheduler;
        this.timeScale = timeScale <= 0 ? 1.0 : timeScale;
        if (scheduler != null) {
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
    @Override
    public void run() {
        if (scheduler != null) {
            runInProcess();
            return;
        }
        sendToScheduler(UDPMessage.droneIdle(droneId));
        updateDroneState(DroneState.IDLE.name(), null);
        while (!Thread.currentThread().isInterrupted()) {
            Incident incident = waitForDispatch();
            if (incident == null) break;
            System.out.println("[Drone " + droneId + "] Assigned incident: " + incident);
            try {
                double travelTime = calculateTravelTime(incident.getZoneId());
                System.out.println("[Drone " + droneId + "] Traveling to incident. Time: " + travelTime + " seconds");
                updateDroneState(DroneState.EN_ROUTE.name(), incident.getZoneId());
                useBattery(travelTime);
                sleepSeconds(travelTime);
                sendToScheduler(UDPMessage.droneArrived(droneId, incident));
                int litresNeeded = incident.getSeverity();
                int litresUsed = Math.min(litresNeeded, agentRemaining);
                agentRemaining -= litresUsed;
                double extinguishTime = calculateExtinguishTime(litresUsed);
                System.out.println("[Drone " + droneId + "] Extinguishing fire. Used: "
                        + litresUsed + "L. Time:" + extinguishTime + "seconds");
                updateDroneState(DroneState.EXTINGUISHING.name(), incident.getZoneId());
                useBattery(extinguishTime);
                sleepSeconds(extinguishTime);
                sendToScheduler(UDPMessage.droneDroppedAgent(droneId, incident));
                System.out.println("[Drone " + droneId + "] Incident completed: " + incident);
                double returnTime = calculateTravelTime(incident.getZoneId());
                System.out.println("[Drone " + droneId + "] Returning to base. Time: " + returnTime + " seconds");
                updateDroneState(DroneState.RETURNING.name(), null);
                sendToScheduler(UDPMessage.droneReturning(droneId));
                useBattery(returnTime);
                sleepSeconds(returnTime);
                agentRemaining = (int) MAX_AGENT;
                batteryRemaining = MAX_BATTERY;
                sendToScheduler(UDPMessage.droneIdle(droneId));
                updateDroneState(DroneState.IDLE.name(), null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    private void runInProcess() {
        updateInProcessDroneState(DroneState.IDLE.name(), null);
        while (!Thread.currentThread().isInterrupted()) {
            Incident incident = scheduler.requestWork(droneId);
            if (incident == null) {
                break;
            }
            try {
                executeIncident(incident, true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    private void executeIncident(Incident incident, boolean inProcess) throws InterruptedException {
        System.out.println("[Drone " + droneId + "] Assigned incident: " + incident);
        double travelTime = calculateTravelTime(incident.getZoneId());
        System.out.println("[Drone " + droneId + "] Traveling to incident. Time: " + travelTime + " seconds");
        if (inProcess) {
            updateInProcessDroneState(DroneState.EN_ROUTE.name(), incident.getZoneId());
        } else {
            updateDroneState(DroneState.EN_ROUTE.name(), incident.getZoneId());
        }
        useBattery(travelTime);
        sleepSeconds(travelTime);
        if (inProcess) {
            scheduler.reportArrival(droneId, incident);
        } else {
            sendToScheduler(UDPMessage.droneArrived(droneId, incident));
        }
        int litresNeeded = incident.getSeverity();
        int litresUsed = Math.min(litresNeeded, agentRemaining);
        agentRemaining -= litresUsed;
        double extinguishTime = calculateExtinguishTime(litresUsed);
        System.out.println("[Drone " + droneId + "] Extinguishing fire. Used: "
                + litresUsed + "L. Time:" + extinguishTime + "seconds");
        if (inProcess) {
            updateInProcessDroneState(DroneState.EXTINGUISHING.name(), incident.getZoneId());
        } else {
            updateDroneState(DroneState.EXTINGUISHING.name(), incident.getZoneId());
        }
        useBattery(extinguishTime);
        sleepSeconds(extinguishTime);
        if (inProcess) {
            scheduler.reportCompletion(droneId, incident);
        } else {
            sendToScheduler(UDPMessage.droneDroppedAgent(droneId, incident));
        }
        System.out.println("[Drone " + droneId + "] Incident completed: " + incident);
        double returnTime = calculateTravelTime(incident.getZoneId());
        System.out.println("[Drone " + droneId + "] Returning to base. Time: " + returnTime + " seconds");
        if (inProcess) {
            updateInProcessDroneState(DroneState.RETURNING.name(), null);
            scheduler.reportReturnToBase(droneId);
        } else {
            updateDroneState(DroneState.RETURNING.name(), null);
            sendToScheduler(UDPMessage.droneReturning(droneId));
        }
        useBattery(returnTime);
        sleepSeconds(returnTime);
        agentRemaining = (int) MAX_AGENT;
        batteryRemaining = MAX_BATTERY;
        if (inProcess) {
            updateInProcessDroneState(DroneState.IDLE.name(), null);
        } else {
            sendToScheduler(UDPMessage.droneIdle(droneId));
            updateDroneState(DroneState.IDLE.name(), null);
        }
    }
    private Incident waitForDispatch() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                byte[] data = new byte[UDPMessage.MAX_SIZE];
                receivePacket = new DatagramPacket(data, data.length);
                System.out.println("[Drone " + droneId + "] Waiting for dispatch...");
                receiveSocket.receive(receivePacket);
                System.out.println("[Drone " + droneId + "] Packet received:");
                System.out.println("From host: " + receivePacket.getAddress());
                System.out.println("Host port: " + receivePacket.getPort());
                int len = receivePacket.getLength();
                System.out.println("Length: " + len);
                System.out.print("Containing: ");
                String received = new String(data, 0, len);
                System.out.println(received + "\n");
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
            System.out.println("[Drone " + droneId + "] Sending packet:");
            System.out.println("To host: " + sendPacket.getAddress());
            System.out.println("Destination host port: " + sendPacket.getPort());
            System.out.println("Length: " + sendPacket.getLength());
            System.out.print("Containing: ");
            System.out.println(new String(sendPacket.getData(), 0, sendPacket.getLength()));
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
    private double calculateTravelTime(int zoneId) {
        double distance = zoneId * 100.0;
        double cruiseTime = distance / CRUISE_SPEED;
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
    public DroneSubsystem(int droneId, IDroneSchedulerChannel channel, double timeScale) {
        this(droneId, timeScale);
    }
}
