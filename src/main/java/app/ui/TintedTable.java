package app.ui;

import app.theme.DashboardTheme;

import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableModel;
import java.awt.*;

/**
 * Zebra striping plus optional fault-row tint when a state column indicates offline / fault.
 */
public final class TintedTable extends JTable {

    private final int stateColumnIndex;

    /**
     * @param stateColumnIndex column index used for fault detection, or {@code -1} for zebra only
     */
    public TintedTable(TableModel model, int stateColumnIndex) {
        super(model);
        this.stateColumnIndex = stateColumnIndex;
        setOpaque(true);
    }

    private static boolean isFaultState(String s) {
        if (s == null) return false;
        String u = s.toUpperCase();
        return "OFFLINE".equals(u) || "UNAVAILABLE".equals(u) || "FAULTED".equals(u);
    }

    @Override
    public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
        Component c = super.prepareRenderer(renderer, row, column);
        if (isRowSelected(row)) {
            return c;
        }
        boolean fault = false;
        if (stateColumnIndex >= 0 && stateColumnIndex < getColumnCount()) {
            Object st = getValueAt(row, stateColumnIndex);
            fault = isFaultState(String.valueOf(st));
        }
        if (fault && stateColumnIndex >= 0 && column == stateColumnIndex) {
            return c;
        }
        Color bg = fault ? DashboardTheme.TABLE_ROW_FAULT
                : (row % 2 == 0 ? Color.WHITE : DashboardTheme.TABLE_ROW_ALT);
        c.setBackground(bg);
        if (c instanceof JComponent) {
            ((JComponent) c).setOpaque(true);
        }
        return c;
    }
}
