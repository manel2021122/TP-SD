package org.example;

import javax.swing.*;
import java.awt.*;

public class ImapServerGUI extends JFrame {
    private JTextArea logArea;
    private JButton startBtn, stopBtn;
    private ImapServerMultiplexed server;
    private java.util.concurrent.atomic.AtomicInteger totalClientsCreated = new java.util.concurrent.atomic.AtomicInteger(0);
    private java.util.concurrent.atomic.AtomicInteger connectedClients = new java.util.concurrent.atomic.AtomicInteger(0);
    private JLabel clientCountLabel; // Label pour afficher le nombre de clients connectés

    public ImapServerGUI() {
        setTitle("Supervision IMAP");
        setSize(700, 500);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        clientCountLabel = new JLabel("Clients connectés : 0");
        
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
        panel.add(clientCountLabel); 

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

    public int getNextClientNumber() {
        return totalClientsCreated.incrementAndGet();
    }

    public void updateClientCount(boolean increment) {
        // Met à jour ton JLabel si tu en as un (comme pour POP3)
        int count = increment ? connectedClients.incrementAndGet() : connectedClients.decrementAndGet();
        if (count < 0) count = connectedClients.getAndSet(0); // Sécurité
        final int finalCount = count;
        SwingUtilities.invokeLater(() -> clientCountLabel.setText("Clients connectés : " + finalCount));
    }
    

    public void appendLog(String msg) {
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + timestamp + "] " + msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ImapServerGUI().setVisible(true));
    }
}