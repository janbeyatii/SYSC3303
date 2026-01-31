package app;

import fireincident.FireIncidentSubsystem;
import fireincident.Scheduler;
import fireincident.SchedulerListener;
import model.Incident;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
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

    private JTextArea logArea;

    // helps update a specific incident row
    private final Map<String, Integer> incidentRowByKey = new HashMap<>();
    private final Map<Integer, Integer> droneRowById = new HashMap<>();

    /** Default CSV path shown in the file field (from command line or default). */
    private final String defaultCsvPath;

    public SchedulerGUI(Scheduler scheduler) {
        this(scheduler, "data/Sample_event_file.csv");
    }

    public SchedulerGUI(Scheduler scheduler, String defaultCsvPath) {
        this.scheduler = scheduler;
        this.defaultCsvPath = defaultCsvPath != null && !defaultCsvPath.isEmpty()
                ? defaultCsvPath.trim()
                : "data/Sample_event_file.csv";

        // ADD: register GUI as a listener
        this.scheduler.addListener(this);

        setTitle("Firefighting Drone Swarm - Iteration 1");
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

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT));
        systemStatus = new JLabel("System Status: IDLE");
        countsLabel = new JLabel("Queue: 0 | In-Progress: 0");
        left.add(systemStatus);
        left.add(Box.createHorizontalStrut(12));
        left.add(countsLabel);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        fileField = new JTextField(defaultCsvPath, 28);
        fileField.setEditable(false);

        loadFileBtn = new JButton("Load CSV...");
        startBtn = new JButton("Start");

        loadFileBtn.addActionListener(e -> chooseFile());
        startBtn.addActionListener(e -> startSimulation());

        right.add(new JLabel("Input:"));
        right.add(fileField);
        right.add(loadFileBtn);
        right.add(startBtn);

        top.add(left, BorderLayout.WEST);
        top.add(right, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);
    }

    private void buildCenterPanels(){
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        split.setResizeWeight(0.65);

        // Incidents table
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

        // Drones table
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

        split.setTopComponent(incidentPanel);
        split.setBottomComponent(dronePanel);

        add(split, BorderLayout.CENTER);
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
        log("[GUI] Starting simulation using: " + filePath);

        // ADD: run FireIncidentSubsystem in a background thread so GUI stays responsive
        Thread t = new Thread(() -> {
            FireIncidentSubsystem fis = new FireIncidentSubsystem(filePath, scheduler);
            fis.processIncidents();

            SwingUtilities.invokeLater(() -> {
                setSystemStatus("DONE");
                startBtn.setEnabled(true);
                loadFileBtn.setEnabled(true);
                refreshCounts();
            });
        }, "FireIncidentSubsystem-Thread");

        t.start();
    }

    private void setSystemStatus(String status){
        systemStatus.setText("System Status: " + status);
    }

    private void refreshCounts(){
        countsLabel.setText("Queue: " + scheduler.getQueueSize() + " | In-Progress: " + scheduler.getInProgressCount());
    }

    private void log(String msg){
        logArea.append(timestamp() + " " + msg + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private String timestamp(){
        return new SimpleDateFormat("HH:mm:ss").format(new Date());
    }

    private String incidentKey(Incident i){
        // basic unique key for Iteration 1: time + zone + type
        return i.getTime() + "|" + i.getZoneId() + "|" + i.getEventType();
    }

    // SchedulerListener callbacks (these can be called from non-GUI threads)
    @Override
    public void onIncidentQueued(Incident incident){
        SwingUtilities.invokeLater(() -> {
            String key = incidentKey(incident);
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
        });
    }

    @Override
    public void onIncidentDispatched(int droneId, Incident incident){
        SwingUtilities.invokeLater(() -> {
            String key = incidentKey(incident);
            Integer row = incidentRowByKey.get(key);
            if (row != null) {
                incidentModel.setValueAt("DISPATCHED", row, 4);
                incidentModel.setValueAt(droneId, row, 5);
            }
            refreshCounts();
        });
    }

    @Override
    public void onIncidentCompleted(int droneId, Incident incident){
        SwingUtilities.invokeLater(() -> {
            String key = incidentKey(incident);
            Integer row = incidentRowByKey.get(key);
            if (row != null) {
                incidentModel.setValueAt("COMPLETED", row, 4);
                incidentModel.setValueAt(droneId, row, 5);
            }
            refreshCounts();
        });
    }

    @Override
    public void onDroneStateChanged(int droneId, String state, Integer zoneId){
        SwingUtilities.invokeLater(() -> {
            Integer row = droneRowById.get(droneId);
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
                if (zoneId == null && "IDLE".equals(state)) droneModel.setValueAt("-", row, 2);
                droneModel.setValueAt(timestamp(), row, 3);
            }
        });
    }

    @Override
    public void onLog(String message){
        SwingUtilities.invokeLater(() -> log(message));
    }
}

