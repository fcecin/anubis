/**
 * Anubis: An Universal Basic Income Server.
 */
package anubis;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javax.xml.bind.DatatypeConverter;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;

/**
 * The ANUBIS server.
 * 
 * This will start the RMI server which is the main access point to the 
 *   public core data model.
 * The RMI server will also have another private persisted data model to 
 *   implement the security features (user passwords, etc.)
 */

public class Main {
    
    // The main economic parameters ---------------------------------------
    
    // Currency unit (Democratic Money Simulation)
    public final static String TICKER = "DMS";
    
    // One unit of the currency in "balance" ("satoshis", fractions)
    public final static long COIN = 10000; 
    
    // COIN in decimal places
    public final static int COIN_DECIMALS = 4;
    
    // Daily payment to all authentic human-user accounts
    public static final long UBI_AMOUNT = 24 * COIN;
    
    // User account daily fee
    public static final long USER_ACCOUNT_DAILY_FEE = 100;
    
    // Paid when validating someone who doesn't validate you (to avoid spam)
    public static final long NONRECIPROCAL_OUTBOUND_VALIDATION_FEE = 100;
    
    // Generic transaction fee to avoid spam
    public static final long TRANSACTION_FEE = 10;

    // Daily demurrage rate multiplier for 5%/year: v = v * 0.9998595764738929;
    // v = 100; for i=1,1461 do v = v * 0.9998595764738929; end; print(v);
    // v = 1; for i=1,4 do v = v * 0.95; print(v); end;
    public static final BigDecimal DAILY_DEMURRAGE_MULTIPLIER = new BigDecimal("0.9998595764738929");
    
    // Miscellaneous configuration ---------------------------------------
    
    // a week to accept an invitation
    public static final int INVITE_TIMEOUT_MINUTES = 7 * 24 * 60;
    
    // 36 credits gifted to an user you invite
    public static final long MIN_INVITE_AMOUNT = ((UBI_AMOUNT * 3) / 2);

    // max live invitation codes per user
    public static final int MAX_PENDING_INVITES = 20;
    
    // maximum number of other people that you can have validation links from/to
    public static final int MAX_UNIQUE_VALIDATION_IDS = 150;
    
    // a week to validate an user's profile
    public static final int AUTH_TIMEOUT_MINUTES = 7 * 24 * 60;
    
    // trusted users summoned to authenticate a new profile
    public static final int AUTH_TOTAL_VOTES = 15;
    
    // amount paid for each authenticator
    public static final long AUTH_PER_VOTE_FEE = 1 * COIN;
    
    // maximum fee for initiating your authentication. used by the client.
    public static final long AUTH_TOTAL_FEE = AUTH_PER_VOTE_FEE * AUTH_TOTAL_VOTES;

    // amount paid for each voter during an authentication challenge
    public static final long AUTH_CHALLENGE_PER_VOTE_FEE = AUTH_PER_VOTE_FEE * 20;

    // maximum fee for challenging an authentication. used by the client.
    public static final long AUTH_CHALLENGE_TOTAL_FEE = AUTH_CHALLENGE_PER_VOTE_FEE * AUTH_TOTAL_VOTES;
        
    // minutes since last login time until an account is deemed inactive and 
    //  its UBI payments cease, and its money begins to be destroyed faster 
    //  and faster (until the account is ultimately deleted or the user 
    //  decides to show up and log in again.)
    public static final int ACCOUNT_INACTIVITY_MINUTES = 1461 * 24 * 60;
    
    // maximum "comment" size in bytes for an user's burnt money receipt
    public static final int MAX_BURN_MONEY_COMMENT_BYTES = 600;
    
    // version of the burn receipt
    public static final byte BURN_RECEIPT_VERSION = 1;
    
    // Server configuration ----------------------------------------------

    // RMI server ports and names
    public static int RMI_REGISTRY_PORT = 1099;
    public static int RMI_SERVER_PORT = 11099;
    public static String RMI_SERVER_NAME = "ANUBIS";
    
    // Global vars -------------------------------------------------------
    
    // singleton static server supported
    static Server server;
    static Registry registry;
    
    static SecureRandom secureRandom;
    
    static boolean shutdown = false;
    static Thread mainThread;
    
    public static Logger log;
    
    // Methods -----------------------------------------------------------
    
    public static void log(String message) {
        log.log(Level.INFO, message);
    }

    public static void logError(String message) {
        log.log(Level.SEVERE, message);
    }

    public static void logError(String message, Throwable ex) {
        log.log(Level.SEVERE, message, ex);
    }

    // apply the demurrage charge for a balance
    public static long applyDailyDemurrage(long balance) {
        if (balance > 0) {
            BigDecimal bal = new BigDecimal(balance);
            bal = bal.multiply(DAILY_DEMURRAGE_MULTIPLIER);
            long newBalance = bal.longValue();
            if ((newBalance == balance) && (balance > 0))
                --newBalance; // make sure demurrage always steps towards zero
            balance = newBalance;
        }
        return balance;
    }
    
    public static String formatMoney(long amount) {
        BigDecimal b = BigDecimal.valueOf(amount);
        b = b.divide(BigDecimal.valueOf(COIN));
        if (b.signum() < 0)
            return "(" + b.abs().toString() + ")";
        else
            return b.toString();
    }

    public static String formatMoneyDecimals(long amount, int decimals) {
        BigDecimal b = BigDecimal.valueOf(amount);
        b = b.divide(BigDecimal.valueOf(COIN));
        b = b.setScale(decimals, BigDecimal.ROUND_HALF_DOWN);
        if (b.signum() < 0)
            return "(" + b.abs().toString() + ")";
        else
            return b.toString();
    }

    public static String formatMoneyTicker(long amount) {
        return formatMoney(amount) + " " + TICKER;
    }
    
    public static SecureRandom getSecureRandom() {
        if (secureRandom == null) {
            try {
                secureRandom = SecureRandom.getInstance("NativePRNGNonBlocking");
            } catch (NoSuchAlgorithmException e) { }
        }
        return secureRandom;
    }

    public static void createServer(String dataDir) throws Exception {
        // Error if it already exists
        if (server != null)
            throw new RuntimeException("Only one Server at a time is supported by Main::createServer().");
        // Create server
        server = new Server(dataDir);
    }
    
    public static void startServer() throws Exception {
        if (server == null)
            throw new RuntimeException("Cannot start server; server == null.");
        // Start the RMI server.
        ServerInterface stub = (ServerInterface)
            UnicastRemoteObject.exportObject(server, RMI_SERVER_PORT);
        if (registry == null)
            registry = LocateRegistry.createRegistry(RMI_REGISTRY_PORT);
        registry.rebind(RMI_SERVER_NAME, stub);
    }
    
    public static void stopServer() throws Exception {
        // Stop the RMI server.
        if (registry != null) {
            registry.unbind(RMI_SERVER_NAME);
            registry = null;
        }
        if (server != null)
            UnicastRemoteObject.unexportObject(server, true);
    }

    public static synchronized void requestServerShutdown() throws Exception {
        shutdown = true;
        mainThread.interrupt();
    }

    // https://stackoverflow.com/questions/779519/delete-directories-recursively-in-java
    public static void deleteDirectory(Path directory) {
        try {
            Files.walk(directory)
                .map(Path::toFile)
                .sorted((o1, o2) -> -o1.compareTo(o2))
                .forEach(File::delete);
        } catch (IOException e) { 
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    static void writeKeypairFiles(String fn, KeyPair keyPair) throws IOException {
        
        EdDSAPrivateKey prk = (EdDSAPrivateKey) keyPair.getPrivate();

        String fnprk = fn + ".prk";
        String fntxt = fn + ".txt";
        FileOutputStream fos;

        log("Writing encoded private key to: " + fnprk);
        fos = new FileOutputStream(fnprk);
        try { fos.write(prk.getEncoded()); } finally { fos.close(); }

        String hexPuk = DatatypeConverter.printHexBinary(prk.getAbyte());
        log("Public key (32-byte hex): " + hexPuk);
        log("Writing public key (32-byte hex) to: " + fntxt);
        try (FileWriter fw = new FileWriter(fntxt)) { fw.write(hexPuk); }
    }
    
    // =========================================================================

    /**
     * This creates and start an ANUBIS server.
     * @param argsArray the command-line arguments
     * @throws Exception If there's a problem initializing a Prevayler.
     */
    public static void main(String[] argsArray)  {

        // ==================== Setup logging ===============================
        
        // make sure log base path exists
        Path anubisDirPath = Paths.get(System.getProperty("user.home"), ".anubis");
        anubisDirPath.toFile().mkdirs();
        
        // Setup logging
        // %2$s = where
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT [%4$s] %5$s%6$s%n");
        SimpleFormatter formatter = new SimpleFormatter();
        log = Logger.getGlobal();
        try {
            FileHandler fileHandler = new FileHandler("%h/.anubis/anubis%g.log", 10000000, 2, true);
            fileHandler.setFormatter(formatter);
            log.addHandler(fileHandler);
        } catch (IOException e) {
            logError("FATAL: Cannot open log files at " + anubisDirPath.toString());
            System.exit(1);
        }
        
        // ============== Run everything wrapped around a try ===============

        try {
            main2(argsArray);
        } catch (Exception e) {
            logError("FATAL: Exception thrown in the main thread. Aborting process.", e);
            System.exit(1);
        }
    }

    public static void main2(String[] argsArray) throws Exception {
        
        mainThread = Thread.currentThread();

        // ==================== Run args ====================================
        
        Path generateMasterKeyPath = null;
        Path importMasterKeyPath = null;
        Path exportMasterKeyPath = null;
        boolean resetData = false;
        boolean runTests = false;
        boolean inviteAnchor = false;
        boolean fabby = false;
        boolean snapshot = false;
        boolean quit = false;
        long inviteAnchorAmount = 0;
        HashSet<Integer> setAnchor = new HashSet();
        HashSet<Integer> unsetAnchor = new HashSet();
        HashSet<Integer> superUserIds = new HashSet();
        
        List<String> args = Arrays.asList(argsArray);
        
        Iterator it = args.iterator();
        while (it.hasNext()) {
            String arg = (String)it.next();
            if (arg.startsWith("--")) {
                String cmd = arg.substring(2);
                switch (cmd) {
                    case "generate_master_key":
                        if (it.hasNext())
                            generateMasterKeyPath = Paths.get((String)it.next());
                        else {
                            logError("Must specify an output file prefix for the keypair files to write (e.g. 'keypair').");
                            System.exit(1);
                        }                            
                        break;
                    case "import_master_key":
                        if (it.hasNext())
                            importMasterKeyPath = Paths.get((String)it.next());
                        else {
                            logError("Must specify the private key input file to read (e.g. 'keypair.prk').");
                            System.exit(1);
                        }                            
                        break;
                    case "export_master_key":
                        if (it.hasNext())
                            exportMasterKeyPath = Paths.get((String)it.next());
                        else {
                            logError("Must specify an output file prefix for the keypair files to write (e.g. 'keypair').");
                            System.exit(1);
                        }                            
                        break;
                    case "reset_data":
                        resetData = true;
                        break;
                    case "run_tests":
                        runTests = true;
                        break;
                    case "invite_anchor":
                        inviteAnchor = true;
                        if (it.hasNext())
                            inviteAnchorAmount = Long.parseLong((String)it.next());
                        break;
                    case "set_anchor":
                        if (it.hasNext()) {
                            int userId = Integer.parseInt((String)it.next());
                            setAnchor.add(userId);
                        } else {
                            logError("Missing argument user ID for --set_anchor.");
                            System.exit(1);
                        }                            
                        break;
                    case "unset_anchor":
                        if (it.hasNext()) {
                            int userId = Integer.parseInt((String)it.next());
                            unsetAnchor.add(userId);
                        } else {
                            logError("Missing argument user ID for --unset_anchor.");
                            System.exit(1);
                        }                            
                        break;
                    case "snapshot":
                        snapshot = true;
                        break;
                    case "fabby":
                        fabby = true;
                        break;
                    case "su":
                        if (it.hasNext()) {
                            int userId = Integer.parseInt((String)it.next());
                            superUserIds.add(userId);
                        } else {
                            logError("Missing argument user ID for --su.");
                            System.exit(1);
                        }
                        break;
                    case "quit":
                        quit = true;
                        break;
                    default:
                        logError("Unknown command: " + arg);
                        System.exit(1);
                }
            }
        }
        
        // ==================== Check if running tests =======================
        
        if (runTests) {
            
            log("Running tests.");
            
            // Find the user's .anubis/temp data dir
            Path tmpDataDirPath = Paths.get(System.getProperty("user.home"), ".anubis", "temp");
            String tmpDataDir = tmpDataDirPath.toString();
            log("Temporary data directory: " + tmpDataDir);
            
            // Clean the temp dir
            deleteDirectory(tmpDataDirPath);
            
            // Create a server using the temp dir
            createServer(tmpDataDir);
            
            // Run the tests
            Tests.run(server);
            
            // Goodbye
            log("Done running tests.");
            System.exit(0);
        }
        
        // ==================== Create the server ===========================
        
        log("Starting server.");
        
        // Find the user's .anubis/temp data dir
        Path dataDirPath = Paths.get(System.getProperty("user.home"), ".anubis", "data");
        String dataDir = dataDirPath.toString();
        log("Data directory: " + dataDir);
        
        // Check if wiping out the data dir
        if (resetData) {
            deleteDirectory(dataDirPath);
            log("Previous data has been destroyed (--reset_data).");
        }
        
        // Create a server that continues whatever was on the data dir
        createServer(dataDir);
        
        // ==================== Create a keypair, overwrite private key ======
        
        if (generateMasterKeyPath != null) {
            
            log("Generating new master keypair.");
            
            KeyPairGenerator gen = new KeyPairGenerator();
            KeyPair keyPair = gen.generateKeyPair();
            
            log("Writing keypair files.");
            
            writeKeypairFiles(generateMasterKeyPath.toString(), keyPair);
            
            log("Setting the server's master keypair.");
            
            EncodedKeyPair encodedKeyPair = new EncodedKeyPair((EdDSAPrivateKey)keyPair.getPrivate());
            server.setMasterKeypair(encodedKeyPair);
            
            log("Generated master keypair saved to the server database.");
            log("You can regenerate the keypair backup files with --export_master_key <filename_prefix>");
        }
        
        // ==================== Load private key file, overwrite ============

        if (importMasterKeyPath != null) {

            log("Importing master encoded private key from file: " + importMasterKeyPath);
            
            byte[] prkEncoded = Files.readAllBytes(importMasterKeyPath);
            EncodedKeyPair encodedKeyPair = new EncodedKeyPair(prkEncoded);
            
            EdDSAPublicKey puk = (EdDSAPublicKey)encodedKeyPair.getKeypair().getPublic();
            String hexPuk = DatatypeConverter.printHexBinary(puk.getAbyte());
            log("Public key (32-byte hex): " + hexPuk);

            log("Setting the server's master keypair.");

            server.setMasterKeypair(encodedKeyPair);

            log("Imported master keypair saved to the server database.");
        }
            
        // ==================== Save key files from server data =============

        if (exportMasterKeyPath != null) {
            
            log("Fetching the master keypair from the server database.");

            EncodedKeyPair encodedKeyPair = server.getMasterKeypair();
            
            log("Writing keypair files.");
            
            KeyPair keyPair = encodedKeyPair.getKeypair();
            writeKeypairFiles(exportMasterKeyPath.toString(), keyPair);

            log("Finished server key export.");
        }
            
        // ==================== Command: create anchor ======================
        
        if (inviteAnchor) {
            //
            // A negative userId ("Error.OK_NO_SPONSOR") is used to signal 
            //   being invited by the server itself. this makes the user 
            //   an anchor upon accepting the invite, with zero validation 
            //   links pointing to it.
            //
            // The anchor invitation amount is direct money creation. This
            //   is needed to allow e.g. the root anchor to kickstart the
            //   invitation process themselves in-game by paying for the
            //   invitation fees right away (instead of waiting for them
            //   to slowly generate through the UBI payments)
            //
            PendingInvite invite = new PendingInvite(Error.OK_NO_SPONSOR, inviteAnchorAmount, Timestamp.now());
            long invitationCode = server.createPendingInvite(invite);
            log("New anchor invitation created.");
            log("  Amount: " + inviteAnchorAmount);
            log("  Code: " + invitationCode);
        }
        
        // ==================== Command: give anchor status =================
        
        for (int userId : setAnchor) {
            int err = server.setAnchor(userId, true);
            log("--set_anchor " + userId + " result code (0 == OK): " + err);
        }

        // ==================== Command: remove anchor status ===============

        for (int userId : unsetAnchor) {
            int err = server.setAnchor(userId, false);
            log("--unset_anchor " + userId + " result code (0 == OK): " + err);
        }
        
        // ==================== Command: testing ============================
        
        if (fabby) {
            UserAccount acc = new UserAccount();
            acc.name = "Fabiana Reis Cecin";
            acc.profile.add("Born in Porto Alegre, RS, Brazil");
            acc.profile.add("http://twitter.com/fabianacecin");
            acc.profile.add("http://medium.com/@fcecin");
            acc.profile.add("http://facebook.com/fabiana.r.cecin");
            acc.profile.add("fabiana.reis.cecin@gmail.com");
            acc.balance = 1000000 * COIN;
            acc.flags = UserAccount.USER_FLAG_ANCHOR;
            
            PrivateUserAccount pvt = new PrivateUserAccount();
            pvt.emailAddress = "fcecin@gmail.com";
            pvt.password = StoredPassword.hashPassword("a");
            
            int userId = server.createUser(acc, pvt, Error.OK_NO_SPONSOR);
            
            log("New user (--fabby) created with user ID = " + userId);
            
/*            
            // additional tester accounts (hardcoded to userId == 0)
            
            acc.validationIn.add(1);
            acc.validationOut.add(1);
            acc.validationIn.add(2);
            acc.validationOut.add(2);
            
            acc = new UserAccount();
            acc.name = "Joao";
            acc.profile.add("Joao");
            acc.balance = 36 * COIN;
            acc.validationIn.add(0);
            acc.validationOut.add(0);
            acc.validationIn.add(2);
            acc.validationOut.add(2);
            pvt = new PrivateUserAccount();
            pvt.emailAddress = "joao@joao";
            pvt.password = StoredPassword.hashPassword("a");
            server.createUser(acc, pvt, 0);

            acc = new UserAccount();
            acc.name = "Maria";
            acc.profile.add("Maria");
            acc.balance = 36 * COIN;
            acc.validationIn.add(0);
            acc.validationOut.add(0);
            acc.validationIn.add(1);
            acc.validationOut.add(1);
            pvt = new PrivateUserAccount();
            pvt.emailAddress = "maria@maria";
            pvt.password = StoredPassword.hashPassword("a");
            server.createUser(acc, pvt, 0);
*/
        }
        
        // ==================== Command: set super user IDs =================

        if (! superUserIds.isEmpty()) {
            server.setSuperUserIds(superUserIds);
            String msg = "Set specified --su super-user IDs: ";
            for (int userId : superUserIds)
                msg = msg + userId + " ";
            log(msg);
        }
    
        // ==================== Command: take snapshot ======================
        
        if (snapshot)
            server.takeSnapshot();

        // ==================== Quit if we're not going to listen ===========
        
        if (quit) {
            log("Quitting due to --quit flag.");
            System.exit(0);
        }

        // ==================== Start listening to clients ==================
        
        log("Starting RMI Server...");
        startServer();
        log("RMI Server started.");
        log("Server API name: " + RMI_SERVER_NAME);
        log("Server API port: " + RMI_SERVER_PORT);
        log("RMI Registry port: " + RMI_REGISTRY_PORT);
        
        // ==================== Main server loop ============================

        // This is where the main thread goes to die: it is used to run the 
        //   daily data model update task forever.
        // You can quit the server by crashing it (kill -9) without any
        //   loss of data model consistency (user transactions being 
        //   serviced die).
        // The server listens for RMI requests in another thread.
        // There should be an admin backdoor somewhere that interrupts the
        //   mainThread after setting shutdown=true; that will allow the 
        //   server to take a snapshot before exiting, which will speed things
        //   up when it reboots (no need to re-execute all transactions in
        //   Prevayler's transaction log).
        while (! shutdown) {
            
            // Check if it's a new day and advance the simulation if so.
            // (even if we're "fast-forwarding" here due to some dumb 
            //  operational mistake, we can afford the one-minute intervals).
            server.checkTick(false);
            
            // Sleep for one minute, then check again.
            try {
                Thread.sleep(60 * 1000);
            } catch (InterruptedException e) {
            }
        }
        
        // Shutting down...

        // Stop the RMI server
        log("Stopping server...");
        stopServer();
        
        // Take a snapshot.
        log("Taking a snapshot...");
        server.takeSnapshot();

        log("All done. Quitting.");
    }
}
