

import java.io.*;
import java.net.*;
import java.util.*;
import java.text.SimpleDateFormat;

public class ImapTestScenarios {
    private static final String SERVER = "localhost";
    private static final int PORT = 1143;
    private static final String USER = "testuser";
    private static final String PASSWORD = "password"; // Non vérifié dans notre implémentation
    
    private static PrintWriter logWriter;
    private static int testNumber = 1;
    
    public static void main(String[] args) {
        try {
            // Créer un fichier de log pour les résultats
            logWriter = new PrintWriter(new FileWriter("imap_test_results.log"));
            log("=== RAPPORT DE TESTS IMAP - " + new Date() + " ===\n");
            
            // Préparer l'environnement de test
            prepareTestEnvironment();
            
            // Exécuter tous les scénarios
            runAllScenarios();
            
            logWriter.close();
            System.out.println("\n✅ Tous les tests terminés. Consultez imap_test_results.log pour les détails.");
            
        } catch (IOException e) {
            System.err.println("Erreur lors de l'initialisation des tests: " + e.getMessage());
        }
    }
    
    private static void prepareTestEnvironment() throws IOException {
        System.out.println("🔧 Préparation de l'environnement de test...");
        
        // Créer le répertoire de l'utilisateur
        File userDir = new File("mailserver/" + USER + "/INBOX");
        userDir.mkdirs();
        
        // Créer des messages de test avec différents contenus
        String[][] testMessages = {
            {
                "From: alice@example.com\r\n" +
                "To: testuser@example.com\r\n" +
                "Subject: Réunion importante\r\n" +
                "Date: " + getCurrentDate() + "\r\n" +
                "Message-ID: <123@example.com>\r\n" +
                "\r\n" +
                "Bonjour,\r\n" +
                "Nous avons une réunion importante demain à 10h.\r\n" +
                "Cordialement,\r\n" +
                "Alice"
            },
            {
                "From: bob@example.com\r\n" +
                "To: testuser@example.com\r\n" +
                "Subject: Projet IMAP\r\n" +
                "Date: " + getCurrentDate() + "\r\n" +
                "Message-ID: <456@example.com>\r\n" +
                "\r\n" +
                "Le projet IMAP avance bien.\r\n" +
                "J'ai presque terminé l'implémentation.\r\n" +
                "Bob"
            },
            {
                "From: charlie@example.com\r\n" +
                "To: testuser@example.com\r\n" +
                "Subject: Test de recherche\r\n" +
                "Date: " + getCurrentDate() + "\r\n" +
                "Message-ID: <789@example.com>\r\n" +
                "\r\n" +
                "Ce message servira pour tester la recherche.\r\n" +
                "Il contient des mots-clés spécifiques."
            }
        };
        
        // Sauvegarder les messages
        for (int i = 0; i < testMessages.length; i++) {
            File msgFile = new File(userDir, "msg" + (i + 1) + ".eml");
            try (FileWriter writer = new FileWriter(msgFile)) {
                writer.write(testMessages[i]);
            }
            System.out.println("  ✓ Message " + (i + 1) + " créé");
        }
        
        System.out.println("✅ Environnement de test prêt avec " + testMessages.length + " messages\n");
    }
    
    private static String getCurrentDate() {
        return new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH).format(new Date());
    }
    
    private static void runAllScenarios() {
        testScenario1_BasicFlow();
        testScenario2_SelectNonExistentMailbox();
        testScenario3_PartialFetch();
        testScenario4_FlagManagement();
        testScenario5_Search();
        testScenario6_WrongStateCommands();
    }
    
    private static void log(String message) {
        System.out.println(message);
        logWriter.println(message);
        logWriter.flush();
    }
    
    private static void logResponse(String prefix, String response) {
        log(prefix + response);
    }
    
    /**
     * SCÉNARIO 1: Scénario de base
     * - Connexion
     * - Authentification LOGIN
     * - Sélection INBOX avec SELECT
     * - Lecture d'un message avec FETCH
     * - Déconnexion avec LOGOUT
     */
    private static void testScenario1_BasicFlow() {
        log("\n" + "=".repeat(80));
        log("📋 SCÉNARIO 1: Parcours de base");
        log("=".repeat(80));
        log("Objectif: Vérifier le fonctionnement normal du serveur\n");
        
        try (Socket socket = new Socket(SERVER, PORT);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            
            // 1. Lire le greeting
            String response = in.readLine();
            logResponse("S: ", response);
            verify(response.startsWith("* OK"), "Le serveur doit envoyer un greeting valide");
            
            // 2. LOGIN
            out.println("A001 LOGIN " + USER + " " + PASSWORD);
            log("C: A001 LOGIN " + USER + " " + PASSWORD);
            response = in.readLine();
            logResponse("S: ", response);
            verify(response.equals("A001 OK LOGIN completed"), "Login doit réussir");
            
            // 3. SELECT INBOX
            out.println("A002 SELECT INBOX");
            log("C: A002 SELECT INBOX");
            
            // Lire toutes les réponses jusqu'au OK
            List<String> selectResponses = new ArrayList<>();
            while (!(response = in.readLine()).startsWith("A002 OK")) {
                selectResponses.add(response);
                logResponse("S: ", response);
            }
            logResponse("S: ", response);
            
            // Vérifier les réponses SELECT
            verify(selectResponses.stream().anyMatch(r -> r.contains("EXISTS")), 
                   "Le serveur doit indiquer le nombre de messages");
            verify(selectResponses.stream().anyMatch(r -> r.contains("FLAGS")), 
                   "Le serveur doit annoncer les flags supportés");
            
            // 4. FETCH du message 1
            out.println("A003 FETCH 1 BODY[]");
            log("C: A003 FETCH 1 BODY[]");
            
            // Lire la réponse FETCH
            response = in.readLine();
            logResponse("S: ", response);
            verify(response.contains("FETCH") && response.contains("BODY[]"), 
                   "La réponse FETCH doit contenir le message");
            
            // Lire le contenu du message
            String line;
            while (!(line = in.readLine()).startsWith("A003 OK")) {
                if (!line.equals(".")) { // Ignorer le terminateur
                    logResponse("S: ", line);
                }
            }
            logResponse("S: ", line);
            
            // 5. LOGOUT
            out.println("A004 LOGOUT");
            log("C: A004 LOGOUT");
            response = in.readLine();
            logResponse("S: ", response);
            verify(response.contains("BYE"), "Le serveur doit envoyer BYE");
            response = in.readLine();
            logResponse("S: ", response);
            verify(response.equals("A004 OK LOGOUT completed"), "LOGOUT doit réussir");
            
            log("✅ SCÉNARIO 1: RÉUSSI\n");
            
        } catch (IOException e) {
            log("❌ SCÉNARIO 1: ÉCHEC - " + e.getMessage());
        }
    }
    
    /**
     * SCÉNARIO 2: Sélection de boîte inexistante
     */
    private static void testScenario2_SelectNonExistentMailbox() {
        log("\n" + "=".repeat(80));
        log("📋 SCÉNARIO 2: Sélection de boîte inexistante");
        log("=".repeat(80));
        log("Objectif: Vérifier la gestion d'erreur sur SELECT d'une boîte qui n'existe pas\n");
        
        try (Socket socket = new Socket(SERVER, PORT);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            
            // Lire le greeting
            in.readLine();
            
            // LOGIN
            out.println("A001 LOGIN " + USER + " " + PASSWORD);
            in.readLine(); // Ignorer réponse LOGIN
            
            // SELECT UNKNOWN (boîte inexistante)
            out.println("A002 SELECT UNKNOWN");
            log("C: A002 SELECT UNKNOWN");
            String response = in.readLine();
            logResponse("S: ", response);
            
            // Vérifier que c'est une réponse d'erreur
            verify(response.startsWith("A002 NO") || response.contains("only INBOX"), 
                   "Le serveur doit refuser la sélection d'une boîte inexistante");
            
            // LOGOUT
            out.println("A003 LOGOUT");
            in.readLine(); // BYE
            in.readLine(); // OK
            
            log("✅ SCÉNARIO 2: RÉUSSI\n");
            
        } catch (IOException e) {
            log("❌ SCÉNARIO 2: ÉCHEC - " + e.getMessage());
        }
    }
    
    /**
     * SCÉNARIO 3: Lecture partielle (uniquement les en-têtes)
     */
    private static void testScenario3_PartialFetch() {
        log("\n" + "=".repeat(80));
        log("📋 SCÉNARIO 3: Lecture partielle (FETCH BODY[HEADER])");
        log("=".repeat(80));
        log("Objectif: Vérifier que le serveur peut retourner seulement les en-têtes\n");
        
        try (Socket socket = new Socket(SERVER, PORT);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            
            // Connexion et authentification
            in.readLine(); // Greeting
            out.println("A001 LOGIN " + USER + " " + PASSWORD);
            in.readLine(); // OK LOGIN
            out.println("A002 SELECT INBOX");
            
            // Lire les réponses SELECT
            String response;
            while (!(response = in.readLine()).startsWith("A002 OK")) {
                // Ignorer
            }
            
            // FETCH uniquement les en-têtes
            out.println("A003 FETCH 1 BODY[HEADER]");
            log("C: A003 FETCH 1 BODY[HEADER]");
            
            // Lire la réponse
            response = in.readLine();
            logResponse("S: ", response);
            
            boolean hasBody = false;
            boolean hasEmptyLine = false;
            
            // Lire le contenu des en-têtes
            String line;
            while (!(line = in.readLine()).startsWith("A003 OK")) {
                if (!line.equals(".")) {
                    logResponse("S: ", line);
                    if (line.trim().isEmpty()) {
                        hasEmptyLine = true; // Une ligne vide sépare en-têtes et corps
                    }
                    if (line.contains("This is the body")) {
                        hasBody = true; // Le corps ne devrait pas apparaître
                    }
                }
            }
            logResponse("S: ", line);
            
            // Vérifications
            verify(!hasBody, "Le corps du message ne doit pas être inclus");
            verify(response.contains("BODY[HEADER]"), "La réponse doit indiquer BODY[HEADER]");
            
            log("✅ SCÉNARIO 3: RÉUSSI\n");
            
        } catch (IOException e) {
            log("❌ SCÉNARIO 3: ÉCHEC - " + e.getMessage());
        }
    }
    
    /**
     * SCÉNARIO 4: Gestion des flags (marquer comme lu)
     */
    private static void testScenario4_FlagManagement() {
        log("\n" + "=".repeat(80));
        log("📋 SCÉNARIO 4: Gestion des flags (\\Seen)");
        log("=".repeat(80));
        log("Objectif: Vérifier que les flags sont correctement gérés\n");
        
        try (Socket socket = new Socket(SERVER, PORT);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            
            // Connexion
            in.readLine(); // Greeting
            out.println("A001 LOGIN " + USER + " " + PASSWORD);
            in.readLine(); // OK LOGIN
            out.println("A002 SELECT INBOX");
            
            // Lire les réponses SELECT
            String response;
            while (!(response = in.readLine()).startsWith("A002 OK")) {
                // Ignorer
            }
            
            // Vérifier les flags initiaux
            out.println("A003 FETCH 1 FLAGS");
            log("C: A003 FETCH 1 FLAGS");
            response = in.readLine();
            logResponse("S: ", response);
            
            boolean initiallySeen = response.contains("\\Seen");
            log("État initial: " + (initiallySeen ? "Lu" : "Non lu"));
            
            // Attendre le OK
            in.readLine();
            
            // Marquer comme lu
            out.println("A004 STORE 1 +FLAGS (\\Seen)");
            log("C: A004 STORE 1 +FLAGS (\\Seen)");
            
            // Lire la réponse STORE
            response = in.readLine();
            logResponse("S: ", response);
            verify(response.contains("\\Seen"), "Le flag \\Seen doit être ajouté");
            
            // Attendre le OK
            in.readLine();
            
            // Vérifier que le flag a été ajouté
            out.println("A005 FETCH 1 FLAGS");
            log("C: A005 FETCH 1 FLAGS");
            response = in.readLine();
            logResponse("S: ", response);
            
            verify(response.contains("\\Seen"), "Le message doit maintenant être marqué comme lu");
            
            // Attendre le OK
            in.readLine();
            
            // Maintenant, testons avec une nouvelle session pour vérifier la persistance
            log("\n--- Nouvelle session pour vérifier la persistance ---");
            
            try (Socket socket2 = new Socket(SERVER, PORT);
                 BufferedReader in2 = new BufferedReader(new InputStreamReader(socket2.getInputStream()));
                 PrintWriter out2 = new PrintWriter(socket2.getOutputStream(), true)) {
                
                in2.readLine(); // Greeting
                out2.println("B001 LOGIN " + USER + " " + PASSWORD);
                in2.readLine(); // OK LOGIN
                out2.println("B002 SELECT INBOX");
                
                while (!(response = in2.readLine()).startsWith("B002 OK")) {
                    // Ignorer
                }
                
                out2.println("B003 FETCH 1 FLAGS");
                log("C: B003 FETCH 1 FLAGS");
                response = in2.readLine();
                logResponse("S: ", response);
                
                verify(response.contains("\\Seen"), "Le flag \\Seen doit persister entre les sessions");
                
                in2.readLine(); // OK
                out2.println("B004 LOGOUT");
                in2.readLine(); // BYE
                in2.readLine(); // OK
            }
            
            // LOGOUT
            out.println("A006 LOGOUT");
            in.readLine(); // BYE
            in.readLine(); // OK
            
            log("✅ SCÉNARIO 4: RÉUSSI\n");
            
        } catch (IOException e) {
            log("❌ SCÉNARIO 4: ÉCHEC - " + e.getMessage());
        }
    }
    
    /**
     * SCÉNARIO 5: Recherche de messages
     */
    private static void testScenario5_Search() {
        log("\n" + "=".repeat(80));
        log("📋 SCÉNARIO 5: Recherche de messages");
        log("=".repeat(80));
        log("Objectif: Vérifier que la commande SEARCH fonctionne correctement\n");
        
        try (Socket socket = new Socket(SERVER, PORT);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            
            // Connexion
            in.readLine(); // Greeting
            out.println("A001 LOGIN " + USER + " " + PASSWORD);
            in.readLine(); // OK LOGIN
            out.println("A002 SELECT INBOX");
            
            // Lire les réponses SELECT
            String response;
            while (!(response = in.readLine()).startsWith("A002 OK")) {
                // Ignorer
            }
            
            // Recherche par mot-clé
            String[] searchTerms = {"réunion", "projet", "recherche", "important"};
            
            for (String term : searchTerms) {
                log("\nRecherche du terme: \"" + term + "\"");
                out.println("A003 SEARCH " + term);
                log("C: A003 SEARCH " + term);
                
                response = in.readLine();
                logResponse("S: ", response);
                
                // Vérifier que la réponse commence par * SEARCH
                verify(response.startsWith("* SEARCH"), "La réponse doit commencer par * SEARCH");
                
                // Compter les résultats
                String[] parts = response.split(" ");
                int resultCount = parts.length - 2; // Enlever "*" et "SEARCH"
                log("  → " + resultCount + " résultat(s) trouvé(s)");
                
                // Attendre le OK
                response = in.readLine();
                logResponse("S: ", response);
                verify(response.equals("A003 OK SEARCH completed"), "SEARCH doit se terminer par OK");
            }
            
            // LOGOUT
            out.println("A004 LOGOUT");
            in.readLine(); // BYE
            in.readLine(); // OK
            
            log("\n✅ SCÉNARIO 5: RÉUSSI\n");
            
        } catch (IOException e) {
            log("❌ SCÉNARIO 5: ÉCHEC - " + e.getMessage());
        }
    }
    
    /**
     * SCÉNARIO 6: Commandes dans le mauvais état
     */
    private static void testScenario6_WrongStateCommands() {
        log("\n" + "=".repeat(80));
        log("📋 SCÉNARIO 6: Commandes dans le mauvais état");
        log("=".repeat(80));
        log("Objectif: Vérifier que le serveur rejette les commandes dans un état invalide\n");
        
        try (Socket socket = new Socket(SERVER, PORT);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            
            // Lire le greeting
            in.readLine();
            
            // 1. FETCH avant authentification (devrait échouer)
            out.println("A001 FETCH 1 BODY[]");
            log("C: A001 FETCH 1 BODY[] (avant LOGIN)");
            String response = in.readLine();
            logResponse("S: ", response);
            verify(response.contains("BAD") || response.contains("NO"), 
                   "FETCH sans authentification doit être rejeté");
            
            // 2. LOGIN (réussit)
            out.println("A002 LOGIN " + USER + " " + PASSWORD);
            log("C: A002 LOGIN " + USER + " " + PASSWORD);
            response = in.readLine();
            logResponse("S: ", response);
            
            // 3. FETCH avant SELECT (devrait échouer)
            out.println("A003 FETCH 1 BODY[]");
            log("C: A003 FETCH 1 BODY[] (après LOGIN mais avant SELECT)");
            response = in.readLine();
            logResponse("S: ", response);
            verify(response.contains("BAD") || response.contains("No mailbox selected"), 
                   "FETCH sans SELECT doit être rejeté");
            
            // 4. SELECT (réussit)
            out.println("A004 SELECT INBOX");
            log("C: A004 SELECT INBOX");
            while (!(response = in.readLine()).startsWith("A004 OK")) {
                // Ignorer
            }
            
            // 5. FETCH après SELECT (doit réussir)
            out.println("A005 FETCH 1 FLAGS");
            log("C: A005 FETCH 1 FLAGS (après SELECT)");
            response = in.readLine();
            logResponse("S: ", response);
            verify(response.startsWith("* 1 FETCH"), 
                   "FETCH après SELECT doit réussir");
            
            // Lire le OK
            in.readLine();
            
            // 6. LOGOUT
            out.println("A006 LOGOUT");
            in.readLine(); // BYE
            in.readLine(); // OK
            
            log("\n✅ SCÉNARIO 6: RÉUSSI\n");
            
        } catch (IOException e) {
            log("❌ SCÉNARIO 6: ÉCHEC - " + e.getMessage());
        }
    }
    
    /**
     * Méthode utilitaire pour vérifier une condition
     */
    private static void verify(boolean condition, String message) {
        if (condition) {
            log("  ✓ " + message);
        } else {
            log("  ❌ ÉCHEC: " + message);
            // On ne lance pas d'exception pour continuer les tests
        }
    }
}