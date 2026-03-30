package org.example.Pop3;

import javax.swing.*;
import java.awt.*;

public class Pop3ServerGUI extends JFrame {
    private JTextArea logArea;
    private JButton startBtn, stopBtn;
    private Pop3Server server;
    private JLabel clientCountLabel; // Label pour afficher le nombre de clients connectés
    // Bonus : Compteurs pour les identifiants et le nombre de connectés
    private java.util.concurrent.atomic.AtomicInteger totalClientsCreated = new java.util.concurrent.atomic.AtomicInteger(0);
    private java.util.concurrent.atomic.AtomicInteger connectedClients = new java.util.concurrent.atomic.AtomicInteger(0);

    public Pop3ServerGUI() {
        setTitle("Supervision Serveur POP3");
        setSize(700, 500);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        clientCountLabel = new JLabel("Clients connectés : 0");

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        startBtn = new JButton("Démarrer Serveur");
        stopBtn = new JButton("Arrêter Serveur");
        stopBtn.setEnabled(false);
        panel.add(startBtn);
        panel.add(stopBtn);
        panel.add(clientCountLabel); // Ajout du label en haut

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(new Color(30, 30, 30));
        logArea.setForeground(Color.WHITE);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));

        add(panel, BorderLayout.NORTH);
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        startBtn.addActionListener(e -> {
            server = new Pop3Server(this); // On passe la GUI au serveur
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
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + timestamp + "] " + msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public void updateClientCount(boolean increment) {
        int count = increment ? connectedClients.incrementAndGet() : connectedClients.decrementAndGet();
        if (count < 0) count = connectedClients.getAndSet(0); 
    
        final int finalCount = count;
        SwingUtilities.invokeLater(() -> clientCountLabel.setText("Clients connectés : " + finalCount));
    }

    public int getNextClientNumber() {
        return totalClientsCreated.incrementAndGet();
    }
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Pop3ServerGUI().setVisible(true));
    }
}