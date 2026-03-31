package app.ui;

import app.theme.DashboardTheme;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;

/**
 * Read-only event log with color-coded lines so faults and key events stand out.
 */
public final class DashboardEventLog extends JScrollPane {

    private static final int MAX_CHARS = 280_000;

    private final JTextPane textPane;

    public DashboardEventLog() {
        textPane = new JTextPane();
        textPane.setEditable(false);
        textPane.setBackground(DashboardTheme.LOG_PANEL_BG);
        textPane.setCaretColor(DashboardTheme.LOG_TEXT_DEFAULT);
        textPane.setFont(DashboardTheme.monoFont(12f));
        textPane.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        setViewportView(textPane);
        setBorder(BorderFactory.createLineBorder(DashboardTheme.LOG_BORDER, 1, true));
        getVerticalScrollBar().setUnitIncrement(16);
    }

    /** Appends one line; applies style from content heuristics. */
    public void appendLine(String line) {
        if (line == null) return;
        if (SwingUtilities.isEventDispatchThread()) {
            appendLineEdt(line);
        } else {
            SwingUtilities.invokeLater(() -> appendLineEdt(line));
        }
    }

    private void appendLineEdt(String line) {
        StyledDocument doc = textPane.getStyledDocument();
        LogCategory cat = categorize(line);
        SimpleAttributeSet base = baseAttrs(cat);
        try {
            trimIfNeeded(doc);
            doc.insertString(doc.getLength(), line + "\n", base);
        } catch (BadLocationException ignored) {
        }
        textPane.setCaretPosition(doc.getLength());
    }

    private void trimIfNeeded(StyledDocument doc) throws BadLocationException {
        int len = doc.getLength();
        if (len <= MAX_CHARS) return;
        doc.remove(0, Math.min(len, len - MAX_CHARS + 20_000));
    }

    private static SimpleAttributeSet baseAttrs(LogCategory cat) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setFontFamily(a, DashboardTheme.monoFont(12f).getFamily());
        StyleConstants.setFontSize(a, 12);
        switch (cat) {
            case CRITICAL:
                StyleConstants.setForeground(a, DashboardTheme.LOG_TEXT_FAULT);
                StyleConstants.setBold(a, true);
                break;
            case WARNING:
                StyleConstants.setForeground(a, DashboardTheme.LOG_TEXT_WARN);
                StyleConstants.setBold(a, true);
                break;
            case SUCCESS:
                StyleConstants.setForeground(a, DashboardTheme.LOG_TEXT_OK);
                break;
            case INFO:
                StyleConstants.setForeground(a, DashboardTheme.LOG_TEXT_INFO);
                break;
            case GUI:
                StyleConstants.setForeground(a, DashboardTheme.LOG_TEXT_GUI);
                break;
            default:
                StyleConstants.setForeground(a, DashboardTheme.LOG_TEXT_DEFAULT);
        }
        return a;
    }

    private static LogCategory categorize(String line) {
        String u = line.toUpperCase();
        if (u.contains("[GUI]") && (u.contains("FAULT") || u.contains("ERROR") || u.contains("FAILED"))) {
            return LogCategory.CRITICAL;
        }
        if (u.contains("FAULT")
                || (u.contains("OFFLINE") && u.contains("DRONE"))
                || u.contains("HARD FAULT")
                || u.contains("MARKED AS OFFLINE")) {
            return LogCategory.CRITICAL;
        }
        if (u.contains("UNAVAILABLE")
                || (u.contains("SOFT") && u.contains("FAULT"))
                || u.contains("TIMED OUT")) {
            return LogCategory.WARNING;
        }
        if (u.contains("COMPLETED") || u.contains("CONFIRMED:") || u.contains("IDLE AT BASE")
                || u.contains("SIMULATION COMPLETE")) {
            return LogCategory.SUCCESS;
        }
        if (u.contains("QUEUED") || u.contains("DISPATCHED") || u.contains("DISPATCHING")
                || u.contains("ARRIVED AT ZONE") || u.contains("STARTED (PID")) {
            return LogCategory.INFO;
        }
        if (line.contains("[GUI]") || line.contains("[FIS]")) {
            return LogCategory.GUI;
        }
        return LogCategory.DEFAULT;
    }

    private enum LogCategory {
        DEFAULT, CRITICAL, WARNING, SUCCESS, INFO, GUI
    }

    public void clear() {
        textPane.setText("");
    }
}
