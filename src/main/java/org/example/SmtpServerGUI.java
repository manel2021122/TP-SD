package org.example;

import javax.swing.*;
import java.awt.*;

public class SmtpServerGUI extends JFrame {
    private JTextArea logArea;
    private JButton startBtn, stopBtn;
    private SmtpServer server;

    public SmtpServerGUI() {
        setTitle("Supervision Serveur SMTP");
        setSize(700, 500);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(new Color(25, 25, 25));
        logArea.setForeground(new Color(200, 200, 100)); // Jaune pour SMTP
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
            server = new SmtpServer(this);
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
        SwingUtilities.invokeLater(() -> new SmtpServerGUI().setVisible(true));
    }
}