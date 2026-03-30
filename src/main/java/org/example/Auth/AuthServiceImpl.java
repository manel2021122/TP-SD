package org.example.Auth;


import org.example.SharedFolder.IAuthService;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AuthServiceImpl extends UnicastRemoteObject implements IAuthService {
    private Map<String, String> users = new HashMap<>();
    private Map<String, String> activeTokens = new HashMap<>();
    private static final String FILE_PATH = "users.json";
    private Gson gson = new Gson();

    public AuthServiceImpl() throws RemoteException {
        super();
        loadUsers();
    }

    // --- 1. Authentification (Scénario 4.1) ---
    @Override
    public boolean authenticate(String username, String password) throws RemoteException {
        return users.containsKey(username) && users.get(username).equals(password);
    }

    // --- 2. Gestion des Tokens (Pour le schéma de la Figure 3) ---
    @Override
    public String loginAndGetToken(String username, String password) throws RemoteException {
        if (authenticate(username, password)) {
            String token = UUID.randomUUID().toString();
            activeTokens.put(token, username);
            return token;
        }
        return null;
    }

    @Override
    public String verifyToken(String token) throws RemoteException {
        return activeTokens.get(token);
    }

    // --- 3. Opérations CRUD (Scénario 4.1 & 4.3) ---
    @Override
    public boolean createUser(String username, String password) throws RemoteException {
        if (users.containsKey(username)) return false;
        users.put(username, password);
        saveUsers();
        return true;
    }

    @Override
    public boolean updateUser(String username, String newPassword) throws RemoteException {
        if (!users.containsKey(username)) return false;
        users.put(username, newPassword);
        saveUsers();
        return true;
    }

    @Override
    public boolean deleteUser(String username) throws RemoteException {
        if (users.remove(username) != null) {
            saveUsers();
            return true;
        }
        return false;
    }

    // --- 4. Persistance JSON ---
    private void loadUsers() {
        File file = new File(FILE_PATH);
        if (!file.exists()) {
            users = new HashMap<>();
            return;
        }
        try (Reader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, String>>(){}.getType();
            users = gson.fromJson(reader, type);
            if (users == null) users = new HashMap<>();
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void saveUsers() {
        try (Writer writer = new FileWriter(FILE_PATH)) {
            gson.toJson(users, writer);
        } catch (IOException e) { e.printStackTrace(); }
    }
}