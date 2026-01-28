package app;
import javax.swing.*;
import java.awt.*;

public class SchedulerGUI extends JFrame{

    private JTextArea loggingLoc;
    private JButton loadFileBtn;
    private JLabel systemStatus;

    public SchedulerGUI(){
        setTitle("Firefighting Drone");
        setSize(600, 400);
        setDefaultCloseOperation((JFrame.EXIT_ON_CLOSE));
        setLayout(new BorderLayout());

        JPanel topPanel = new JPanel (new FlowLayout());
        systemStatus = new JLabel ("System Status: IDLE");
        loadFileBtn = new JButton ("Load File");
        topPanel.add(systemStatus);
        topPanel.add(loadFileBtn);
        loggingLoc = new JTextArea();
        loggingLoc.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(loggingLoc);
        add(topPanel, BorderLayout.NORTH);
        add(scrollPane,BorderLayout.CENTER);
    }

    public void logEvent(String message){
        loggingLoc.append(message + "\n");
    }

    public void setSystemStatus(String status){
        systemStatus.setText("System Status: " + status);
    }

}
