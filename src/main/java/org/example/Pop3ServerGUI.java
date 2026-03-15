package org.example;

import javax.swing.*;
import java.awt.*;

public class Pop3ServerGUI extends JFrame {
    private JTextArea logArea;
    private JButton startBtn, stopBtn;
    private Pop3Server server;

    public Pop3ServerGUI() {
        setTitle("Supervision Serveur POP3");
        setSize(700, 500);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(new Color(30, 30, 30));
        logArea.setForeground(Color.WHITE);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));

        startBtn = new JButton("Démarrer Serveur");
        stopBtn = new JButton("Arrêter Serveur");
        stopBtn.setEnabled(false);

        JPanel panel = new JPanel();
        panel.add(startBtn);
        panel.add(stopBtn);

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
        SwingUtilities.invokeLater(() -> logArea.append(msg + "\n"));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Pop3ServerGUI().setVisible(true));
    }
}