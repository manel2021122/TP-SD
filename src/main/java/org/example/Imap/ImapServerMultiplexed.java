package org.example.Imap;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.net.*;
import java.util.concurrent.ConcurrentHashMap;

public class ImapServerMultiplexed {
    private static final int PORT = 1143;
    private Selector selector;
    private ImapServerGUI gui;
    private ServerSocketChannel serverChannel;
    private volatile boolean active = true; // Variable pour contrôler l'arrêt du serveur
    private Map<SocketChannel, ImapSessionMultiplexed> sessions = new ConcurrentHashMap<>();
    
    public ImapServerMultiplexed(ImapServerGUI gui) { // Constructeur modifié
        this.gui = gui;
    }
    
    public static void main(String[] args) {
        new ImapServerMultiplexed(new ImapServerGUI()).start();
    }
    
    public void start() {
        try {
            // Ouvrir le selector
            selector = Selector.open();
            
            // Créer le channel serveur
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.socket().bind(new InetSocketAddress(PORT));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            
            gui.appendLog("Serveur IMAP démarré sur le port " + PORT); // Log GUI
            
            while (active) {
                // Attendre qu'un événement se produise (bloquant)
               if( selector.select() == 0 ) continue;
                
                // Récupérer les clés des canaux prêts
                Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
                
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();
                    
                    if (!key.isValid()) {
                        continue;
                    }
                    
                    try {
                        if (key.isAcceptable()) {
                            // Nouvelle connexion entrante
                            acceptConnection(key);
                        } else if (key.isReadable()) {
                            // Données disponibles à lire
                            readFromClient(key);
                        }
                    } catch (IOException e) {
                        System.err.println("Erreur sur la connexion: " + e.getMessage());
                        key.cancel();
                        try {
                            key.channel().close();
                        } catch (IOException ex) {
                            // Ignorer
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public void stop() {
        active = false;
        try {
            if (selector != null) {
                selector.wakeup();

                // 1. Fermer toutes les sessions clients actives
                for (ImapSessionMultiplexed session : sessions.values()) {
                    try {
                        session.closeSession(); // Ferme le canal et décrémente la GUI
                    } catch (IOException e) { /* Ignorer */ }
                }
                sessions.clear(); // Vide la liste

                selector.close();
            }

            // 2. Fermer le canal serveur principal
            if (serverChannel != null) {
                serverChannel.close();
            }

            gui.appendLog("SYSTÈME : Serveur totalement arrêté et clients déconnectés.");
        } catch (IOException e) {
            gui.appendLog("Erreur lors de l'arrêt : " + e.getMessage());
        }
    }   

    public void removeSession(SocketChannel channel) {
        sessions.remove(channel);
    }

    private void acceptConnection(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept();
        clientChannel.configureBlocking(false);
        
        // Enregistrer le canal client pour la lecture
        clientChannel.register(selector, SelectionKey.OP_READ);
        
        // Créer une session pour ce client
        ImapSessionMultiplexed session = new ImapSessionMultiplexed(clientChannel, selector, gui, this);
        sessions.put(clientChannel, session);
        
        // Envoyer le message de bienvenue
        gui.updateClientCount(true);
        int num = session.getClientNum(); // Numéro de client unique
        gui.appendLog("ÉVÉNEMENT : Client " + num + " connecté (" + clientChannel.getRemoteAddress() + ")");
        session.sendGreeting();
    }
    
    private void readFromClient(SelectionKey key) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ImapSessionMultiplexed session = sessions.get(clientChannel);
        
        try {
            session.read();
        } catch (IOException e) {
            // Si une erreur survient (client qui coupe brutalement)
            if (session != null) session.closeSession();
            sessions.remove(clientChannel); // On nettoie la Map ici !
            key.cancel();
        }
    }
}

enum ImapState {
    NOT_AUTHENTICATED,
    AUTHENTICATED,
    SELECTED,
    LOGOUT
}

class Message {
    private int uid;
    private File file;
    private boolean seen;  // Flag \Seen
    private boolean deleted; // Flag \Deleted
    
    public Message(int uid, File file) {
        this.uid = uid;
        this.file = file;
        this.seen = false;
        this.deleted = false;
    }
    
    public int getUid() { return uid; }
    public File getFile() { return file; }
    public boolean isSeen() { return seen; }
    public void setSeen(boolean seen) { this.seen = seen; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    
    public String getFlags() {
        StringBuilder flags = new StringBuilder();
        if (seen) flags.append("\\Seen ");
        if (deleted) flags.append("\\Deleted ");
        return flags.toString().trim();
    }
}

class Mailbox {
    private String name;
    private List<Message> messages;
    private Map<Integer, Integer> uidMap;
    private int nextUid;
    
    public Mailbox(String name, File directory) {
        this.name = name;
        this.messages = new ArrayList<>();
        this.uidMap = new HashMap<>();
        this.nextUid = 1;
        loadMessages(directory);
    }
    
    private void loadMessages(File directory) {
        File[] files = directory.listFiles((dir, name) -> 
            name.endsWith(".eml") || name.endsWith(".txt"));
        
        if (files != null) {
            for (File file : files) {
                messages.add(new Message(nextUid++, file));
            }
        }
        // Construit la correspondance numéro séquence -> UID
        for (int i = 0; i < messages.size(); i++) {
            uidMap.put(i + 1, messages.get(i).getUid());
        }
    }
    
    public String getName() { return name; }
    public int getMessageCount() { return messages.size(); }
    public int getNextUid() { return nextUid; }
    
    public Message getMessageBySequence(int seq) {
        if (seq < 1 || seq > messages.size()) return null;
        return messages.get(seq - 1);
    }
    
    public Message getMessageByUid(int uid) {
        for (Message msg : messages) {
            if (msg.getUid() == uid) return msg;
        }
        return null;
    }
    
    public List<Integer> searchMessages(String criteria) {
        List<Integer> results = new ArrayList<>();
        String searchLower = criteria.toLowerCase();
        
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            File file = msg.getFile();
            
            try {
                String content = new String(Files.readAllBytes(file.toPath())).toLowerCase();
                if (content.contains(searchLower)) {
                    results.add(i + 1);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return results;
    }
    
    public void updateFlags(int seq, String operation, String flags) {
        Message msg = getMessageBySequence(seq);
        if (msg != null) {
            boolean hasSeen = flags.contains("\\Seen");
            boolean hasDeleted = flags.contains("\\Deleted");
            
            switch (operation) {
                case "FLAGS":
                    msg.setSeen(hasSeen);
                    msg.setDeleted(hasDeleted);
                    break;
                case "+FLAGS":
                    if (hasSeen) msg.setSeen(true);
                    if (hasDeleted) msg.setDeleted(true);
                    break;
                case "-FLAGS":
                    if (hasSeen) msg.setSeen(false);
                    if (hasDeleted) msg.setDeleted(false);
                    break;
            }
        }
    }
    
    public int getUnseenCount() {
        int count = 0;
        for (int i = 1; i <= messages.size(); i++) {
            Message msg = getMessageBySequence(i);
            if (msg != null && !msg.isSeen()) count++;
        }
        return count;
    }
    
    public int getFirstUnseen() {
        for (int i = 1; i <= messages.size(); i++) {
            Message msg = getMessageBySequence(i);
            if (msg != null && !msg.isSeen()) return i;
        }
        return 0;
    }
}



class ImapSessionMultiplexed {
    private SocketChannel clientChannel;
    private Selector selector;
    private ByteBuffer readBuffer = ByteBuffer.allocate(8192);
    private StringBuilder commandBuffer = new StringBuilder();
    private ImapServerGUI gui;

    private ImapState state;
    private ImapServerMultiplexed server;
    private String currentUser;
    private File userDir;
    private Mailbox currentMailbox;
    private String currentTag;
    private int clientNum;
    
    private static final String CAPABILITIES = "IMAP4rev1";
    private static final String GREETING = "* OK [CAPABILITY " + CAPABILITIES + "] IMAP4rev1 Server Ready";
    
    public ImapSessionMultiplexed(SocketChannel channel, Selector selector, ImapServerGUI gui, ImapServerMultiplexed server) {
        this.clientChannel = channel;
        this.selector = selector;
        this.gui = gui; // Initialisé
        this.server = server;
        this.state = ImapState.NOT_AUTHENTICATED;
        this.setClientNum(gui.getNextClientNumber()); // Numéro de client unique
    }
    
    public int getClientNum() {
        return clientNum;
        
    }

    public void setClientNum(int clientNum) {
        this.clientNum = clientNum;
        
    }

    public void sendGreeting() throws IOException {
        sendLine(GREETING);
    }
    
    public void read() throws IOException {
        readBuffer.clear();
        int bytesRead = clientChannel.read(readBuffer);

        if (bytesRead == -1) {
            closeSession();
            return;
        }

        readBuffer.flip();
        byte[] data = new byte[readBuffer.limit()];
        readBuffer.get(data);

        String received = new String(data);
        commandBuffer.append(received);

        // On traite tant qu'il y a des lignes complètes dans le buffer
        while (commandBuffer.toString().contains("\r\n")) {
            int eol = commandBuffer.indexOf("\r\n");
            String line = commandBuffer.substring(0, eol);
            commandBuffer.delete(0, eol + 2); // Retire la ligne traitée

            line = cleanInput(line); // CORRECTION : Intégration du nettoyage
            if (!line.isEmpty()) {
                gui.appendLog("Client " + getClientNum() + " -> " + line);
                processCommand(line);
            }
        }
    }
    
    public void closeSession() throws IOException {
        if (clientChannel.isOpen()) {
        gui.updateClientCount(false); // Décrémente le compteur
        gui.appendLog("ÉVÉNEMENT : Client " + clientNum + " déconnecté.");
        server.removeSession(clientChannel);
        clientChannel.close();
       }
    }

    private void sendLine(String line) throws IOException {
        System.out.println("S: " + line);
        gui.appendLog("Serveur (to Client " + getClientNum() + ")-> " + line); // Affichage GUI

        ByteBuffer buffer = ByteBuffer.wrap((line + "\r\n").getBytes());
        clientChannel.write(buffer);
    }
    
    private void processCommand(String line) {
        try {
            if (line.trim().isEmpty()) return;
            
            String[] parts = line.split(" ", 3);
            String tag = parts[0];
            String command = parts[1].toUpperCase();
            String args = parts.length > 2 ? parts[2] : "";
            
            currentTag = tag;
            
            switch (command) {
                case "LOGIN":
                    handleLogin(args);
                    break;
                case "SELECT":
                    handleSelect(args);
                    break;
                case "FETCH":
                    handleFetch(args);
                    break;
                case "STORE":
                    handleStore(args);
                    break;
                case "SEARCH":
                    handleSearch(args);
                    break;
                case "LOGOUT":
                    handleLogout();
                    break;
                case "CAPABILITY":
                    handleCapability();
                    break;
                case "NOOP":
                    handleNoop();
                    break;
                default:
                    sendLine(tag + " BAD Unknown command");
                    break;
            }
        } catch (IOException e) {
            try {
                sendLine(currentTag + " BAD Server error: " + e.getMessage());
            } catch (IOException ex) {
                System.err.println("Erreur d'envoi: " + ex.getMessage());
            }
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
    
    
    private void handleCapability() throws IOException {
        sendLine("* CAPABILITY " + CAPABILITIES);
        sendLine(currentTag + " OK CAPABILITY completed");
    }
    
    private void handleLogin(String args) throws IOException {
        if (state != ImapState.NOT_AUTHENTICATED) {
            sendLine(currentTag + " BAD Already authenticated");
            return;
        }
        
        String[] credentials = args.split(" ");
        if (credentials.length < 2) {
            sendLine(currentTag + " BAD Invalid arguments");
            return;
        }
        
        String username = credentials[0];
        String password = credentials[1]; // Dans un vrai serveur, vérifier le mot de passe
        
        File dir = new File("mailserver/" + username);
        if (dir.exists() && dir.isDirectory()) {
            currentUser = username;
            userDir = dir;
            state = ImapState.AUTHENTICATED;
            
            sendLine(currentTag + " OK LOGIN completed");
        } else {
            sendLine(currentTag + " NO Login failed");
        }
    }
    
    private void handleSelect(String args) throws IOException {
        if (state == ImapState.NOT_AUTHENTICATED) {
            sendLine(currentTag + " BAD Not authenticated");
            return;
        }
        
        String mailboxName = args.trim();
        
        // Conformément à la consigne : uniquement INBOX pour simplifier
        if (!"INBOX".equalsIgnoreCase(mailboxName)) {
            sendLine(currentTag + " NO Mailbox doesn't exist (only INBOX is supported)");
            return;
        }
        
        //File inboxDir = new File(userDir, "INBOX");
        File inboxDir = userDir;
        if (!inboxDir.exists()) {
            inboxDir.mkdirs();
        }
        
        currentMailbox = new Mailbox("INBOX", inboxDir);
        state = ImapState.SELECTED;
        
        // Réponses conformes à la RFC
        sendLine("* " + currentMailbox.getMessageCount() + " EXISTS");
        sendLine("* 0 RECENT"); // Simplifié
        sendLine("* OK [UIDNEXT " + currentMailbox.getNextUid() + "] Predicted next UID");
        sendLine("* OK [UIDVALIDITY " + System.currentTimeMillis() + "] UIDs valid");
        sendLine("* FLAGS (\\Seen \\Deleted)");
        sendLine("* OK [PERMANENTFLAGS (\\Seen \\Deleted)] Limited");
        
        sendLine(currentTag + " OK [READ-WRITE] SELECT completed");
    }
    
    private void handleFetch(String args) throws IOException {
        if (state != ImapState.SELECTED) {
            sendLine(currentTag + " BAD No mailbox selected");
            return;
        }
        
        String[] parts = args.split(" ", 2);
        if (parts.length < 2) {
            sendLine(currentTag + " BAD Invalid FETCH arguments");
            return;
        }
        
        String sequence = parts[0];
        String dataItems = parts[1].toUpperCase();
        
        try {
            int seqNum = Integer.parseInt(sequence);
            fetchMessage(seqNum, dataItems);
        } catch (NumberFormatException e) {
            sendLine(currentTag + " BAD Invalid sequence number");
            return;
        }
        
        sendLine(currentTag + " OK FETCH completed");
    }
    
    private void fetchMessage(int seqNum, String dataItems) throws IOException {
        Message msg = currentMailbox.getMessageBySequence(seqNum);
        if (msg == null) {
            sendLine(currentTag + " BAD Invalid message sequence number");
            return;
        }
        
        String content = new String(Files.readAllBytes(msg.getFile().toPath()));
        
        if (dataItems.contains("BODY[HEADER]") || dataItems.contains("BODY.PEEK[HEADER]")) {
            // Récupérer uniquement les en-têtes
            String[] lines = content.split("\n");
            StringBuilder headers = new StringBuilder();
            for (String line : lines) {
                if (line.trim().isEmpty()) break;
                headers.append(line).append("\n");
            }
            sendLine("* " + seqNum + " FETCH (BODY[HEADER] {" + headers.length() + "}");
            sendLine(headers.toString());
        } else if (dataItems.contains("BODY[]")) {
            // Message complet
            sendLine("* " + seqNum + " FETCH (BODY[] {" + content.length() + "}");
            sendLine(content);
        } else if (dataItems.contains("FLAGS")) {
            // Uniquement les flags
            sendLine("* " + seqNum + " FETCH (FLAGS (" + msg.getFlags() + "))");
        } else {
            // Réponse minimale par défaut
            sendLine("* " + seqNum + " FETCH (FLAGS (" + msg.getFlags() + ") RFC822.SIZE " + msg.getFile().length() + ")");
        }
        
        // Marquer comme lu sauf si BODY.PEEK est utilisé
        if (!dataItems.contains("BODY.PEEK") && !msg.isSeen()) {
            msg.setSeen(true);
        }
    }
    
    private void handleStore(String args) throws IOException {
        if (state != ImapState.SELECTED) {
            sendLine(currentTag + " BAD No mailbox selected");
            return;
        }
        
        // Format: STORE <sequence> <operation> <flag list>
        String[] parts = args.split(" ", 3);
        if (parts.length < 3) {
            sendLine(currentTag + " BAD Invalid STORE arguments");
            return;
        }
        
        String sequence = parts[0];
        String operation = parts[1].toUpperCase();
        String flagList = parts[2].replaceAll("[()]", "");
        
        try {
            int seqNum = Integer.parseInt(sequence);
            currentMailbox.updateFlags(seqNum, operation, flagList);
            
            if (!operation.contains(".SILENT")) {
                Message msg = currentMailbox.getMessageBySequence(seqNum);
                sendLine("* " + seqNum + " FETCH (FLAGS (" + msg.getFlags() + "))");
            }
            
            sendLine(currentTag + " OK STORE completed");
        } catch (NumberFormatException e) {
            sendLine(currentTag + " BAD Invalid sequence number");
        }
    }
    
    private void handleSearch(String args) throws IOException {
        if (state != ImapState.SELECTED) {
            sendLine(currentTag + " BAD No mailbox selected");
            return;
        }
        
        // Recherche simple (contient le texte)
        List<Integer> results = currentMailbox.searchMessages(args);
        
        StringBuilder response = new StringBuilder("* SEARCH");
        for (Integer seq : results) {
            response.append(" ").append(seq);
        }
        sendLine(response.toString());
        sendLine(currentTag + " OK SEARCH completed");
    }
    
    private void handleNoop() throws IOException {
        sendLine(currentTag + " OK NOOP completed");
    }
    
    private void handleLogout() throws IOException {
        sendLine("* BYE IMAP4rev1 Server logging out");
        sendLine(currentTag + " OK LOGOUT completed");
        state = ImapState.LOGOUT;
        closeSession();
    }
}
