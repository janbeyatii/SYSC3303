package app;

import app.theme.DashboardTheme;
import app.ui.BadgeCellRenderer;
import app.ui.DashboardEventLog;
import app.ui.MapLegendPanel;
import app.ui.TintedTable;
import fireincident.Scheduler;
import fireincident.SchedulerListener;
import model.DroneTelemetry;
import model.Incident;
import model.SimulationMetricsReport;
import model.ZoneConfig;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Swing dashboard for the scheduler: zone map, incident and drone tables, KPI strip, event log,
 * and controls to load an incident file and restart {@link app.FireIncidentMain} as a separate process.
 */
public class SchedulerGUI extends JFrame implements SchedulerListener {

    private static final int INC_COL_STATUS = 6;
    private static final int INC_COL_DRONE = 7;

    private static final int DRONE_COL_HEALTH = 1;
    private static final int DRONE_COL_STATE = 2;
    private static final int DRONE_COL_ZONE = 3;
    private static final int DRONE_COL_DEST = 4;
    private static final int DRONE_COL_DIST = 5;
    private static final int DRONE_COL_AGENT = 6;
    private static final int DRONE_COL_BATTERY = 7;
    private static final int DRONE_COL_TIME = 8;

    private final Scheduler scheduler;
    private final SimConfig simConfig;
    private final double timeScale;

    private JLabel systemStatusLabel;
    private JLabel kpiQueueVal;
    private JLabel kpiInProgressVal;
    private JLabel kpiCompletedVal;
    private JLabel kpiDronesIdleVal;
    private JLabel kpiDronesBusyVal;
    private JLabel kpiDronesFaultVal;
    private JButton loadFileBtn;
    private JButton startBtn;
    private JButton clearLogBtn;

    private JTextField fileField;

    private JTable incidentTable;
    private DefaultTableModel incidentModel;

    private JTable droneTable;
    private DefaultTableModel droneModel;

    private ZoneMapPanel zoneMapPanel;
    private DashboardEventLog eventLog;
    private JTextArea metricsArea;

    private JPanel alertBanner;
    private JLabel alertHeadline;
    private JLabel alertBody;

    /** KPI chip panel for "Drones fault/offline" — highlighted when count is positive */
    private JPanel kpiFaultChipPanel;

    private final Map<String, Integer> incidentRowByKey = new HashMap<>();
    private final Map<Integer, Integer> droneRowById = new HashMap<>();
    private final Map<Integer, ZoneMapPanel.DroneAnimation> droneAnimations = new HashMap<>();

    private final String defaultCsvPath;
    private final ZoneConfig zoneConfig;

    private int completedIncidentCount;

    /** Subprocess for {@link app.FireIncidentMain}; used to restart or replace a run. */
    private volatile Process fireIncidentProcess;

    /**
     * Same formula as {@link fireincident.DroneSubsystem}: simulated seconds, scaled to wall clock via {@link SimConfig#getDroneTimeScale()}.
     */
    private long travelDurationMs(int fromZone, int toZone) {
        double distanceMeters = zoneConfig.getDistanceMeters(fromZone, toZone);
        double simSeconds = 24 + distanceMeters / 10.0;
        return Math.max(1L, (long) (simSeconds * 1000 * timeScale));
    }

    private static String fmtDroneDest(Integer z) {
        return z == null ? "-" : String.valueOf(z);
    }

    private static String fmtDroneDist(Double m) {
        if (m == null) return "-";
        return String.format("%.1f", m);
    }

    private static String fmtDroneAgent(int rem, int cap) {
        return rem + " / " + cap + " L";
    }

    private static String fmtDroneBattery(double rem, double max) {
        return String.format("%.0f / %.0f s", rem, max);
    }

    /** Normal vs fault-style states for dispatcher visibility (Iteration 5). */
    private static String fmtDroneHealth(String state) {
        if (state == null) return "—";
        String u = state.toUpperCase();
        if ("OFFLINE".equals(u) || "UNAVAILABLE".equals(u) || "FAULTED".equals(u)) {
            return "Fault / offline";
        }
        return "Normal";
    }

    public SchedulerGUI(Scheduler scheduler) {
        this(scheduler, "data/final_event_file_w26.csv", new SimConfig());
    }

    public SchedulerGUI(Scheduler scheduler, String defaultCsvPath) {
        this(scheduler, defaultCsvPath, new SimConfig());
    }

    public SchedulerGUI(Scheduler scheduler, String defaultCsvPath, SimConfig simConfig) {
        this.scheduler = scheduler;
        this.simConfig = simConfig != null ? simConfig : new SimConfig();
        this.timeScale = this.simConfig.getDroneTimeScale() <= 0 ? 1.0 : this.simConfig.getDroneTimeScale();
        this.zoneConfig = new ZoneConfig();
        this.defaultCsvPath = defaultCsvPath != null && !defaultCsvPath.isEmpty()
                ? defaultCsvPath.trim()
                : "data/final_event_file_w26.csv";

        this.scheduler.addListener(this);
        setTitle("Firefighting Drone Swarm — Dispatcher console (Iteration 5)");
        setMinimumSize(new Dimension(1024, 680));
        setSize(1180, 760);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(DashboardTheme.PAD_SM, DashboardTheme.PAD_SM));
        getContentPane().setBackground(DashboardTheme.APP_BACKGROUND);

        buildToolbarAndKpi();
        buildCenterAndLog();

        setSystemStatus("IDLE");
        refreshCounts();
    }

    private void buildToolbarAndKpi() {
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.setOpaque(true);
        north.setBackground(DashboardTheme.APP_BACKGROUND);
        north.setBorder(new javax.swing.border.EmptyBorder(DashboardTheme.PAD_MD, DashboardTheme.PAD_MD, 0, DashboardTheme.PAD_MD));

        JPanel toolbar = new JPanel(new BorderLayout(8, 4));
        toolbar.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        left.setOpaque(false);
        JLabel statusTitle = new JLabel("Status");
        statusTitle.setFont(DashboardTheme.uiFont(12f));
        statusTitle.setForeground(DashboardTheme.TEXT_MUTED);
        systemStatusLabel = new JLabel("IDLE");
        systemStatusLabel.setFont(DashboardTheme.uiFont(14f).deriveFont(Font.BOLD));
        systemStatusLabel.setForeground(DashboardTheme.TEXT_PRIMARY);
        systemStatusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DashboardTheme.ACCENT, 1, true),
                BorderFactory.createEmptyBorder(5, 14, 5, 14)));
        systemStatusLabel.setBackground(DashboardTheme.ACCENT_SOFT);
        systemStatusLabel.setOpaque(true);
        left.add(statusTitle);
        left.add(systemStatusLabel);
        toolbar.add(left, BorderLayout.WEST);

        JPanel fileRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        fileRow.setOpaque(false);
        JLabel fileLbl = new JLabel("Incident file");
        fileLbl.setFont(DashboardTheme.uiFont(12f));
        fileLbl.setForeground(DashboardTheme.TEXT_MUTED);
        fileField = new JTextField(defaultCsvPath, 36);
        fileField.setEditable(false);
        loadFileBtn = new JButton("Load CSV…");
        startBtn = new JButton("Restart simulation");
        startBtn.setToolTipText("Fully resets scheduler state and the dashboard, then runs the selected CSV again (stops any in-flight fire process).");
        loadFileBtn.addActionListener(e -> chooseFile());
        startBtn.addActionListener(e -> restartSimulation());
        fileRow.add(fileLbl);
        fileRow.add(fileField);
        fileRow.add(loadFileBtn);
        fileRow.add(startBtn);
        toolbar.add(fileRow, BorderLayout.EAST);
        north.add(toolbar);

        JPanel kpi = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 6));
        kpi.setOpaque(true);
        kpi.setBackground(DashboardTheme.PANEL_ELEVATED);
        kpi.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(8, 0, 8, 0),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(0xE2E8F0), 1, true),
                        BorderFactory.createEmptyBorder(10, 14, 10, 14))));
        kpiQueueVal = addKpiChip(kpi, "Queue", "0");
        kpiInProgressVal = addKpiChip(kpi, "In progress", "0");
        kpiCompletedVal = addKpiChip(kpi, "Completed", "0");
        kpi.add(Box.createHorizontalStrut(12));
        kpiDronesIdleVal = addKpiChip(kpi, "Drones idle", "0");
        kpiDronesBusyVal = addKpiChip(kpi, "Drones busy", "0");
        kpiDronesFaultVal = addKpiChip(kpi, "Drones fault/offline", "0");
        kpiFaultChipPanel = (JPanel) kpiDronesFaultVal.getParent();
        north.add(kpi);

        add(north, BorderLayout.NORTH);
    }

    private enum AlertKind {
        FAULT,
        WARN,
        INFO,
        SUCCESS
    }

    /** Adds a titled KPI chip to the parent; returns the value label for updates. */
    private JLabel addKpiChip(JPanel parent, String title, String value) {
        JPanel p = new JPanel(new BorderLayout(0, 2));
        p.setOpaque(false);
        JLabel t = new JLabel(title);
        t.setFont(DashboardTheme.uiFont(11f));
        t.setForeground(DashboardTheme.TEXT_MUTED);
        JLabel v = new JLabel(value);
        v.setFont(DashboardTheme.uiFont(16f).deriveFont(Font.BOLD));
        v.setForeground(DashboardTheme.TEXT_PRIMARY);
        p.add(t, BorderLayout.NORTH);
        p.add(v, BorderLayout.CENTER);
        parent.add(p);
        return v;
    }

    private static javax.swing.border.Border sectionTitle(String title) {
        return BorderFactory.createTitledBorder(
                BorderFactory.createEmptyBorder(),
                title,
                javax.swing.border.TitledBorder.LEFT,
                javax.swing.border.TitledBorder.TOP,
                DashboardTheme.uiFont(12f).deriveFont(Font.BOLD),
                DashboardTheme.TEXT_MUTED);
    }

    private void buildCenterAndLog() {
        zoneMapPanel = new ZoneMapPanel(zoneConfig);

        incidentModel = new DefaultTableModel(
                new Object[]{"Time", "Zone", "Type", "Severity (L)", "Fault", "Target", "Status", "Drone"}, 0
        ) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        incidentTable = new TintedTable(incidentModel, -1);
        incidentTable.setRowHeight(26);
        incidentTable.setAutoCreateRowSorter(true);
        applyTableStyle(incidentTable);
        incidentTable.getColumnModel().getColumn(INC_COL_STATUS).setCellRenderer(new BadgeCellRenderer(BadgeCellRenderer.Kind.INCIDENT_STATUS));

        JPanel incidentPanel = DashboardTheme.wrapCard(new JScrollPane(incidentTable));
        ((JComponent) incidentPanel.getComponent(0)).setBorder(sectionTitle("Incidents"));

        droneModel = new DefaultTableModel(
                new Object[]{"Drone", "Health", "State", "Zone", "Dest", "Dist (m)", "Agent (fuel)", "Battery", "Last update"}, 0
        ) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        droneTable = new TintedTable(droneModel, 1);
        droneTable.setRowHeight(26);
        droneTable.setAutoCreateRowSorter(true);
        applyTableStyle(droneTable);
        droneTable.getColumnModel().getColumn(DRONE_COL_STATE).setCellRenderer(new BadgeCellRenderer(BadgeCellRenderer.Kind.DRONE_STATE));

        JPanel dronePanel = DashboardTheme.wrapCard(new JScrollPane(droneTable));
        ((JComponent) dronePanel.getComponent(0)).setBorder(sectionTitle("Drones"));

        JSplitPane tablesSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        tablesSplit.setResizeWeight(0.55);
        tablesSplit.setTopComponent(incidentPanel);
        tablesSplit.setBottomComponent(dronePanel);

        JPanel mapCard = DashboardTheme.wrapCard(zoneMapPanel);
        ((JComponent) mapCard.getComponent(0)).setBorder(sectionTitle("Live map"));

        JPanel legendCard = DashboardTheme.wrapCard(new MapLegendPanel());
        ((JComponent) legendCard.getComponent(0)).setBorder(sectionTitle("Legend"));

        JPanel mapColumn = new JPanel(new BorderLayout(0, DashboardTheme.PAD_SM));
        mapColumn.setOpaque(false);
        mapColumn.add(mapCard, BorderLayout.CENTER);
        mapColumn.add(legendCard, BorderLayout.SOUTH);

        JSplitPane centerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        centerSplit.setResizeWeight(0.52);
        centerSplit.setLeftComponent(mapColumn);
        centerSplit.setRightComponent(tablesSplit);

        eventLog = new DashboardEventLog();
        eventLog.setPreferredSize(new Dimension(200, 140));

        JPanel logPanel = new JPanel(new BorderLayout(0, 4));
        logPanel.setOpaque(false);
        logPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEmptyBorder(),
                "Event log — faults and key events are color-coded",
                javax.swing.border.TitledBorder.LEFT,
                javax.swing.border.TitledBorder.TOP,
                DashboardTheme.uiFont(12f).deriveFont(Font.BOLD),
                DashboardTheme.TEXT_MUTED));
        logPanel.add(eventLog, BorderLayout.CENTER);
        clearLogBtn = new JButton("Clear log");
        clearLogBtn.addActionListener(e -> eventLog.clear());
        JPanel logSouth = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        logSouth.setOpaque(false);
        logSouth.add(clearLogBtn);
        logPanel.add(logSouth, BorderLayout.SOUTH);

        metricsArea = new JTextArea(5, 42);
        metricsArea.setEditable(false);
        metricsArea.setLineWrap(true);
        metricsArea.setWrapStyleWord(true);
        metricsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        metricsArea.setBackground(new Color(0xF8FAFC));
        metricsArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        metricsArea.setText("Final demo performance metrics appear here when the scheduler finishes a run.\n");

        JScrollPane metricsScroll = new JScrollPane(metricsArea);
        metricsScroll.setPreferredSize(new Dimension(200, 120));
        JPanel metricsPanel = new JPanel(new BorderLayout());
        metricsPanel.setOpaque(false);
        metricsPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEmptyBorder(),
                "Performance metrics — response, completion, utilization, queue",
                javax.swing.border.TitledBorder.LEFT,
                javax.swing.border.TitledBorder.TOP,
                DashboardTheme.uiFont(12f).deriveFont(Font.BOLD),
                DashboardTheme.TEXT_MUTED));
        metricsPanel.add(metricsScroll, BorderLayout.CENTER);

        JSplitPane logMetricsSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        logMetricsSplit.setResizeWeight(0.55);
        logMetricsSplit.setTopComponent(logPanel);
        logMetricsSplit.setBottomComponent(metricsPanel);

        JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        vertical.setResizeWeight(0.72);
        vertical.setTopComponent(centerSplit);
        vertical.setBottomComponent(logMetricsSplit);

        alertBanner = buildAlertBanner();

        JPanel centerWrap = new JPanel(new BorderLayout(0, DashboardTheme.PAD_SM));
        centerWrap.setOpaque(false);
        centerWrap.add(alertBanner, BorderLayout.NORTH);
        centerWrap.add(vertical, BorderLayout.CENTER);

        add(centerWrap, BorderLayout.CENTER);
    }

    private JPanel buildAlertBanner() {
        JPanel p = new JPanel(new BorderLayout(12, 4));
        p.setOpaque(true);
        p.setVisible(false);

        alertHeadline = new JLabel(" ");
        alertHeadline.setFont(DashboardTheme.uiFont(14f).deriveFont(Font.BOLD));
        alertBody = new JLabel(" ");
        alertBody.setFont(DashboardTheme.uiFont(12f));

        JPanel textCol = new JPanel(new BorderLayout(0, 4));
        textCol.setOpaque(false);
        textCol.add(alertHeadline, BorderLayout.NORTH);
        textCol.add(alertBody, BorderLayout.CENTER);

        JButton dismiss = new JButton("Dismiss");
        dismiss.addActionListener(e -> hideAlert());
        dismiss.setFocusable(false);

        p.add(textCol, BorderLayout.CENTER);
        p.add(dismiss, BorderLayout.EAST);
        return p;
    }

    private void hideAlert() {
        if (alertBanner != null) {
            alertBanner.setVisible(false);
            alertBanner.revalidate();
        }
    }

    private void showAlert(AlertKind kind, String headline, String detail) {
        Color bg;
        Color fg;
        Color border;
        switch (kind) {
            case FAULT:
                bg = DashboardTheme.ALERT_FAULT_BG;
                fg = DashboardTheme.ALERT_FAULT_FG;
                border = DashboardTheme.ALERT_FAULT_BORDER;
                break;
            case WARN:
                bg = DashboardTheme.ALERT_WARN_BG;
                fg = DashboardTheme.ALERT_WARN_FG;
                border = DashboardTheme.ALERT_WARN_BORDER;
                break;
            case SUCCESS:
                bg = new Color(0xECFDF5);
                fg = new Color(0x166534);
                border = new Color(0x22C55E);
                break;
            case INFO:
            default:
                bg = DashboardTheme.ALERT_INFO_BG;
                fg = DashboardTheme.ALERT_INFO_FG;
                border = DashboardTheme.ALERT_INFO_BORDER;
                break;
        }
        alertBanner.setBackground(bg);
        alertBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border, 2, true),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        alertHeadline.setForeground(fg);
        alertBody.setForeground(fg);
        alertHeadline.setText(headline);
        alertBody.setText(detail == null ? "" : detail);
        alertBanner.setVisible(true);
        if (alertBanner.getParent() != null) {
            alertBanner.getParent().revalidate();
        }
    }

    private static void applyTableStyle(JTable table) {
        table.setFillsViewportHeight(true);
        table.setShowGrid(true);
        table.setGridColor(new Color(0xE2E8F0));
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFont(DashboardTheme.uiFont(12f));
        table.setRowHeight(26);
        table.getTableHeader().setFont(DashboardTheme.uiFont(11f).deriveFont(Font.BOLD));
        table.getTableHeader().setBackground(new Color(0xF1F5F9));
        table.getTableHeader().setForeground(DashboardTheme.TEXT_PRIMARY);
        table.getTableHeader().setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xCBD5E1)),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        table.setSelectionBackground(DashboardTheme.ACCENT_SOFT);
        table.setSelectionForeground(DashboardTheme.TEXT_PRIMARY);
        table.getTableHeader().setReorderingAllowed(false);
    }

    private void refreshZoneMap() {
        Map<Integer, Integer> droneZones = new HashMap<>();
        Map<Integer, String> droneStates = new HashMap<>();
        for (int r = 0; r < droneModel.getRowCount(); r++) {
            Object idObj = droneModel.getValueAt(r, 0);
            Object stateObj = droneModel.getValueAt(r, DRONE_COL_STATE);
            Object zoneObj = droneModel.getValueAt(r, DRONE_COL_ZONE);
            if (idObj == null) continue;
            int droneId = idObj instanceof Number ? ((Number) idObj).intValue() : Integer.parseInt(String.valueOf(idObj));
            if (stateObj != null) droneStates.put(droneId, stateObj.toString());
            Integer zone = null;
            if (zoneObj != null && !"-".equals(zoneObj.toString().trim())) {
                try {
                    zone = Integer.parseInt(zoneObj.toString().trim());
                } catch (NumberFormatException ignored) {
                }
            }
            droneZones.put(droneId, zone);
        }
        Map<Integer, String> zoneStatus = new HashMap<>();
        for (int r = 0; r < incidentModel.getRowCount(); r++) {
            Object zoneObj = incidentModel.getValueAt(r, 1);
            Object statusObj = incidentModel.getValueAt(r, INC_COL_STATUS);
            if (zoneObj == null || statusObj == null) continue;
            int zone;
            try {
                zone = Integer.parseInt(zoneObj.toString().trim());
            } catch (NumberFormatException ignored) {
                continue;
            }
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

    private void refreshKpiStrip() {
        int queue = scheduler.getQueueSize();
        int inProgress = scheduler.getInProgressCount();
        kpiQueueVal.setText(String.valueOf(queue));
        kpiInProgressVal.setText(String.valueOf(inProgress));
        kpiCompletedVal.setText(String.valueOf(completedIncidentCount));

        int idle = 0, busy = 0, fault = 0;
        for (int r = 0; r < droneModel.getRowCount(); r++) {
            Object s = droneModel.getValueAt(r, DRONE_COL_STATE);
            if (s == null) continue;
            String u = s.toString().toUpperCase();
            if ("IDLE".equals(u)) idle++;
            else if ("OFFLINE".equals(u) || "UNAVAILABLE".equals(u) || "FAULTED".equals(u)) fault++;
            else busy++;
        }
        kpiDronesIdleVal.setText(String.valueOf(idle));
        kpiDronesBusyVal.setText(String.valueOf(busy));
        kpiDronesFaultVal.setText(String.valueOf(fault));
        styleFaultKpiChip(fault);
    }

    private void styleFaultKpiChip(int fault) {
        if (kpiFaultChipPanel == null) return;
        if (fault > 0) {
            kpiFaultChipPanel.setOpaque(true);
            kpiFaultChipPanel.setBackground(DashboardTheme.KPI_FAULT_BG);
            kpiFaultChipPanel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(DashboardTheme.KPI_FAULT_BORDER, 1, true),
                    BorderFactory.createEmptyBorder(4, 10, 4, 10)));
            kpiDronesFaultVal.setForeground(DashboardTheme.KPI_FAULT_FG);
            kpiDronesFaultVal.setFont(DashboardTheme.uiFont(17f).deriveFont(Font.BOLD));
            for (Component comp : kpiFaultChipPanel.getComponents()) {
                if (comp instanceof JLabel && comp != kpiDronesFaultVal) {
                    ((JLabel) comp).setForeground(DashboardTheme.KPI_FAULT_FG);
                }
            }
        } else {
            kpiFaultChipPanel.setOpaque(false);
            kpiFaultChipPanel.setBorder(null);
            kpiDronesFaultVal.setForeground(DashboardTheme.TEXT_PRIMARY);
            kpiDronesFaultVal.setFont(DashboardTheme.uiFont(16f).deriveFont(Font.BOLD));
            for (Component comp : kpiFaultChipPanel.getComponents()) {
                if (comp instanceof JLabel && comp != kpiDronesFaultVal) {
                    ((JLabel) comp).setForeground(DashboardTheme.TEXT_MUTED);
                }
            }
        }
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser(new File("."));
        chooser.setDialogTitle("Select incident CSV");
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            fileField.setText(chooser.getSelectedFile().getPath());
            log("[GUI] Selected file: " + chooser.getSelectedFile().getPath());
        }
    }

    private void resetDashboardForNewRun() {
        hideAlert();
        incidentModel.setRowCount(0);
        droneModel.setRowCount(0);
        incidentRowByKey.clear();
        droneRowById.clear();
        droneAnimations.clear();
        completedIncidentCount = 0;
        if (metricsArea != null) {
            metricsArea.setText("Final demo performance metrics appear here when the scheduler finishes a run.\n");
        }
        refreshKpiStrip();
        refreshZoneMap();
    }

    /**
     * Resolves a CSV path relative to the JVM working directory when needed (IDE / different cwd).
     */
    private static File resolveCsvFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) return null;
        File f = new File(filePath.trim());
        if (f.isFile()) return f;
        if (!f.isAbsolute()) {
            File rel = new File(System.getProperty("user.dir", "."), filePath.trim());
            if (rel.isFile()) return rel;
        }
        return f;
    }

    private static String resolveJavaExecutable() {
        String home = System.getProperty("java.home");
        if (home == null || home.isEmpty()) {
            return "java";
        }
        File win = new File(home, "bin" + File.separator + "java.exe");
        if (win.isFile()) return win.getAbsolutePath();
        File unix = new File(home, "bin" + File.separator + "java");
        if (unix.isFile()) return unix.getAbsolutePath();
        return "java";
    }

    /** Stops a previous FireIncidentMain process so Start can run the CSV again. */
    private void stopFireIncidentProcessIfRunning() {
        Process p = fireIncidentProcess;
        if (p == null) return;
        if (!p.isAlive()) {
            fireIncidentProcess = null;
            return;
        }
        log("[GUI] Stopping previous Fire Incident process (restart)…");
        p.destroyForcibly();
        try {
            p.waitFor(8, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (fireIncidentProcess == p) {
            fireIncidentProcess = null;
        }
    }

    private void restartSimulation() {
        String filePath = fileField.getText().trim();
        File csv = resolveCsvFile(filePath);
        if (csv == null || !csv.isFile()) {
            String msg = "CSV file not found: " + filePath
                    + "\n(Working directory: " + System.getProperty("user.dir", ".") + ")";
            log("[GUI] " + msg.replace("\n", " "));
            setSystemStatus("ERROR");
            JOptionPane.showMessageDialog(this, msg, "Restart failed", JOptionPane.ERROR_MESSAGE);
            return;
        }

        stopFireIncidentProcessIfRunning();
        scheduler.resetForNewSimulationRun();
        log("[GUI] Scheduler reset for new simulation run.");

        resetDashboardForNewRun();
        setSystemStatus("RUNNING");
        loadFileBtn.setEnabled(false);
        String canonicalPath = csv.getAbsolutePath();
        log("[GUI] Spawning Fire Incident Subsystem: " + canonicalPath);

        SimConfig config = simConfig;
        String cp = System.getProperty("java.class.path", "bin");
        File dir = new File(System.getProperty("user.dir", "."));
        try {
            String javaExe = resolveJavaExecutable();
            ProcessBuilder pb = new ProcessBuilder(
                    javaExe, "-cp", cp,
                    "app.FireIncidentMain",
                    canonicalPath,
                    config.getSchedulerHost(),
                    String.valueOf(config.getSchedulerPort())
            );
            pb.directory(dir);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            fireIncidentProcess = p;
            log("[GUI] Fire Incident Subsystem started (PID " + p.pid() + ")");
            Thread outputReader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String out = line;
                        SwingUtilities.invokeLater(() -> log("[FIS] " + out));
                    }
                } catch (IOException e) {
                    SwingUtilities.invokeLater(() -> log("[GUI] Error reading Fire Incident output: " + e.getMessage()));
                }
            }, "FireIncidentProcess-Output");
            outputReader.setDaemon(true);
            outputReader.start();

            final Process watched = p;
            Thread watcher = new Thread(() -> {
                int exitCode = -1;
                try {
                    exitCode = watched.waitFor();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                int finalExitCode = exitCode;
                SwingUtilities.invokeLater(() -> {
                    if (fireIncidentProcess != watched) {
                        return;
                    }
                    fireIncidentProcess = null;
                    if (finalExitCode == 0) {
                        setSystemStatus("DONE");
                        showAlert(AlertKind.SUCCESS, "Fire subsystem finished",
                                "The incident CSV process exited normally (code 0).");
                    } else {
                        setSystemStatus("ERROR");
                        showAlert(AlertKind.FAULT, "Fire subsystem failed",
                                "Process exited with code " + finalExitCode + ". See Event log for details.");
                        JOptionPane.showMessageDialog(
                                this,
                                "Fire Incident Subsystem exited with code " + finalExitCode + ".\nCheck Event log for details.",
                                "Subsystem error",
                                JOptionPane.ERROR_MESSAGE
                        );
                    }
                    loadFileBtn.setEnabled(true);
                    refreshCounts();
                    log("[GUI] Fire Incident Subsystem process finished (exit " + finalExitCode + ").");
                });
            }, "FireIncidentProcess-Watcher");
            watcher.setDaemon(true);
            watcher.start();
        } catch (IOException e) {
            fireIncidentProcess = null;
            log("[GUI] Failed to start Fire Incident Subsystem: " + e.getMessage());
            setSystemStatus("ERROR");
            JOptionPane.showMessageDialog(this,
                    "Could not start FireIncidentMain:\n" + e.getMessage()
                            + "\n\nJava: " + resolveJavaExecutable() + "\nClasspath length: " + cp.length(),
                    "Start failed", JOptionPane.ERROR_MESSAGE);
            loadFileBtn.setEnabled(true);
        }
    }

    private void setSystemStatus(String status) {
        systemStatusLabel.setText(status);
        String u = status.toUpperCase();
        if (u.contains("ERROR")) {
            systemStatusLabel.setBackground(new Color(0xFEE2E2));
            systemStatusLabel.setForeground(new Color(0x991B1B));
            systemStatusLabel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(0xF43F5E), 1, true),
                    BorderFactory.createEmptyBorder(5, 14, 5, 14)));
        } else if (u.contains("RUNNING")) {
            systemStatusLabel.setBackground(new Color(0xDCFCE7));
            systemStatusLabel.setForeground(new Color(0x166534));
            systemStatusLabel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(0x22C55E), 1, true),
                    BorderFactory.createEmptyBorder(5, 14, 5, 14)));
        } else if (u.contains("SIMULATION COMPLETE") || u.equals("DONE")) {
            systemStatusLabel.setBackground(new Color(0xDBEAFE));
            systemStatusLabel.setForeground(new Color(0x1E40AF));
            systemStatusLabel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(0x3B82F6), 1, true),
                    BorderFactory.createEmptyBorder(5, 14, 5, 14)));
        } else {
            systemStatusLabel.setBackground(DashboardTheme.ACCENT_SOFT);
            systemStatusLabel.setForeground(DashboardTheme.TEXT_PRIMARY);
            systemStatusLabel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(DashboardTheme.ACCENT, 1, true),
                    BorderFactory.createEmptyBorder(5, 14, 5, 14)));
        }
    }

    private void refreshCounts() {
        int queue = scheduler.getQueueSize();
        int inProgress = scheduler.getInProgressCount();
        kpiQueueVal.setText(String.valueOf(queue));
        kpiInProgressVal.setText(String.valueOf(inProgress));
        refreshKpiStrip();
        refreshZoneMap();
    }

    private void log(String msg) {
        eventLog.appendLine(timestamp() + " " + msg);
    }

    private String timestamp() {
        return new SimpleDateFormat("HH:mm:ss").format(new Date());
    }

    private static String fmtFault(Incident incident) {
        String ft = incident.getFaultType();
        if (ft == null || Incident.NO_FAULT.equalsIgnoreCase(ft)) return "—";
        return ft;
    }

    private static String fmtTarget(Incident incident) {
        String ft = incident.getFaultType();
        if (ft == null || Incident.NO_FAULT.equalsIgnoreCase(ft)) return "—";
        return incident.getFaultTargetType() + " · " + incident.getFaultTargetId();
    }

    @Override
    public void onIncidentQueued(Incident incident) {
        SwingUtilities.invokeLater(() -> {
            String key = incident.getKey();
            if (!incidentRowByKey.containsKey(key)) {
                int row = incidentModel.getRowCount();
                incidentModel.addRow(new Object[]{
                        incident.getTime(),
                        incident.getZoneId(),
                        incident.getEventType(),
                        incident.getSeverity(),
                        fmtFault(incident),
                        fmtTarget(incident),
                        "QUEUED",
                        "—"
                });
                incidentRowByKey.put(key, row);
            }
            refreshCounts();
            refreshZoneMap();
        });
    }

    @Override
    public void onIncidentDispatched(int droneId, Incident incident) {
        SwingUtilities.invokeLater(() -> {
            String key = incident.getKey();
            Integer row = incidentRowByKey.get(key);
            if (row != null) {
                incidentModel.setValueAt("DISPATCHED", row, INC_COL_STATUS);
                incidentModel.setValueAt(droneId, row, INC_COL_DRONE);
            }
            refreshCounts();
            refreshZoneMap();
        });
    }

    @Override
    public void onIncidentCompleted(int droneId, Incident incident) {
        SwingUtilities.invokeLater(() -> {
            String key = incident.getKey();
            Integer row = incidentRowByKey.get(key);
            if (row != null) {
                incidentModel.setValueAt("COMPLETED", row, INC_COL_STATUS);
                incidentModel.setValueAt(droneId, row, INC_COL_DRONE);
            }
            completedIncidentCount++;
            refreshKpiStrip();
            refreshZoneMap();
        });
    }

    @Override
    public void onDroneStateChanged(int droneId, String state, Integer zoneId) {
        SwingUtilities.invokeLater(() -> {
            int fromZone = 0;
            Integer row = droneRowById.get(droneId);
            if (row != null) {
                Object zoneObj = droneModel.getValueAt(row, DRONE_COL_ZONE);
                if (zoneObj != null && !"-".equals(zoneObj.toString().trim())) {
                    try {
                        fromZone = Integer.parseInt(zoneObj.toString().trim());
                    } catch (NumberFormatException ignored) {
                    }
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
                        fmtDroneHealth(state),
                        state,
                        zoneId == null ? "-" : zoneId,
                        "-",
                        "-",
                        "-",
                        "-",
                        timestamp()
                });
                droneRowById.put(droneId, row);
            } else {
                if (state != null) {
                    droneModel.setValueAt(fmtDroneHealth(state), row, DRONE_COL_HEALTH);
                    droneModel.setValueAt(state, row, DRONE_COL_STATE);
                }
                if (zoneId != null) droneModel.setValueAt(zoneId, row, DRONE_COL_ZONE);
                else droneModel.setValueAt("-", row, DRONE_COL_ZONE);
                droneModel.setValueAt(timestamp(), row, DRONE_COL_TIME);
            }
            refreshKpiStrip();
            refreshZoneMap();
        });
    }

    @Override
    public void onDroneTelemetryUpdated(DroneTelemetry t) {
        SwingUtilities.invokeLater(() -> {
            Integer row = droneRowById.get(t.droneId());
            if (row == null) {
                row = droneModel.getRowCount();
                droneModel.addRow(new Object[]{
                        t.droneId(),
                        fmtDroneHealth(t.state()),
                        t.state(),
                        t.zoneId() == null ? "-" : t.zoneId(),
                        fmtDroneDest(t.destinationZoneId()),
                        fmtDroneDist(t.distanceToDestinationMeters()),
                        fmtDroneAgent(t.agentRemainingLitres(), t.agentCapacityLitres()),
                        fmtDroneBattery(t.batteryRemainingSeconds(), t.batteryMaxSeconds()),
                        timestamp()
                });
                droneRowById.put(t.droneId(), row);
            } else {
                if (t.state() != null) {
                    droneModel.setValueAt(fmtDroneHealth(t.state()), row, DRONE_COL_HEALTH);
                    droneModel.setValueAt(t.state(), row, DRONE_COL_STATE);
                }
                if (t.zoneId() != null) droneModel.setValueAt(t.zoneId(), row, DRONE_COL_ZONE);
                else droneModel.setValueAt("-", row, DRONE_COL_ZONE);
                droneModel.setValueAt(fmtDroneDest(t.destinationZoneId()), row, DRONE_COL_DEST);
                droneModel.setValueAt(fmtDroneDist(t.distanceToDestinationMeters()), row, DRONE_COL_DIST);
                droneModel.setValueAt(fmtDroneAgent(t.agentRemainingLitres(), t.agentCapacityLitres()), row, DRONE_COL_AGENT);
                droneModel.setValueAt(fmtDroneBattery(t.batteryRemainingSeconds(), t.batteryMaxSeconds()), row, DRONE_COL_BATTERY);
                droneModel.setValueAt(timestamp(), row, DRONE_COL_TIME);
            }
            refreshKpiStrip();
            refreshZoneMap();
        });
    }

    @Override
    public void onLog(String message) {
        SwingUtilities.invokeLater(() -> log(message));
    }

    @Override
    public void onSimulationComplete() {
        SwingUtilities.invokeLater(() -> {
            setSystemStatus("Simulation complete");
            showAlert(AlertKind.INFO, "Scheduler simulation complete",
                    "The scheduler has finished its run (e.g. all work drained or stopped).");
            refreshMetricsPanel();
            refreshCounts();
        });
    }

    @Override
    public void onSimulationMetrics(SimulationMetricsReport report) {
        SwingUtilities.invokeLater(() -> applyMetricsReport(report));
    }

    private void refreshMetricsPanel() {
        applyMetricsReport(scheduler.getSimulationMetricsReport());
    }

    private void applyMetricsReport(SimulationMetricsReport report) {
        if (metricsArea == null || report == null) return;
        List<Integer> ids = new ArrayList<>();
        for (int r = 0; r < droneModel.getRowCount(); r++) {
            Object idObj = droneModel.getValueAt(r, 0);
            if (idObj instanceof Number) {
                ids.add(((Number) idObj).intValue());
            } else if (idObj != null) {
                try {
                    ids.add(Integer.parseInt(idObj.toString().trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        metricsArea.setText(report.toDetailedString(SimulationMetricsReport.sortedIds(ids)));
        metricsArea.setCaretPosition(0);
    }

    @Override
    public void onDroneFaultDetected(int droneId, String faultMessage, boolean isHardFault) {
        SwingUtilities.invokeLater(() -> {
            String severity = isHardFault ? "HARD — drone offline" : "SOFT — temporarily unavailable";
            log("[GUI] Drone " + droneId + " fault (" + (isHardFault ? "hard" : "soft") + "): " + faultMessage);
            showAlert(AlertKind.FAULT,
                    "Drone " + droneId + " · " + severity,
                    faultMessage);
            Integer row = droneRowById.get(droneId);
            String newState = isHardFault ? "OFFLINE" : "UNAVAILABLE";
            if (row == null) {
                row = droneModel.getRowCount();
                droneModel.addRow(new Object[]{
                        droneId,
                        fmtDroneHealth(newState),
                        newState,
                        "-",
                        "-",
                        "-",
                        "-",
                        "-",
                        timestamp()
                });
                droneRowById.put(droneId, row);
            } else {
                droneModel.setValueAt(fmtDroneHealth(newState), row, DRONE_COL_HEALTH);
                droneModel.setValueAt(newState, row, DRONE_COL_STATE);
                droneModel.setValueAt(timestamp(), row, DRONE_COL_TIME);
            }
            droneAnimations.remove(droneId);
            refreshKpiStrip();
            refreshZoneMap();
        });
    }
}
