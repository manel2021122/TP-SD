package org.example.Smtp;

import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

public class SmtpServerGUI extends JFrame {
    private JTextArea logArea;
    private JButton startBtn, stopBtn;
    private JLabel clientCountLabel; // Bonus : Affichage du nombre de clients
    private SmtpServer server;
    private AtomicInteger connectedClients = new AtomicInteger(0); // Bonus : Compteur thread-safe
    private AtomicInteger totalClientsCreated = new AtomicInteger(0); // Bonus : Total clients créés depuis le démarrage

    public SmtpServerGUI() {
        setTitle("Supervision Serveur SMTP");
        setSize(700, 500);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // Configuration de la zone de log
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(new Color(25, 25, 25));
        logArea.setForeground(new Color(200, 200, 100)); 
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));

        // Composants de contrôle
        startBtn = new JButton("Démarrer Serveur");
        stopBtn = new JButton("Arrêter Serveur");
        stopBtn.setEnabled(false);
        clientCountLabel = new JLabel("Clients connectés : 0");
        clientCountLabel.setBorder(BorderFactory.createEmptyBorder(0, 15, 0, 0));

        // Panneau supérieur
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(startBtn);
        topPanel.add(stopBtn);
        topPanel.add(clientCountLabel);

        add(topPanel, BorderLayout.NORTH);
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        // Actions
        startBtn.addActionListener(e -> {
            server = new SmtpServer(this);
            new Thread(() -> server.start()).start();
            startBtn.setEnabled(false);
            stopBtn.setEnabled(true);
        });

        stopBtn.addActionListener(e -> {
            if (server != null) {
                server.stop();
                resetUI();
            }
        });
    }

    /**
     * Bonus : Horodatage automatique et Scroll
     */
    public void appendLog(String msg) {
        String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + timestamp + "] " + msg + "\n");
            // Auto-scroll vers le bas
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /**
     * Bonus : Gestion du compteur de clients
     */
    public void updateClientCount(boolean increment) {
        int count = increment ? connectedClients.incrementAndGet() : connectedClients.decrementAndGet();
        if (count < 0){ count = 0; connectedClients.set(0);}
        
        final int finalCount = count;
        SwingUtilities.invokeLater(() -> clientCountLabel.setText("Clients connectés : " + finalCount));
    }

    public int getNextClientNumber() {
    return totalClientsCreated.incrementAndGet();
}

    private void resetUI() {
        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);
        connectedClients.set(0);
        clientCountLabel.setText("Clients connectés : 0");
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new SmtpServerGUI().setVisible(true));
    }
}