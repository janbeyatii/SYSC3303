package app;

import fireincident.Scheduler;
import fireincident.SchedulerListener;
import model.Incident;
import model.ZoneConfig;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;


public class SchedulerGUI extends JFrame implements SchedulerListener {

    private final Scheduler scheduler;

    private JLabel systemStatus;
    private JLabel countsLabel;
    private JButton loadFileBtn;
    private JButton startBtn;

    private JTextField fileField;

    private JTable incidentTable;
    private DefaultTableModel incidentModel;

    private JTable droneTable;
    private DefaultTableModel droneModel;

    private ZoneMapPanel zoneMapPanel;
    private JTextArea logArea;

    private final Map<String, Integer> incidentRowByKey = new HashMap<>();
    private final Map<Integer, Integer> droneRowById = new HashMap<>();
    /** Active or recent animations for map: drone moves from zone A to B. */
    private final Map<Integer, ZoneMapPanel.DroneAnimation> droneAnimations = new HashMap<>();

    private final String defaultCsvPath;
    private final ZoneConfig zoneConfig;

    /** Same formula as DroneSubsystem: 24 + distance/10 seconds. UI scale: 1 real sec = 40 ms. */
    private long travelDurationMs(int fromZone, int toZone) {
        double distanceMeters = zoneConfig.getDistanceMeters(fromZone, toZone);
        double seconds = 24 + distanceMeters / 10.0;
        return (long) (seconds * 40);
    }

    public SchedulerGUI(Scheduler scheduler) {
        this(scheduler, "data/iteration4/iter4_fault_mixed.csv");
    }

    public SchedulerGUI(Scheduler scheduler, String defaultCsvPath) {
        this.scheduler = scheduler;
        this.zoneConfig = new ZoneConfig();
        this.defaultCsvPath = defaultCsvPath != null && !defaultCsvPath.isEmpty()
                ? defaultCsvPath.trim()
                : "data/iteration4/iter4_fault_mixed.csv";

        this.scheduler.addListener(this);
        setTitle("Firefighting Drone Swarm - Scheduler");
        setSize(950, 650);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        buildTopPanel();
        buildCenterPanels();
        buildLogPanel();

        setSystemStatus("IDLE");
        refreshCounts();
    }

    private void buildTopPanel() {
        JPanel top = new JPanel(new BorderLayout(8, 8));

        // Status row: full width so "System Status" and "Active fires" are never truncated
        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        systemStatus = new JLabel("System Status: IDLE");
        countsLabel = new JLabel("Queue: 0 | In-Progress: 0 | Active fires: 0");
        statusRow.add(systemStatus);
        statusRow.add(countsLabel);
        top.add(statusRow, BorderLayout.NORTH);

        // File row: input and buttons
        JPanel fileRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        fileField = new JTextField(defaultCsvPath, 28);
        fileField.setEditable(false);

        loadFileBtn = new JButton("Load CSV...");
        startBtn = new JButton("Start");

        loadFileBtn.addActionListener(e -> chooseFile());
        startBtn.addActionListener(e -> startSimulation());

        fileRow.add(new JLabel("Input:"));
        fileRow.add(fileField);
        fileRow.add(loadFileBtn);
        fileRow.add(startBtn);
        top.add(fileRow, BorderLayout.CENTER);

        add(top, BorderLayout.NORTH);
    }

    private void buildCenterPanels(){
        zoneMapPanel = new ZoneMapPanel(zoneConfig);

        JSplitPane tablesSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        tablesSplit.setResizeWeight(0.65);
        incidentModel = new DefaultTableModel(
                new Object[]{"Time", "Zone", "Type", "Severity", "Status", "Drone"}, 0
        ) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        incidentTable = new JTable(incidentModel);
        JScrollPane incidentScroll = new JScrollPane(incidentTable);

        JPanel incidentPanel = new JPanel(new BorderLayout());
        incidentPanel.setBorder(BorderFactory.createTitledBorder("Incidents"));
        incidentPanel.add(incidentScroll, BorderLayout.CENTER);

        droneModel = new DefaultTableModel(
                new Object[]{"Drone", "State", "Zone", "Last Update"}, 0
        ) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        droneTable = new JTable(droneModel);
        JScrollPane droneScroll = new JScrollPane(droneTable);

        JPanel dronePanel = new JPanel(new BorderLayout());
        dronePanel.setBorder(BorderFactory.createTitledBorder("Drones"));
        dronePanel.add(droneScroll, BorderLayout.CENTER);

        tablesSplit.setTopComponent(incidentPanel);
        tablesSplit.setBottomComponent(dronePanel);

        JSplitPane centerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        centerSplit.setLeftComponent(zoneMapPanel);
        centerSplit.setRightComponent(tablesSplit);
        centerSplit.setResizeWeight(0.38);

        add(centerSplit, BorderLayout.CENTER);
    }

    private void refreshZoneMap() {
        Map<Integer, Integer> droneZones = new HashMap<>();
        Map<Integer, String> droneStates = new HashMap<>();
        for (int r = 0; r < droneModel.getRowCount(); r++) {
            Object idObj = droneModel.getValueAt(r, 0);
            Object stateObj = droneModel.getValueAt(r, 1);
            Object zoneObj = droneModel.getValueAt(r, 2);
            if (idObj == null) continue;
            int droneId = idObj instanceof Number ? ((Number) idObj).intValue() : Integer.parseInt(String.valueOf(idObj));
            if (stateObj != null) droneStates.put(droneId, stateObj.toString());
            Integer zone = null;
            if (zoneObj != null && !"-".equals(zoneObj.toString().trim())) {
                try {
                    zone = Integer.parseInt(zoneObj.toString().trim());
                } catch (NumberFormatException ignored) {}
            }
            droneZones.put(droneId, zone);
        }
        Map<Integer, String> zoneStatus = new HashMap<>();
        for (int r = 0; r < incidentModel.getRowCount(); r++) {
            Object zoneObj = incidentModel.getValueAt(r, 1);
            Object statusObj = incidentModel.getValueAt(r, 4);
            if (zoneObj == null || statusObj == null) continue;
            int zone;
            try {
                zone = Integer.parseInt(zoneObj.toString().trim());
            } catch (NumberFormatException ignored) { continue; }
            String status = statusObj.toString();
            String existing = zoneStatus.get(zone);
            if (existing == null || status.equals("DISPATCHED") || (status.equals("QUEUED") && !"DISPATCHED".equals(existing)))
                zoneStatus.put(zone, status);
        }
        zoneMapPanel.setDroneZones(droneZones);
        zoneMapPanel.setDroneStates(droneStates);
        zoneMapPanel.setDroneAnimations(droneAnimations);
        zoneMapPanel.setZoneIncidentStatus(zoneStatus);
        zoneMapPanel.repaint();
    }

    private void buildLogPanel(){
        logArea = new JTextArea(7, 20);
        logArea.setEditable(false);
        JScrollPane logScroll = new JScrollPane(logArea);

        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("Event Log"));
        logPanel.add(logScroll, BorderLayout.CENTER);

        add(logPanel, BorderLayout.SOUTH);
    }

    private void chooseFile(){
        JFileChooser chooser = new JFileChooser(new File("."));
        chooser.setDialogTitle("Select incident CSV");
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            fileField.setText(chooser.getSelectedFile().getPath());
            log("[GUI] Selected file: " + chooser.getSelectedFile().getPath());
        }
    }

    private void startSimulation(){
        setSystemStatus("RUNNING");
        startBtn.setEnabled(false);
        loadFileBtn.setEnabled(false);

        String filePath = fileField.getText().trim();
        log("[GUI] Spawning Fire Incident Subsystem as separate process: " + filePath);

        SimConfig config = new SimConfig();
        String cp = System.getProperty("java.class.path", "bin");
        File dir = new File(System.getProperty("user.dir", "."));
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "java", "-cp", cp,
                    "app.FireIncidentMain",
                    filePath,
                    config.getSchedulerHost(),
                    String.valueOf(config.getSchedulerPort())
            );
            pb.directory(dir);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            log("[GUI] Fire Incident Subsystem started (separate process, PID " + p.pid() + ")");
            Thread watcher = new Thread(() -> {
                try {
                    p.waitFor();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                SwingUtilities.invokeLater(() -> {
                    setSystemStatus("DONE");
                    startBtn.setEnabled(true);
                    loadFileBtn.setEnabled(true);
                    refreshCounts();
                    log("[GUI] Fire Incident Subsystem process finished.");
                });
            }, "FireIncidentProcess-Watcher");
            watcher.setDaemon(true);
            watcher.start();
        } catch (IOException e) {
            log("[GUI] Failed to start Fire Incident Subsystem: " + e.getMessage());
            setSystemStatus("ERROR");
            startBtn.setEnabled(true);
            loadFileBtn.setEnabled(true);
        }
    }

    private void setSystemStatus(String status){
        systemStatus.setText("System Status: " + status);
    }

    private void refreshCounts(){
        int queue = scheduler.getQueueSize();
        int inProgress = scheduler.getInProgressCount();
        countsLabel.setText("Queue: " + queue + " | In-Progress: " + inProgress + " | Active fires: " + (queue + inProgress));
        refreshZoneMap();
    }

    private void log(String msg){
        logArea.append(timestamp() + " " + msg + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private String timestamp(){
        return new SimpleDateFormat("HH:mm:ss").format(new Date());
    }

    @Override
    public void onIncidentQueued(Incident incident){
        SwingUtilities.invokeLater(() -> {
            String key = incident.getKey();
            if (!incidentRowByKey.containsKey(key)) {
                int row = incidentModel.getRowCount();
                incidentModel.addRow(new Object[]{
                        incident.getTime(),
                        incident.getZoneId(),
                        incident.getEventType(),
                        incident.getSeverity(),
                        "QUEUED",
                        "-"
                });
                incidentRowByKey.put(key, row);
            }
            refreshCounts();
            refreshZoneMap();
        });
    }

    @Override
    public void onIncidentDispatched(int droneId, Incident incident){
        SwingUtilities.invokeLater(() -> {
            String key = incident.getKey();
            Integer row = incidentRowByKey.get(key);
            if (row != null) {
                incidentModel.setValueAt("DISPATCHED", row, 4);
                incidentModel.setValueAt(droneId, row, 5);
            }
            refreshCounts();
            refreshZoneMap();
        });
    }

    @Override
    public void onIncidentCompleted(int droneId, Incident incident){
        SwingUtilities.invokeLater(() -> {
            String key = incident.getKey();
            Integer row = incidentRowByKey.get(key);
            if (row != null) {
                incidentModel.setValueAt("COMPLETED", row, 4);
                incidentModel.setValueAt(droneId, row, 5);
            }
            refreshCounts();
            refreshZoneMap();
        });
    }

    @Override
    public void onDroneStateChanged(int droneId, String state, Integer zoneId){
        SwingUtilities.invokeLater(() -> {
            int fromZone = 0;
            Integer row = droneRowById.get(droneId);
            if (row != null) {
                Object zoneObj = droneModel.getValueAt(row, 2);
                if (zoneObj != null && !"-".equals(zoneObj.toString().trim())) {
                    try {
                        fromZone = Integer.parseInt(zoneObj.toString().trim());
                    } catch (NumberFormatException ignored) {}
                }
            }
            if ("EN_ROUTE".equals(state) && zoneId != null && zoneId != fromZone) {
                long dur = travelDurationMs(fromZone, zoneId);
                droneAnimations.put(droneId, new ZoneMapPanel.DroneAnimation(fromZone, zoneId, System.currentTimeMillis(), dur));
            } else if ("RETURNING".equals(state) && fromZone != 0) {
                long dur = travelDurationMs(fromZone, 0);
                droneAnimations.put(droneId, new ZoneMapPanel.DroneAnimation(fromZone, 0, System.currentTimeMillis(), dur));
            } else if ("ARRIVED".equals(state) || "IDLE".equals(state) || "EXTINGUISHING".equals(state)) {
                droneAnimations.remove(droneId);
            }

            if (row == null) {
                row = droneModel.getRowCount();
                droneModel.addRow(new Object[]{
                        droneId,
                        state,
                        zoneId == null ? "-" : zoneId,
                        timestamp()
                });
                droneRowById.put(droneId, row);
            } else {
                if (state != null) droneModel.setValueAt(state, row, 1);
                if (zoneId != null) droneModel.setValueAt(zoneId, row, 2);
                else droneModel.setValueAt("-", row, 2);
                droneModel.setValueAt(timestamp(), row, 3);
            }
            refreshZoneMap();
        });
    }

    @Override
    public void onLog(String message){
        SwingUtilities.invokeLater(() -> log(message));
    }

    @Override
    public void onSimulationComplete() {
        SwingUtilities.invokeLater(() -> {
            setSystemStatus("Simulation complete");
            refreshCounts();
        });
    }

    @Override
    public void onDroneFaultDetected(int droneId, String faultMessage) {
        SwingUtilities.invokeLater(() -> {
            log("[GUI] Drone " + droneId + " fault detected: " + faultMessage);
            int row = droneRowById.getOrDefault(droneId, -1);
            if (row != -1) {
                //don't change state to fault
                // droneModel.setValueAt("FAULT", row, 1); // Update the drone state to "FAULT"
                droneModel.setValueAt(timestamp(), row, 3); // Update the last update timestamp
            }
            refreshZoneMap();
        });
    }
}

