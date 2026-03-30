package org.example.Smtp;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

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
    private String clientId;
    private SmtpServer server;

    // Finite state machine for the SMTP session
    private enum SmtpState {
        CONNECTED,    // Connection established; waiting for HELO/EHLO.
        HELO_RECEIVED, // HELO/EHLO received; ready for MAIL FROM.
        MAIL_FROM_SET, // MAIL FROM command processed; ready for RCPT TO.
        RCPT_TO_SET,   // At least one RCPT TO received; ready for DATA.
        DATA_RECEIVING // DATA command received; reading email content.
    }

    private SmtpState state;
    private String sender;
    private List<String> recipients;
    private StringBuilder dataBuffer;
    private int clientNum;

    public SmtpSession(Socket socket, SmtpServerGUI gui2, SmtpServer server) {
        this.socket = socket;
        this.gui = gui2;
        this.server = server;
        this.clientNum = gui.getNextClientNumber(); // Get a unique client number for logging
        this.state = SmtpState.CONNECTED;
        this.recipients = new ArrayList<>();
        this.dataBuffer = new StringBuilder();
    }

    public void closeSession() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close(); // C'est CA qui coupera la connexion du client Telnet
            }
        } catch (IOException e) { 
            gui.appendLog("Erreur fermeture session : " + e.getMessage()); 
        }
    }

    @Override
    public void run() {
        gui.updateClientCount(true); // +1 connexion
        gui.appendLog("ÉVÉNEMENT : Client " + clientNum + " - Nouvelle session ouverte pour " + socket.getInetAddress());
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            sendResponse("220 smtp.example.com Service Ready");

            String line;
            while ((line = in.readLine()) != null) {
                line = cleanInput(line);
                if (state != SmtpState.DATA_RECEIVING) {
                    gui.appendLog("Client " + clientNum + " -> " + line); // Log commande
                }

                if (state == SmtpState.DATA_RECEIVING) {
                    if (line.equals(".")) {
                        storeEmail(dataBuffer.toString());
                        dataBuffer.setLength(0);
                        state = SmtpState.HELO_RECEIVED;
                        sendResponse("250 OK: Message accepted for delivery");
                    } else {
                        dataBuffer.append(line).append("\r\n");
                    }
                    continue;
                }

                String command = extractToken(line).toUpperCase();
                String argument = extractArgument(line);

                switch (command) {
                    case "HELO": case "EHLO": handleHelo(argument); break;
                    case "MAIL": handleMailFrom(argument); break;
                    case "RCPT": handleRcptTo(argument); break;
                    case "DATA": handleData(); break;
                    case "QUIT": handleQuit(); return;
                    default: sendResponse("500 Command unrecognized"); break;
                }
            }
        } catch (IOException e) {
            gui.appendLog("Erreur session : " + e.getMessage());
        } finally {
            gui.updateClientCount(false); // -1 déconnexion
            gui.appendLog("ÉVÉNEMENT : Session terminée pour " + socket.getInetAddress());
            try { socket.close(); } catch (IOException e) { /* ignore */ }
        }
    }

    // Clean input by trimming and removing control characters.
    private String cleanInput(String input) {
        if (input == null) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            // Gère Backspace (8) et Delete (127)
            if (c == '\b' || (int)c == 127) {
                if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1);
            } else {
                sb.append(c);
            }
        }
        return sb.toString(); // On évite le .trim() global pour préserver les espaces DATA
    }

    private void sendResponse(String msg) {
        gui.appendLog("Serveur -> " + msg); // Log réponse
        if (out != null) {
            out.println(msg); // Indispensable pour que le client reçoive la réponse !
        }
    }

    private void handleHelo(String arg) {
        // Reset any previous session data
        state = SmtpState.HELO_RECEIVED;
        sender = "";
        recipients.clear();
        sendResponse("250 Hello " + arg); 
    }

    private void handleMailFrom(String arg) {
        // Vérifier que l'argument correspond exactement au format "FROM:<email>"
        // L'expression régulière vérifie que la chaîne commence par "FROM:", suivie de zéro ou plusieurs espaces,
        // puis d'une adresse email entre chevrons et rien d'autre.
        if (!arg.toUpperCase().matches("^FROM:\\s*<[^>]+>$")) {
            sendResponse("501 Syntax error in parameters or arguments");
            return;
        }
        // Extraire l'adresse email en retirant "FROM:" et les chevrons.
        String potentialEmail = arg.substring(5).trim();  // Extrait ce qui suit "FROM:"
        // Retirer les chevrons (< et >)
        potentialEmail = potentialEmail.substring(1, potentialEmail.length() - 1).trim();

        String email = extractEmail(potentialEmail);
        if (email == null) {
            sendResponse("501 Syntax error in parameters or arguments");
            return;
        }
        sender = email;
        state = SmtpState.MAIL_FROM_SET;
        sendResponse("250 OK");
    }

    private void handleRcptTo(String arg) {
        if (state != SmtpState.MAIL_FROM_SET && state != SmtpState.RCPT_TO_SET) {
            sendResponse("503 Bad sequence of commands");
            return;
        }
        if (!arg.toUpperCase().startsWith("TO:")) {
            sendResponse("501 Syntax error in parameters or arguments");
            return;
        }
        String potentialEmail = arg.substring(3).trim();
        String email = extractEmail(potentialEmail);
        if (email == null) {
            sendResponse("501 Syntax error in parameters or arguments");
            return;
        }

        // Check if the recipient's directory exists.
        // The user directory is assumed to be "mailserver/username" where username is the part before '@'.
        String username = email.split("@")[0];
        File userDir = new File(System.getProperty("user.dir") + "/mailserver/" + username);
        if (!userDir.exists()) {
            boolean created = userDir.mkdirs();  // Create user directory
            if (!created) {
                sendResponse("550 Failed to create user directory");
                return;
            }
        }


        recipients.add(email);
        state = SmtpState.RCPT_TO_SET;
        sendResponse("250 OK");
    }

    private void handleData() {
        if (state != SmtpState.RCPT_TO_SET || recipients.isEmpty()) {
            sendResponse("503 Bad sequence of commands");
            return;
        }
        state = SmtpState.DATA_RECEIVING;
        sendResponse("354 Start mail input; end with <CRLF>.<CRLF>");
    }

    private void handleQuit() {
        sendResponse("221 smtp.example.com Service closing transmission channel");
    }

    // Helper to extract the first token (command) from the input line.
    private String extractToken(String line) {
        String[] parts = line.split(" ");
        return parts.length > 0 ? parts[0] : "";
    }

    // Helper to extract the argument portion (everything after the command).
    private String extractArgument(String line) {
        int index = line.indexOf(' ');
        return index > 0 ? line.substring(index).trim() : "";
    }

    // Simple email extraction: removes angle brackets and performs a basic validation.
    private String extractEmail(String input) {
        // Remove any surrounding angle brackets.
        input = input.replaceAll("[<>]", "");
        if (input.contains("@") && input.indexOf("@") > 0 && input.indexOf("@") < input.length() - 1) {
            return input;
        }
        return null;
    }

    // Store the email for each recipient in the corresponding user directory.
    // Files are named using the current timestamp.

    private void storeEmail(String data) {
        // Use a readable timestamp format (YYYYMMDD_HHMMSS)
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        for (String recipient : recipients) {
            // Extract username (before @)
            String username = recipient.split("@")[0];

            // Define user directory path
            File userDir = new File("mailserver/" + username);

            // Ensure the directory exists
            if (!userDir.exists()) {
                userDir.mkdirs();  // Create if missing
            }

            // Define email file path
            File emailFile = new File(userDir, timestamp + ".txt");

            // Write email content
            try (PrintWriter writer = new PrintWriter(new FileWriter(emailFile))) {
                // Basic email headers (RFC 5322)
                writer.println("From: " + sender);
                writer.println("To: " + String.join(", ", recipients));
                writer.println("Date: " + new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z").format(new Date()));
                writer.println("Subject: Test Email");
                writer.println();
                writer.print(data);

                // Log success
                gui.appendLog("Système -> Email stocké pour " + recipient);
                System.out.println(" Stored email for " + recipient + " in " + emailFile.getAbsolutePath());
            } catch (IOException e) {
                gui.appendLog("Erreur stockage -> " + e.getMessage());
                System.err.println(" Error storing email: " + e.getMessage());
            }
        }
    }
}

