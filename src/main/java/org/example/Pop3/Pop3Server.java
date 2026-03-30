package org.example.Pop3;
import java.io.*;
import java.net.*;
import java.util.*;

public class Pop3Server {
    private static final int PORT = 1110; // Custom port to avoid conflicts
    private Pop3ServerGUI gui;
    private ServerSocket serverSocket;
    private volatile boolean keepRunning = true;
    private List<Pop3Session> activeSessions = new ArrayList<>();
    //private List<File> emails;
    //private List<Boolean> deletionFlags = new ArrayList<>();

    public Pop3Server(Pop3ServerGUI gui) {
        this.gui = gui;
    }

   /* public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("POP3 Server started on port " + PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Connection from " + clientSocket.getInetAddress());
                new Pop3Session(clientSocket).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }*/
    public void start() {
        keepRunning = true;
        try {
            serverSocket = new ServerSocket(PORT);
            gui.appendLog("Serveur SMTP prêt sur le port " + PORT);
            
            while (keepRunning) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    // On crée la session
                    Pop3Session session = new Pop3Session(clientSocket, gui, this);
                    activeSessions.add(session);
                    session.start();
                    gui.appendLog("Connexion entrante : " + clientSocket.getInetAddress());
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
            if (serverSocket != null) serverSocket.close();
            // ON FERME TOUS LES CLIENTS ACTIFS
            for (Pop3Session s : activeSessions) s.closeSession();
            activeSessions.clear();
            gui.appendLog("Système -> Serveur POP3 arrêté et clients déconnectés.");
        } catch (IOException e) { 
            gui.appendLog("Erreur lors de la fermeture : " + e.getMessage()); 
        }
    }
    public void removeSession(Pop3Session session) {
        activeSessions.remove(session);
    }

}

class Pop3Session extends Thread {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Pop3ServerGUI gui;
    private String username;
    private File userDir;
    private List<File> emails;
    private boolean authenticated;
    private List<Boolean> deletionFlags; // Déclaration correcte
    private int clientNum;
    private Pop3Server server;


   public Pop3Session(Socket socket, Pop3ServerGUI gui, Pop3Server server) {
        this.socket = socket;
        this.gui = gui; // Initialisation
        this.server = server; // Initialisation
        this.clientNum = gui.getNextClientNumber(); // Récupération du numéro de client
        this.authenticated = false;
    }

    public void closeSession() {
        try { socket.close(); } catch (IOException e) { }
    }

    @Override
    public void run() {
        gui.updateClientCount(true);
        gui.appendLog("ÉVÉNEMENT : Client " + clientNum + " connecté");
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);
            
            sendResponse("+OK POP3 server ready");
            

            String line;
            while ((line = in.readLine()) != null) {
                line = cleanInput(line); // Nettoyage de l'entrée pour gérer les backspaces
                System.out.println("Received: " + line);
                String[] parts = line.split(" ", 2);
                String command = parts[0].toUpperCase();
                String argument = parts.length > 1 ? parts[1] : "";

                switch (command.toUpperCase()) {
                    case "USER":
                        handleUser(argument);
                        break;
                    case "PASS":
                        handlePass(argument);
                        break;
                    case "STAT":
                        handleStat();
                        break;
                    case "LIST":
                        handleList();
                        break;
                    case "RETR":
                        handleRetr(argument);
                        break;
                    case "DELE":
                        handleDele(argument);
                        break;
                    case "RSET":
                        handleRset();
                        break;
                    case "QUIT":
                        handleQuit();
                        return; // Terminer la session
                    default:
                        sendResponse("-ERR Unknown command");
                        out.println("-ERR Unknown command");
                        break;
                }

            }
            // Si la boucle se termine, cela signifie que la connexion a été interrompue sans QUIT.
            if (authenticated) {
                sendResponse("-ERR Connection closed without QUIT");
                System.err.println("La connexion a été interrompue sans recevoir QUIT. Les suppressions marquées ne seront pas appliquées.");
            }
        } catch (IOException e) {
            gui.appendLog("Erreur Session: " + e.getMessage());
            
        } finally {
            gui.updateClientCount(false);
            server.removeSession(this);
            gui.appendLog("ÉVÉNEMENT : Client " + clientNum + " déconnecté");
            closeSession();
        }
    }

    private String cleanInput(String input) {
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (c == '\b' || (int)c == 127) { // Si c'est un Backspace
                if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1);
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private void sendResponse(String msg) {
        gui.appendLog("Serveur (to Client " + clientNum + ") -> " + msg);
        out.println(msg);
    }

    private void handleUser(String arg) {
        File dir = new File("mailserver/" + arg);
        if (dir.exists() && dir.isDirectory()) {
            username = arg;
            userDir = dir;
            sendResponse("+Ok User accepted");
            
        } else {
            sendResponse("-ERR User not found");
            
        }
    }

    private void handlePass(String arg) {
        if (username == null) {
            sendResponse("-ERR USER required first");
            out.println("-ERR USER required first");
            return;
        }
        // Pour simplifier, on suppose que "userDir" est le dossier de l'utilisateur déjà défini
        authenticated = true;
        // Chargez les fichiers du répertoire dans une ArrayList mutable
        File[] files = userDir.listFiles();
        if (files == null) {
            emails = new ArrayList<>();
        } else {
            emails = new ArrayList<>(Arrays.asList(files));
        }
        // Initialisez les flags de suppression : aucun email n'est marqué (false)
        deletionFlags = new ArrayList<>();
        for (int i = 0; i < emails.size(); i++) {
            deletionFlags.add(false);
        }
        sendResponse("+OK Password accepted");
        out.println("+OK Password accepted");
    }



    private void handleStat() {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            return;
        }
        long size = emails.stream().mapToLong(File::length).sum();
        sendResponse("+OK " + emails.size() + " " + size);
        
    }

    private void handleList() {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            
            return;
        }
        sendResponse("+OK " + emails.size() + " messages");
        for (int i = 0; i < emails.size(); i++) {
            sendResponse((i + 1) + " " + emails.get(i).length());
        }
        sendResponse(".");
    }

    private void handleRetr(String arg) {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            
            return;
        }
        try {
            int index = Integer.parseInt(arg) - 1;
            if (index < 0 || index >= emails.size()) {
                sendResponse("-ERR No such message");
                
                return;
            }
            File emailFile = emails.get(index);
            sendResponse("+OK " + emailFile.length() + " octets");
            
            BufferedReader reader = new BufferedReader(new FileReader(emailFile));
            String line;
            while ((line = reader.readLine()) != null) {
                sendResponse(line);
            }
            sendResponse(".");
            reader.close();
        } catch (Exception e) {
            sendResponse("-ERR Invalid message number");
            
        }
    }

    private void handleDele(String arg) {
        if (!authenticated) {
            
            sendResponse("-ERR Authentication required!");
            return;
        }
        try {
            arg = arg.trim();
            int index = Integer.parseInt(arg) - 1; // Les messages sont numérotés à partir de 1
            if (index < 0 || index >= emails.size()) {
                sendResponse("-ERR No such message");
                
                return;
            }
            // Vérifier si le message est déjà marqué pour suppression
            if (deletionFlags.get(index)) {
                sendResponse("-ERR Message already marked for deletion");
                
                return;
            }
            // Marquer le message pour suppression (ne pas le supprimer tout de suite)
            deletionFlags.set(index, true);
            sendResponse("+OK Message marked for deletion");
            
        } catch (NumberFormatException nfe) {
            sendResponse("-ERR Invalid message number");
            
        } catch (Exception e) {
            sendResponse("-ERR Invalid message number");
            
        }
    }
    private void handleRset() {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            
            return;
        }
        // Remise à zéro de tous les flags de suppression
        for (int i = 0; i < deletionFlags.size(); i++) {
            deletionFlags.set(i, false);
        }
        sendResponse("+OK Deletion marks reset");
        
    }




    private void handleQuit() {
        // Pour chaque email marqué pour suppression, supprimez le fichier
        for (int i = deletionFlags.size() - 1; i >= 0; i--) {
            if (deletionFlags.get(i)) {
                File emailFile = emails.get(i);
                if (emailFile.delete()) {
                    gui.appendLog("Deleted email: " + emailFile.getAbsolutePath());
                    // Optionnel : vous pouvez retirer l'email de la liste
                    emails.remove(i);
                    deletionFlags.remove(i);
                } else {
                    sendResponse("-ERR Failed to delete email");
                    gui.appendLog("Failed to delete email: " + emailFile.getAbsolutePath());
                }
            }
        }
        sendResponse("+OK POP3 server signing off");
        gui.appendLog("POP3 server signing off");
    }

}

