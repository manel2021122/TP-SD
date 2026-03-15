package org.example;

import javax.swing.*;
import java.awt.*;

public class ImapServerGUI extends JFrame {
    private JTextArea logArea;
    private JButton startBtn, stopBtn;
    private ImapServerMultiplexed server;

    public ImapServerGUI() {
        setTitle("Supervision IMAP");
        setSize(700, 500);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(Color.BLACK);
        logArea.setForeground(Color.CYAN);
        
        startBtn = new JButton("Démarrer");
        stopBtn = new JButton("Arrêter");
        stopBtn.setEnabled(false);

        JPanel panel = new JPanel();
        panel.add(startBtn);
        panel.add(stopBtn);

        add(panel, BorderLayout.NORTH);
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        startBtn.addActionListener(e -> {
            server = new ImapServerMultiplexed(this);
            new Thread(() -> server.start()).start();
            startBtn.setEnabled(false);
            stopBtn.setEnabled(true);
        });

        stopBtn.addActionListener(e -> {
            if (server != null) {
                server.stop();
                startBtn.setEnabled(true);
                stopBtn.setEnabled(false);
            }
        });
    }

    public void appendLog(String msg) {
        SwingUtilities.invokeLater(() -> logArea.append(msg + "\n"));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ImapServerGUI().setVisible(true));
    }
}