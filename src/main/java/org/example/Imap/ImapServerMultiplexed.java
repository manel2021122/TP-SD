package org.example.Imap;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.net.*;
import java.util.concurrent.ConcurrentHashMap;
import org.example.SharedFolder.IAuthService; // Import de l'interface RMI

public class ImapServerMultiplexed {
    private static final int PORT = 1143;
    private Selector selector;
    private ImapServerGUI gui;
    private ServerSocketChannel serverChannel;
    private volatile boolean active = true;
    private Map<SocketChannel, ImapSessionMultiplexed> sessions = new ConcurrentHashMap<>();
    
    public ImapServerMultiplexed(ImapServerGUI gui) {
        this.gui = gui;
    }
    
    public static void main(String[] args) {
        new ImapServerMultiplexed(new ImapServerGUI()).start();
    }
    
    public void start() {
        try {
            selector = Selector.open();
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.socket().bind(new InetSocketAddress(PORT));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            
            gui.appendLog("Serveur IMAP Multiplexé démarré sur le port " + PORT);
            
            while (active) {
                if(selector.select() == 0) continue;
                
                Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();
                    
                    if (!key.isValid()) continue;
                    
                    try {
                        if (key.isAcceptable()) {
                            acceptConnection(key);
                        } else if (key.isReadable()) {
                            readFromClient(key);
                        }
                    } catch (IOException e) {
                        key.cancel();
                        try { key.channel().close(); } catch (IOException ex) {}
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
                for (ImapSessionMultiplexed session : sessions.values()) {
                    try { session.closeSession(); } catch (IOException e) {}
                }
                sessions.clear();
                selector.close();
            }
            if (serverChannel != null) serverChannel.close();
            gui.appendLog("SYSTÈME : Serveur IMAP arrêté.");
        } catch (IOException e) {
            gui.appendLog("Erreur arrêt : " + e.getMessage());
        }
    } 

    public void removeSession(SocketChannel channel) {
        sessions.remove(channel);
    }

    private void acceptConnection(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ);
        
        ImapSessionMultiplexed session = new ImapSessionMultiplexed(client, selector, gui, this);
        sessions.put(client, session);
        
        gui.updateClientCount(true);
        gui.appendLog("Connexion : Client " + session.getClientNum() + " (" + client.getRemoteAddress() + ")");
        session.sendGreeting();
    }
    
    private void readFromClient(SelectionKey key) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ImapSessionMultiplexed session = sessions.get(clientChannel);
        if (session != null) {
            try {
                session.read();
            } catch (IOException e) {
                session.closeSession();
                key.cancel();
            }
        }
    }
}

enum ImapState { NOT_AUTHENTICATED, AUTHENTICATED, SELECTED, LOGOUT }

// Les classes Message et Mailbox restent identiques à ton code original
class Message {
    private int uid;
    private File file;
    private boolean seen;
    private boolean deleted;
    
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
    private int nextUid;
    
    public Mailbox(String name, File directory) {
        this.name = name;
        this.messages = new ArrayList<>();
        this.nextUid = 1;
        loadMessages(directory);
    }
    
    private void loadMessages(File directory) {
        File[] files = directory.listFiles((dir, fname) -> fname.endsWith(".txt") || fname.endsWith(".eml"));
        if (files != null) {
            for (File file : files) {
                messages.add(new Message(nextUid++, file));
            }
        }
    }
    
    public String getName() { return name; }
    public int getMessageCount() { return messages.size(); }
    public int getNextUid() { return nextUid; }
    public Message getMessageBySequence(int seq) {
        return (seq < 1 || seq > messages.size()) ? null : messages.get(seq - 1);
    }
    
    public List<Integer> searchMessages(String criteria) {
        List<Integer> results = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            try {
                String content = new String(Files.readAllBytes(messages.get(i).getFile().toPath())).toLowerCase();
                if (content.contains(criteria.toLowerCase())) results.add(i + 1);
            } catch (IOException e) {}
        }
        return results;
    }
    
    public void updateFlags(int seq, String operation, String flags) {
        Message msg = getMessageBySequence(seq);
        if (msg != null) {
            boolean seen = flags.contains("\\Seen");
            boolean del = flags.contains("\\Deleted");
            if (operation.equals("FLAGS")) { msg.setSeen(seen); msg.setDeleted(del); }
            else if (operation.equals("+FLAGS")) { if(seen) msg.setSeen(true); if(del) msg.setDeleted(true); }
            else if (operation.equals("-FLAGS")) { if(seen) msg.setSeen(false); if(del) msg.setDeleted(false); }
        }
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
    private IAuthService authService; // Stub RMI
    private String sessionToken = null;
    private String authenticatedUser = null;


    public ImapSessionMultiplexed(SocketChannel channel, Selector selector, ImapServerGUI gui, ImapServerMultiplexed server) {
        this.clientChannel = channel;
        this.selector = selector;
        this.gui = gui;
        this.server = server;
        this.state = ImapState.NOT_AUTHENTICATED;
        this.clientNum = gui.getNextClientNumber();
        
        // Liaison RMI
        try {
            authService = (IAuthService) java.rmi.Naming.lookup("rmi://localhost/AuthService");
        } catch (Exception e) {
            gui.appendLog("IMAP RMI ERROR (Client " + clientNum + "): AuthService introuvable.");
        }
    }
    
    public int getClientNum() { return clientNum; }

    public void sendGreeting() throws IOException {
        sendLine("* OK [CAPABILITY IMAP4rev1] Server Ready");
    }
    
    public void read() throws IOException {
        readBuffer.clear();
        int bytesRead = clientChannel.read(readBuffer);
        if (bytesRead == -1) { closeSession(); return; }
        
        readBuffer.flip();
        byte[] data = new byte[readBuffer.limit()];
        readBuffer.get(data);
        commandBuffer.append(new String(data));

        while (commandBuffer.toString().contains("\r\n")) {
            int eol = commandBuffer.indexOf("\r\n");
            String line = commandBuffer.substring(0, eol);
            commandBuffer.delete(0, eol + 2);
            line = cleanInput(line);
            if (!line.isEmpty()) {
                gui.appendLog("Client " + clientNum + " -> " + line);
                processCommand(line);
            }
        }
    }
    
    public void closeSession() throws IOException {
        if (clientChannel.isOpen()) {
            gui.updateClientCount(false);
            gui.appendLog("ÉVÉNEMENT : Client " + clientNum + " déconnecté.");
            server.removeSession(clientChannel);
            clientChannel.close();
        }
    }

    private void sendLine(String line) throws IOException {
        gui.appendLog("Serveur -> Client " + clientNum + ": " + line);
        ByteBuffer buffer = ByteBuffer.wrap((line + "\r\n").getBytes());
        clientChannel.write(buffer);
    }
    
    private void processCommand(String line) {
        try {
            String[] parts = line.split(" ", 3);
            if (parts.length < 2) return;
            currentTag = parts[0];
            String command = parts[1].toUpperCase();
            String args = parts.length > 2 ? parts[2] : "";
            
            switch (command) {
                case "CAPABILITY": sendLine("* CAPABILITY IMAP4rev1"); sendLine(currentTag + " OK completed"); break;
                case "LOGIN": handleLogin(args); break;
                case "SELECT": handleSelect(args); break;
                case "FETCH": handleFetch(args); break;
                case "STORE": handleStore(args); break;
                case "SEARCH": handleSearch(args); break;
                case "NOOP": sendLine(currentTag + " OK NOOP completed"); break;
                case "LOGOUT": sendLine("* BYE Logging out"); sendLine(currentTag + " OK LOGOUT completed"); closeSession(); break;
                default: sendLine(currentTag + " BAD Unknown command"); break;
            }
        } catch (IOException e) {
            gui.appendLog("Erreur session " + clientNum + ": " + e.getMessage());
        }
    }

    private void handleLogin(String args) throws IOException {
        String[] creds = args.split(" ");
        if (creds.length < 2) { sendLine(currentTag + " BAD Arguments"); return; }
        
        String user = creds[0].replace("\"", "");
        String pass = creds[1].replace("\"", "");

        try {
            if (authService != null) {
                // APPEL RMI : On récupère le jeton
                String token = authService.loginAndGetToken(user, pass);

                if (token != null) {
                    this.sessionToken = token;
                    this.authenticatedUser = user;
                    this.userDir = new File("mailserver/" + user);
                    if (!userDir.exists()) userDir.mkdirs();

                    this.state = ImapState.AUTHENTICATED;
                    sendLine(currentTag + " OK LOGIN completed. Session: " + token);
                } else {
                    sendLine(currentTag + " NO Login failed");
                }
            }
        } catch (Exception e) {
            sendLine(currentTag + " BAD Auth Service error");
        }
    }

    private boolean isTokenValid() {
        try {
            if (sessionToken == null || authService == null) return false;
            String verifiedUser = authService.verifyToken(sessionToken);
            // On vérifie que le token est valide ET qu'il appartient bien à l'utilisateur de la session
            return verifiedUser != null && verifiedUser.equals(authenticatedUser);
        } catch (Exception e) {
            return false;
        }
    }

    private void handleSelect(String args) throws IOException {
        if (!isTokenValid()) { sendLine(currentTag + " BAD Invalid session token"); return; }
        if (state == ImapState.NOT_AUTHENTICATED) { sendLine(currentTag + " BAD Auth required"); return; }
        currentMailbox = new Mailbox("INBOX", userDir);
        state = ImapState.SELECTED;
        sendLine("* " + currentMailbox.getMessageCount() + " EXISTS");
        sendLine("* FLAGS (\\Seen \\Deleted)");
        sendLine(currentTag + " OK [READ-WRITE] SELECT completed");
    }

    private void handleFetch(String args) throws IOException {
        if (!isTokenValid()) { sendLine(currentTag + " BAD Invalid session token"); return; }
        if (state != ImapState.SELECTED) { sendLine(currentTag + " BAD Select mailbox first"); return; }
        String[] parts = args.split(" ");
        int seq = Integer.parseInt(parts[0]);
        Message msg = currentMailbox.getMessageBySequence(seq);
        if (msg != null) {
            String content = new String(Files.readAllBytes(msg.getFile().toPath()));
            sendLine("* " + seq + " FETCH (BODY[] {" + content.length() + "}");
            sendLine(content);
            sendLine(")");
            sendLine(currentTag + " OK FETCH completed");
        } else {
            sendLine(currentTag + " NO No such message");
        }
    }

    private void handleStore(String args) throws IOException {
        if (!isTokenValid()) { sendLine(currentTag + " BAD Invalid session token"); return; }
        String[] parts = args.split(" ", 3);
        int seq = Integer.parseInt(parts[0]);
        currentMailbox.updateFlags(seq, parts[1].toUpperCase(), parts[2]);
        sendLine(currentTag + " OK STORE completed");
    }

    private void handleSearch(String args) throws IOException {
        if (!isTokenValid()) { sendLine(currentTag + " BAD Invalid session token"); return; }
        List<Integer> ids = currentMailbox.searchMessages(args);
        StringBuilder sb = new StringBuilder("* SEARCH");
        for(int id : ids) sb.append(" ").append(id);
        sendLine(sb.toString());
        sendLine(currentTag + " OK SEARCH completed");
    }

    private String cleanInput(String input) {
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (c == '\b' || (int)c == 127) { if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1); }
            else { sb.append(c); }
        }
        return sb.toString().trim();
    }
}