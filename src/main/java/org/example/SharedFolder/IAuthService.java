package org.example.SharedFolder;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface IAuthService extends Remote {
    boolean authenticate(String user, String pass) throws RemoteException;
    String loginAndGetToken(String user, String pass) throws RemoteException;
    String verifyToken(String token) throws RemoteException;
    boolean createUser(String user, String pass) throws RemoteException;
    boolean updateUser(String user, String newPass) throws RemoteException; // Vérifie celle-ci !
    boolean deleteUser(String user) throws RemoteException;
}