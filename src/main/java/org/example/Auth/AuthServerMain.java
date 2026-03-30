package org.example.Auth;

import org.example.SharedFolder.IAuthService;

import java.rmi.Naming;
import java.rmi.registry.LocateRegistry;

public class AuthServerMain {
    public static void main(String[] args) {
        try {
            // Démarrer l'annuaire RMI (Registry) sur le port par défaut 1099
            LocateRegistry.createRegistry(1099);

            // Créer l'instance du service
            IAuthService authService = new AuthServiceImpl();

            // Enregistrer le service sous le nom "AuthService"
            Naming.rebind("rmi://localhost/AuthService", authService);

            System.out.println("Serveur d'authentification RMI prêt.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}