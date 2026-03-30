package org.example.Smtp;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

import org.example.SharedFolder.IAuthService;

public class SmtpServer {
    // Use a custom port (e.g., 2525) to avoid needing special privileges.
    private static final int PORT = 2525;
    private SmtpServerGUI gui;
    private ServerSocket serverSocket;
    private volatile boolean keepRunning = true;
    private List<SmtpSession> activeSessions = new ArrayList<>();

    public SmtpServer(SmtpServerGUI gui) {
        this.gui = gui;
    }

    /*public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("SMTP Server started on port " + PORT);
            // Continuously accept new client connections
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Connection from " + clientSocket.getInetAddress());
                // Handle each connection in its own thread
                new SmtpSession(clientSocket).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }*/
   public void start() {
        keepRunning = true;
        try {
            // CORRECTION : On initialise la variable de classe sans "ServerSocket" devant
            // On n'utilise pas le try-with-resources ici car il ferme le socket trop tôt
            serverSocket = new ServerSocket(PORT);
            gui.appendLog("Serveur SMTP prêt sur le port " + PORT);

            while (keepRunning) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    gui.appendLog("Connexion entrante : " + clientSocket.getInetAddress());
                    SmtpSession session = new SmtpSession(clientSocket, gui, this); // On passe 'this'
                    activeSessions.add(session);
                    session.start();
                } catch (IOException e) {
                    if (keepRunning) {
                        gui.appendLog("Erreur acceptation : " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            gui.appendLog("Erreur critique : " + e.getMessage());
        } finally {
            stop(); // Assure la fermeture si on sort de la boucle
        }
    }

    public void stop() {
        keepRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                for (SmtpSession session : activeSessions) {
                session.closeSession();
                }
                serverSocket.close(); // Libère le port 2525 immédiatement
                gui.appendLog("Système -> Serveur SMTP arrêté.");
            }
        } catch (IOException e) { 
            gui.appendLog("Erreur lors de la fermeture : " + e.getMessage()); 
        }
    }
}

class SmtpSession extends Thread {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private SmtpServerGUI gui;
    private SmtpServer server;
    private org.example.SharedFolder.IAuthService authService;

    private enum SmtpState { CONNECTED, HELO_RECEIVED, AUTHENTICATED, MAIL_FROM_SET, RCPT_TO_SET, DATA_RECEIVING }

    private SmtpState state;
    private String sender;
    private List<String> recipients;
    private StringBuilder dataBuffer;
    private int clientNum;
    private String sessionToken = null;
    private String authenticatedUser = null;

    public SmtpSession(Socket socket, SmtpServerGUI gui2, SmtpServer server) {
        this.socket = socket;
        this.gui = gui2;
        this.server = server;
        this.clientNum = gui.getNextClientNumber();
        this.state = SmtpState.CONNECTED;
        this.recipients = new ArrayList<>();
        this.dataBuffer = new StringBuilder();

        try {
            authService = (org.example.SharedFolder.IAuthService) java.rmi.Naming.lookup("rmi://localhost/AuthService");
        } catch (Exception e) {
            gui.appendLog("SMTP ERREUR RMI : AuthService introuvable.");
        }
    }

    public void closeSession() {
        try { if (socket != null) socket.close(); } catch (IOException e) { }
    }

    @Override
    public void run() {
        gui.updateClientCount(true);
        gui.appendLog("ÉVÉNEMENT : Client " + clientNum + " connecté");
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            sendResponse("220 smtp.example.com Service Ready");

            String line;
            while ((line = in.readLine()) != null) {
                line = cleanInput(line);

                if (state == SmtpState.DATA_RECEIVING) {
                    if (line.equals(".")) {
                        storeEmail(dataBuffer.toString());
                        dataBuffer.setLength(0);
                        state = SmtpState.AUTHENTICATED;
                        sendResponse("250 OK: Message accepted");
                    } else {
                        dataBuffer.append(line).append("\r\n");
                    }
                    continue;
                }

                String command = extractToken(line).toUpperCase();
                String argument = extractArgument(line);

                switch (command) {
                    case "HELO": case "EHLO": handleHelo(argument); break;
                    case "AUTH": handleAuth(argument); break;
                    case "MAIL": handleMailFrom(argument); break;
                    case "RCPT": handleRcptTo(argument); break;
                    case "DATA": handleData(); break;
                    case "QUIT": handleQuit(); return;
                    default: sendResponse("500 Command unrecognized"); break;
                }
            }
        } catch (IOException e) {
            gui.appendLog("Erreur session " + clientNum + ": " + e.getMessage());
        } finally {
            gui.updateClientCount(false);
            closeSession();
        }
    }

    private void handleAuth(String arg) {
        if (!arg.toUpperCase().startsWith("LOGIN")) {
            sendResponse("504 Unrecognized authentication type");
            return;
        }
        try {
            sendResponse("334 Username"); 
            String user = in.readLine();

            sendResponse("334 Password"); 
            String pass = in.readLine();

            if (authService != null) {
                // APPEL RMI : Récupération du Token
                String token = authService.loginAndGetToken(user, pass);

                if (token != null) {
                    this.sessionToken = token;
                    this.authenticatedUser = user;
                    this.state = SmtpState.AUTHENTICATED;
                    sendResponse("235 Authentication successful. Token assigned.");
                } else {
                    sendResponse("535 Authentication credentials invalid");
                }
            }
        } catch (Exception e) {
            sendResponse("454 Temporary authentication failure");
            gui.appendLog("Erreur RMI SMTP : " + e.getMessage());
        }
    }

    private boolean isTokenValid() {
        try {
            if (sessionToken == null || authService == null) return false;
            String user = authService.verifyToken(sessionToken);
            return user != null && user.equals(authenticatedUser);
        } catch (Exception e) {
            return false;
        }
    }

    private void handleMailFrom(String arg) {
        if (!isTokenValid()) {
            sendResponse("530 Authentication required");
            this.state = SmtpState.CONNECTED; // Reset l'état si le token est invalide
            return;
        }
        // Validation basique du format FROM:<email>
        sender = extractEmail(arg.replace("FROM:", ""));
        state = SmtpState.MAIL_FROM_SET;
        sendResponse("250 OK");
    }

    private void handleRcptTo(String arg) {
        if (state != SmtpState.MAIL_FROM_SET && state != SmtpState.RCPT_TO_SET) {
            sendResponse("503 Bad sequence");
            return;
        }
        String email = extractEmail(arg.replace("TO:", ""));
        if (email != null) {
            recipients.add(email);
            state = SmtpState.RCPT_TO_SET;
            sendResponse("250 OK");
        } else {
            sendResponse("501 Syntax error");
        }
    }

    private void handleData() {
        if (state != SmtpState.RCPT_TO_SET) {
            sendResponse("503 Bad sequence");
            return;
        }
        state = SmtpState.DATA_RECEIVING;
        sendResponse("354 Start mail input; end with <CRLF>.<CRLF>");
    }

    private void handleHelo(String arg) {
        // 1. On réinitialise les données du message en cours
        sender = "";
        recipients.clear();
        dataBuffer.setLength(0);
        
        // 2. On change l'état
        // Si l'utilisateur a déjà un jeton valide, on reste en état AUTHENTICATED
        // Sinon, on passe en HELO_RECEIVED
        if (isTokenValid()) {
            state = SmtpState.AUTHENTICATED;
            sendResponse("250-Hello " + arg + ", pleased to meet you");
            sendResponse("250-AUTH LOGIN");
            sendResponse("250 OK");
        } else {
            state = SmtpState.HELO_RECEIVED;
            sessionToken = null; // Sécurité : on nettoie le jeton si invalide
            sendResponse("250 Hello " + arg + ", pleased to meet you");
        }

        gui.appendLog("SMTP -> HELO reçu de " + arg + " (Session Reset)");
    }

    private void handleQuit() { try {
        // 1. Notification au client
        sendResponse("221 2.0.0 smtp.example.com Service closing transmission");
        
        // 2. Nettoyage local de la session
        gui.appendLog("Client " + clientNum + " a quitté (QUIT).");
        
        // 3. Optionnel : Invalidation du token
        // Si tu veux que le jeton ne soit plus utilisable après déconnexion
        this.sessionToken = null;

        } catch (Exception e) {
            gui.appendLog("Erreur lors du QUIT : " + e.getMessage());
        } finally {
            // 4. Fermeture physique du socket via la méthode du serveur
            closeSession();
        } 
    }

    private void sendResponse(String msg) {
        gui.appendLog("Serveur -> " + msg);
        out.println(msg);
    }

    private String extractToken(String line) { return line.split(" ")[0]; }
    private String extractArgument(String line) {
        int idx = line.indexOf(' ');
        return idx > 0 ? line.substring(idx).trim() : "";
    }
    private String extractEmail(String input) {
        input = input.replaceAll("[<>]", "").trim();
        return (input.contains("@")) ? input : null;
    }

    private void storeEmail(String data) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        for (String recipient : recipients) {
            String user = recipient.split("@")[0];
            File userDir = new File("mailserver/" + user);
            if (!userDir.exists()) userDir.mkdirs();

            try (PrintWriter writer = new PrintWriter(new FileWriter(new File(userDir, timestamp + ".txt")))) {
                writer.println("From: " + sender);
                writer.println("X-Auth-Token: " + sessionToken); // Preuve du jeton
                writer.println("Subject: SMTP RMI Delivery");
                writer.println();
                writer.print(data);
                gui.appendLog("Système -> Email stocké avec Token pour " + recipient);
            } catch (IOException e) { 
                gui.appendLog("Erreur stockage: " + e.getMessage()); 
            }
        }
    }

    private String cleanInput(String input) {
        if (input == null) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (c == '\b' || (int)c == 127) {
                if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1);
            } else { sb.append(c); }
        }
        return sb.toString();
    }
}
