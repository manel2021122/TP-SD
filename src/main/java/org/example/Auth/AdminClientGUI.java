package org.example.Auth;


import org.example.SharedFolder.IAuthService;

import javax.swing.*;
import java.awt.*;
import java.rmi.Naming;

public class AdminClientGUI extends JFrame {

    private JTextField userField;
    private JTextField passField;
    private JTextArea logArea;
    private IAuthService authService;

    public AdminClientGUI() {
        // --- 1. Configuration de la fenêtre principale ---
        setTitle("Administration des Comptes (RMI)");
        setSize(450, 350);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // --- 2. Création des champs de saisie ---
        JPanel inputPanel = new JPanel(new GridLayout(2, 2, 10, 10));
        inputPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        inputPanel.add(new JLabel("Nom d'utilisateur :"));
        userField = new JTextField();
        inputPanel.add(userField);

        inputPanel.add(new JLabel("Mot de passe :"));
        passField = new JTextField();
        inputPanel.add(passField);

        add(inputPanel, BorderLayout.NORTH);

        // --- 3. Création de la zone de logs ---
        logArea = new JTextArea();
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);
        add(scrollPane, BorderLayout.CENTER);

        // --- 4. Création des boutons ---
        JPanel buttonPanel = new JPanel(new FlowLayout());

        JButton btnAdd = new JButton("Ajouter");
        JButton btnUpdate = new JButton("Modifier");
        JButton btnDelete = new JButton("Supprimer");

        buttonPanel.add(btnAdd);
        buttonPanel.add(btnUpdate);
        buttonPanel.add(btnDelete);

        add(buttonPanel, BorderLayout.SOUTH);

        // --- 5. Connexion initiale au serveur RMI ---
        connectToRMI();

        // --- 6. Actions des boutons ---
        btnAdd.addActionListener(e -> {
            String user = userField.getText().trim();
            String pass = passField.getText().trim();
            if (!user.isEmpty() && !pass.isEmpty()) {
                try {
                    boolean success = authService.createUser(user, pass);
                    log(success ? "Succès : Utilisateur '" + user + "' ajouté."
                            : "Erreur : L'utilisateur '" + user + "' existe déjà.");
                } catch (Exception ex) {
                    log("Erreur RMI (Ajout) : " + ex.getMessage());
                }
            } else {
                log("Avertissement : Remplissez tous les champs.");
            }
        });

        btnUpdate.addActionListener(e -> {
            String user = userField.getText().trim();
            String pass = passField.getText().trim();
            if (!user.isEmpty() && !pass.isEmpty()) {
                try {
                    boolean success = authService.updateUser(user, pass);
                    log(success ? "Succès : Mot de passe de '" + user + "' mis à jour."
                            : "Erreur : L'utilisateur '" + user + "' n'existe pas.");
                } catch (Exception ex) {
                    log("Erreur RMI (Modification) : " + ex.getMessage());
                }
            } else {
                log("Avertissement : Remplissez tous les champs.");
            }
        });

        btnDelete.addActionListener(e -> {
            String user = userField.getText().trim();
            if (!user.isEmpty()) {
                try {
                    boolean success = authService.deleteUser(user);
                    log(success ? "Succès : Utilisateur '" + user + "' supprimé."
                            : "Erreur : L'utilisateur '" + user + "' n'existe pas.");
                } catch (Exception ex) {
                    log("Erreur RMI (Suppression) : " + ex.getMessage());
                }
            } else {
                log("Avertissement : Saisissez le nom d'utilisateur à supprimer.");
            }
        });
    }

    // Méthode pour se connecter à l'annuaire RMI
    private void connectToRMI() {
        try {
            authService = (IAuthService) Naming.lookup("rmi://localhost/AuthService");
            log("Connecté au serveur d'authentification RMI.");
        } catch (Exception e) {
            log("Échec de connexion au serveur RMI. Assurez-vous qu'il est lancé.");
            log("Détails : " + e.getMessage());
        }
    }

    // Méthode utilitaire pour écrire dans la zone de texte
    private void log(String message) {
        logArea.append(message + "\n");
        // Scroll automatique vers le bas
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    public static void main(String[] args) {
        // Lancement de l'interface graphique proprement
        SwingUtilities.invokeLater(() -> {
            new AdminClientGUI().setVisible(true);
        });
    }
}
