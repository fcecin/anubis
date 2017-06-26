/*
 * Copyright (C) 2017 user
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package anubis;

import anubis.tx.VoteTrustTx;
import anubis.tx.RequestTrustTx;
import anubis.tx.*;
import anubis.sectx.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import org.prevayler.Prevayler;
import org.prevayler.PrevaylerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * An instance of an UBI server.
 */
public class Server implements ServerInterface {
    
    // All fields should be synchronized since we have the main thread 
    //   and the potentially several RMI threads competing for the server.
    
    // The core data model. All of this is public and snapshots are made 
    //   available daily as .torrent downloads
    Prevayler<DataModel> dm;
        
    // The security data model. This is private stuff to the server that is 
    //   used to implement authentication and other security features.
    Prevayler<SecurityDataModel> secdm;
    
    // Set as server is just booted up (parsing command-line args)
    Set<Integer> superUserIds = Collections.synchronizedSet(new HashSet());
    
    //===================================================================
    
    // Create an ANUBIS server.
    public Server(String dataDir) throws Exception {
        
        // restore the data models if any
        Path prevalenceBaseDM = Paths.get(dataDir, "/dm");
        Path prevalenceBaseSecDM = Paths.get(dataDir, "/secdm");
        dm = PrevaylerFactory.createPrevayler(new DataModel(), prevalenceBaseDM.toString());
        secdm = PrevaylerFactory.createPrevayler(new SecurityDataModel(), prevalenceBaseSecDM.toString());

        // debugging
        int dmDay = dm.execute(new GetEpochDay());
        int currentDay = EpochDay.now();
        Main.log("Restored DataModel epoch day is " + dmDay + " and today's is " + currentDay);
    }
    
    //===================================================================
    // Internal (local) methods 
    //===================================================================
    
    public void setMasterKeypair(EncodedKeyPair keyPair) throws Exception {
        secdm.execute(new SetMasterKeypairTx(keyPair));
    }

    public EncodedKeyPair getMasterKeypair() throws Exception {
        return secdm.execute(new GetMasterKeypair());
    }
    
    public int setAnchor(int userId, boolean set) {
        try {
            return dm.execute(new SetAnchorStatusTx(userId, set));
        } catch (Exception e) {
            Main.logError("setAnchor", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
    }
    
    public void setSuperUserIds(HashSet<Integer> superUserIds) {
        this.superUserIds.addAll(superUserIds);
    }
    
    // advance the stored server day.
    // callers must be pretty sure of what they're doing -- it is the caller 
    //   that will check whether wall-clock time (or test time, or whatever
    //   is going on) warrants executing an increment of the server's epochDay
    //   simulation timestamp.
    //
    public void tick() {
        try {
            
            // Tick
            ArrayList<Integer> deletedUserIds = 
                    dm.execute(new TickTx());
            
            //Main.log("DeletedUserIDS = " + Arrays.toString(deletedUserIds.toArray()));
            
            // Secdm GC
            ArrayList<PendingInvite> deletedPendingInvites = 
                    secdm.execute(new CollectGarbageTx(deletedUserIds));
            
            //Main.log("DeletedPendingInvites = " + Arrays.toString(deletedPendingInvites.toArray()));
            
            // Final step: release minBalances due to expired pendingInvites
            dm.execute(new ReleaseMinBalancesTx(deletedPendingInvites));
            
        } catch (Exception e) {
            Main.logError("FATAL: Server.tick() failed. Aborting.", e);
            System.exit(1);
        }
    }
    
    // force a snapshot taken
    public void takeSnapshot() throws Exception {
        dm.takeSnapshot();
        secdm.takeSnapshot();
    }
    
    // Advance the monetary simulation by one step if it is past the 
    //  (wall-clock) time to do so.
    public void checkTick(boolean force) {
        try {
            int dmDay = dm.execute(new GetEpochDay());
            int currentDay = EpochDay.now();
            if (force || (dmDay < currentDay)) {
                
                if (force)
                    Main.log("The server day is being FORCED to advance.");
                
                Main.log("Server tick: Starting. dmDay: " + dmDay + ", currentDay: " + currentDay);
                
                // Advance simulation time
                tick();
                
                Main.log("Server tick: Data model updated. Snapshotting...");
                
                // Snapshot the Prevaylers
                //
                // FIXME/TODO: publish the snapshots for clients
                //  (probably to an auxiliary bittorrent tracker/seed machine).
                //  this is for limited auditing, but MAINLY for allowing the
                //  service to be replicated elsewhere (ledger state as a 
                //  public good).
                // If we want FULL public auditability from day one of the 
                //  simulation history, a la blockchain, then we need to also 
                //  publish the full prevayler transaction logs for each step.
                //
                dm.takeSnapshot();
                secdm.takeSnapshot();
                
                int updatedDMDay = dm.execute(new GetEpochDay());
                
                Main.log("Server tick: Snapshot completed, done. dmDay: " + dmDay + " -> " + updatedDMDay + ", currentDay: " + currentDay);
            }
        } catch (Exception e) {
            // FIXME/TODO
            //e.printStackTrace();
            //System.exit(1);
        }
    }

    // insert an invitation into the securitydatamodel, returning the 
    //   invitation code allocated.
    // returns a negative value on error, in which case the invite was NOT
    //   created for whatever reason.
    public long createPendingInvite(PendingInvite pendingInvite) {
        
        // Try until we can generate an invitation key that isn't a duplicate.
        boolean success = false;
        long invitationCode;
        do {
            // Get a random positive number to use as invitation key.
            do { invitationCode = Main.getSecureRandom().nextLong(); } while (invitationCode < 0);
            
            // Try to put it in
            try {
                success = secdm.execute(new CreatePendingInviteTx(invitationCode, pendingInvite));
            } catch (Exception e) {
                Main.logError("createPendingInvite", e);
                return Error.EXCEPTION_NEVER_HAPPENS;
            }
        } while(! success);
        
        return invitationCode;
    }
    
    public int createUser(UserAccount account, PrivateUserAccount privateAccount, int sponsorId) throws Exception {
        
        // Trim the supplied e-mail address field
        privateAccount.emailAddress = privateAccount.emailAddress.trim();
        
        // The given e-mail can be an empty string. In that case, the user 
        //   has NO email address set and they can't log in by e-mail address
        //   and instead have to inform their user ID #.
        if (privateAccount.emailAddress.length() > 0) {
            // Check the e-mail isn't a dupĺicate.
            int existingUserID = secdm.execute(new GetUserIdFromEmail(privateAccount.emailAddress));
            if (existingUserID >= 0)
                return Error.ALREADY_EXISTS; // that email is already taken, sorry
        }
        
        // Prevent spam by users (big names or profiles). RAM is precious.
        account.trim();
        
        // Don't accept empty names
        if (account.name.length() == 0)
            return Error.EMPTY_NAME;
        
        // Don't accept empty profiles
        if (account.profile.isEmpty())
            return Error.EMPTY_PROFILE;        
        
        // Set the creation time to the current server clock if it was not
        //   already set by e.g. test code
        if (account.creationTimestamp == 0)
            account.creationTimestamp = Timestamp.now();
        account.lastLoginTimestamp = account.creationTimestamp;
        
        // Put user in, get the ID allocated to it
        int userId = dm.execute(new anubis.tx.CreateUserTx(account, sponsorId));
        
        // Create the private part of the user account
        secdm.execute(new anubis.sectx.CreateUserTx(privateAccount, userId));
        
        return userId;
    }
    
    // returns an userId for a given sessionId, or a negative value if the 
    //   session just expired or was already unexistent.
    public int touchSession(long sessionId) {
        try {
            return secdm.execute(new TouchSessionTx(sessionId));
        } catch (Exception e) {
            Main.logError("touchSession", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
    }
    
    //===================================================================
    // ServerInterface (the server remote API) - Tests (REMOVE)
    //===================================================================
/*
    @Override 
    public void forceTick() {
        Main.log("Forcing server tick.");
        checkTick(true);
    }
*/    
    //===================================================================
    // ServerInterface (the server remote API) - Admin backdoors
    //===================================================================

    @Override
    public int shutdown(long sessionId) {
        int userId = touchSession(sessionId);
        if (userId < 0)
            return userId; // error code
        
        if (! superUserIds.contains(userId))
            return Error.FORBIDDEN;
        
        // signal the main thread to wake up and stop the server.
        try {
            Main.requestServerShutdown();
        } catch (Exception e) {
            Main.logError("shutdown", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
        
        return Error.OK;
    }
    
    @Override
    public int setAnchor(long sessionId, int userId, boolean set) {
        int sessionUserId = touchSession(sessionId);
        if (sessionUserId < 0)
            return sessionUserId; // error code
        
        if (! superUserIds.contains(sessionUserId))
            return Error.FORBIDDEN;
        
        try {
            dm.execute(new SetAnchorStatusTx(userId, set));
        } catch (Exception e) {
            Main.logError("shutdown", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
        
        return Error.OK;
    }
    

    //===================================================================
    // ServerInterface (the server remote API) - Unauthenticated requests
    //===================================================================
    
    @Override
    public UserAccount getUserPublicPage(int userId) {
        try {
            return dm.execute(new GetUserAccount(userId));
        } catch (Exception e) {
            Main.logError("getUserPublicPage", e);
            return null;
        }
    }
    
    @Override
    public HashMap<Integer, String> getUserNames(HashSet<Integer> userIds) {
        try {
           return dm.execute(new GetUserNames(userIds));
        } catch (Exception e) {
            Main.logError("getUserNames", e);
            return null;
        }
    }

    @Override
    public String getUserName(int userId) {
        try {
            HashSet<Integer> dummy = new HashSet();
            dummy.add(userId);
            HashMap<Integer, String> userNames = dm.execute(new GetUserNames(dummy));
            return userNames.get(userId);
        } catch (Exception e) {
            Main.logError("getUserName", e);
            return null;
        }
    }
    
    @Override
    public ServerStats getServerStats() {
        try {
            return dm.execute(new GetServerStats());
        } catch (Exception e) {
            Main.logError("getServerStats", e);
            return null;
        }
    }
    
    @Override
    public String getPublicKey() {
        try {
            return secdm.execute(new GetMasterPublicKey());
        } catch (Exception e) {
            Main.logError("getPublicKey", e);
            return null;
        }
    }
    
    
    @Override
    public int checkInvite(long invitationCode) {
        try {
            return secdm.execute(new CheckInviteTx(invitationCode));
        } catch (Exception e) {
            Main.logError("checkInvite", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
    }

    @Override
    public int acceptInvite(long invitationCode, String emailAddress, 
            StoredPassword password, String name, ArrayList<String> profile) 
    {
        try {
            // See if the invitation code exists
            PendingInvite pendingInvite = secdm.execute(new GetPendingInvite(invitationCode));
            if (pendingInvite == null)
                return Error.NOT_FOUND;
            int sponsorId = pendingInvite.getSponsorId();
            long inviteAmount = pendingInvite.getAmount();

            // Set up a new account object
            UserAccount account = new UserAccount();
            account.name = name;
            account.profile = profile;
            if (sponsorId < 0)  { // server invite: direct credit (money creation)
                account.balance = inviteAmount;
                account.setAnchorFlag(); // invited user is permanently audited
            }
            
            // Private part of the account
            PrivateUserAccount privateAccount = new PrivateUserAccount();
            privateAccount.emailAddress = emailAddress;
            privateAccount.password = password;

            // inner method that does all the dirty work
            int newUserId = createUser(account, privateAccount, sponsorId);
            if (newUserId < 0)
                return newUserId; // some error (duplicate email, etc.)
            
            // Destroy the invitation code
            secdm.execute(new DeletePendingInviteTx(invitationCode));
            
            // If there is an actual user sponsor, must send the locked funds 
            //   from them to the invited user; and when doing so, must also 
            //   reduce the minBalance of the sender by the same amount as 
            //   the funds transferred.
            // The transfer is of at most "amount"; if by any chance there's 
            //   not enough funds, then send everything.
            // If any error occurs, we don't care. The account is created, and 
            //   we did our best to fund it.
            //
            if (sponsorId >= 0)
                dm.execute(new SendMoneyTx(sponsorId, newUserId, inviteAmount, false, true));

            // Finally, return the user ID of the created user, FWIW.
            // (user can log in by their informed email address instead)
            return newUserId;
            
        } catch (Exception e) {
            Main.logError("acceptInvite", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
    }
    
    @Override
    public int cancelInvite(long invitationCode) {
        try {
            // Find the pending invite object
            PendingInvite pendingInvite = secdm.execute(new GetPendingInvite(invitationCode));
            if (pendingInvite == null)
                return Error.NOT_FOUND;
            
            // First, unlock the user's balance at the DataModel
            ArrayList<PendingInvite> dummy = new ArrayList();
            dummy.add(pendingInvite);
            dm.execute(new ReleaseMinBalancesTx(dummy));
            
            // Then destroy the invitation code
            secdm.execute(new DeletePendingInviteTx(invitationCode));
            
            return Error.OK;
        } catch (Exception e) {
            Main.logError("cancelInvite", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
    }

    @Override
    public long getPasswordResetCode(int userId, String emailAddress) {
        try {
            // Get a random positive number to use as a password reset code.
            long resetCode;
            do { resetCode = Main.getSecureRandom().nextLong(); } while (resetCode < 0);
            
            int err = secdm.execute(new GetPasswordResetCodeTx(userId, emailAddress, resetCode));
            if (err != Error.OK)
                return err;
            
            return resetCode;
        } catch (Exception e) {
            Main.logError("getPasswordResetCode", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
    }
    
    @Override
    public int resetPassword(StoredPassword password, long resetCode) {
        try {
            return secdm.execute(new ResetPasswordTx(password, resetCode));
        } catch (Exception e) {
            Main.logError("resetPassword", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
    }
    
    
    @Override
    public int getUserId(String emailAddress) {
        try {
            return secdm.execute(new GetUserIdFromEmail(emailAddress));
        } catch (Exception e) {
            Main.logError("getUserId", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
    }
    
    @Override
    public String getPasswordSalt(int userId) {
        try {
            return secdm.execute(new GetPasswordSalt(userId));
        } catch (Exception e) {
            Main.logError("getPasswordSalt", e);
            return null;
        }
    }
    
    @Override
    public boolean authenticate(int userId, StoredPassword password) {
        try {
            return secdm.execute(new TestUserPassword(userId, password));
        } catch (Exception e) {
            Main.logError("authenticate", e);
            return false;
        }
    }
        
    @Override
    public long login(int userId, StoredPassword password) {
        try {
            UserSession session = new UserSession(userId);
            
            // Get a random positive number to use as a session ID.
            long sessionId;
            do { sessionId = Main.getSecureRandom().nextLong(); } while (sessionId < 0);
            
            if (secdm.execute(new LoginTx(userId, password, sessionId, session))) {
                dm.execute(new TouchLoginTimestampTx(userId));
                return sessionId;
            } else {
                return Error.FAILED;
            }
        } catch (Exception e) {
            Main.logError("login", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
    }
    
    //===================================================================
    // ServerInterface (the server remote API) - Authenticated requests
    //===================================================================

    @Override
    public void logout(long sessionId) {
        try {
            secdm.execute(new LogoutTx(sessionId));
        } catch (Exception e) {
            Main.logError("logout", e);
        }
    }
    
    @Override
    public int deleteAccount(long sessionId) {
        int fromUserId = touchSession(sessionId);
        if (fromUserId < 0)
            return fromUserId; // Error code.
        
        try {
            int err = dm.execute(new anubis.tx.DeleteUserTx(fromUserId));
            if (err == Error.OK)
                secdm.execute(new anubis.sectx.DeleteUserTx(fromUserId));
            return err;
        } catch (Exception e) {
            Main.logError("deleteAccount", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
    }
    
    @Override
    public SessionInfo getSessionInfo(long sessionId) {

        int userId = touchSession(sessionId);
        if (userId < 0)
            return null; // Some error (FIXME/TODO userId is an error code)

        try {
            return dm.execute(new GetUserInfo(userId));
        } catch (Exception e) {
            Main.logError("getSessionInfo", e);
            return null;
        }
    }
    
    @Override
    public UserPrivatePage getUserPrivatePage(long sessionId) {
        
        int userId = touchSession(sessionId);
        if (userId < 0)
            return null; // Some error (FIXME/TODO userId is an error code)
                
        UserPrivatePage page = new UserPrivatePage();
        page.userId = userId;
        
        try {
            page.account = dm.execute(new GetUserAccount(userId));
            page.privateAccount = secdm.execute(new GetPrivateUserAccount(userId));
        } catch (Exception e) {
            Main.logError("getUserPrivatePage", e);
            return null;
        }
        
        return page;
    }

    @Override
    public int editPersonalInfo(long sessionId, String name, String emailAddress, 
            ArrayList<String> profile) 
    {
        int userId = touchSession(sessionId);
        if (userId < 0)
            return userId;
        
        try {
            profile = UserAccount.trimProfile(profile);
            name = name.trim();
            int err = dm.execute(new EditPersonalInfoTx(userId, name, profile));
            if (err != Error.OK)
                return err;
            
            emailAddress = emailAddress.trim();
            secdm.execute(new BindUserIdToEmailTx(userId, emailAddress));
            return Error.OK;
        } catch (Exception e) {
            Main.logError("editPersonalInfo", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
    }
    
    @Override
    public int changePassword(long sessionId, StoredPassword newPassword) {

        int userId = touchSession(sessionId);
        if (userId < 0)
            return userId;
        
        try {
            return secdm.execute(new SetUserPasswordTx(userId, newPassword));
        } catch (Exception e) {
            Main.logError("changePassword", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
    }

    @Override
    public long sendMoney(long sessionId, int toUserId, long amount, boolean exact) {
        
        int fromUserId = touchSession(sessionId);
        if (fromUserId < 0)
            return fromUserId; // Error code.
        
        if (amount <= 0)
            return Error.INVALID_AMOUNT;
        
        try {
            return dm.execute(new SendMoneyTx(fromUserId, toUserId, amount, exact, false));
        } catch (Exception e) {
            Main.logError("sendMoney", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
    }
    
    @Override
    public byte[] burnMoney(long sessionId, long amount, final byte[] comment) {

        int userId = touchSession(sessionId);
        if (userId < 0)
            return null;
        
        try {
            // check that we have a master key configured
            if (secdm.execute(new GetMasterKeypair()) == null)
                return null;
            
            // burn money
            int err = dm.execute(new BurnMoneyTx(userId, amount));
            if (err != Error.OK)
                return null;
            
            // generate and replace the users' saved receipt
            byte[] receipt = secdm.execute(new CreateBurnReceiptTx(userId, amount, comment));
            if (err != Error.OK) {
                // if failed receipt/sign (shouldn't ever), 
                //   unburn money tx (
                dm.execute(new UnburnMoneyTx(userId, amount));
                return null;
            }
            return receipt;
        } catch (Exception e) {
            Main.logError("burnMoney", e);
            return null;
        }
    }    
    
    @Override
    public long createInvite(long sessionId) {

        int fromUserId = touchSession(sessionId);
        if (fromUserId < 0)
            return fromUserId; // Error code.
        
        // Currently, all invites use a server-set sponsorship amount.
        long amount = Main.MIN_INVITE_AMOUNT;
        
        try {
            // Check if we have more than the invitation limit at the secdm
            //  for this user.
            int pendingInviteCount = secdm.execute(new GetPendingInvitationCount(fromUserId));
            if (pendingInviteCount >= Main.MAX_PENDING_INVITES)
                return Error.LIMIT_REACHED;      
            
            int errorCode = dm.execute(new CreateInviteTx(fromUserId, amount));
            if (errorCode == Error.OK) {
                PendingInvite pendingInvite = new PendingInvite(fromUserId, amount, Timestamp.now());
                long invitationCode = createPendingInvite(pendingInvite);
                if (invitationCode < 0) {
                    // Never happens, but in case it does, we need to undo
                    //  the locking of funds for this DOA pendingInvite.
                    ArrayList<PendingInvite> dummy = new ArrayList();
                    dummy.add(pendingInvite);
                    dm.execute(new ReleaseMinBalancesTx(dummy));
                }
                return invitationCode;
            }
            else
                return errorCode;
        } catch (Exception e) {
            Main.logError("createInvite", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
    }
    
    @Override
    public int addValidation(long sessionId, int linkUserId) {
        int fromUserId = touchSession(sessionId);
        if (fromUserId < 0)
            return fromUserId; // Error code.
        
        try {
            return dm.execute(new AddValidationTx(fromUserId, linkUserId));
        } catch (Exception e) {
            Main.logError("addValidation", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
    }

    @Override
    public int removeValidation(long sessionId, int linkUserId, 
            boolean inbound, boolean outbound)
    {
        int fromUserId = touchSession(sessionId);
        if (fromUserId < 0)
            return fromUserId; // Error code.
        
        try {
            return dm.execute(new RemoveValidationTx(fromUserId, linkUserId, inbound, outbound));
        } catch (Exception e) {
            Main.logError("removeValidation", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
    }
    
    @Override
    public ValidationCheck checkValidation(long sessionId, int linkUserId) {
        int fromUserId = touchSession(sessionId);
        if (fromUserId < 0)
            return null; // Error code. FIXME/TODO: log somewhere?
        
        try {
            return dm.execute(new CheckValidation(fromUserId, linkUserId));
        } catch (Exception e) {
            Main.logError("checkValidation", e);
            return null;
        }
    }

    @Override
    public int requestTrust(long sessionId) {
        int fromUserId = touchSession(sessionId);
        if (fromUserId < 0)
            return fromUserId; // Error code;
        
        try {
            return dm.execute(new RequestTrustTx(fromUserId));
        } catch (Exception e) {
            Main.logError("requestTrust", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
    }

    @Override
    public int voteTrust(long sessionId, boolean vote) {
        int fromUserId = touchSession(sessionId);
        if (fromUserId < 0)
            return fromUserId; // Error code;
        
        try {
            return dm.execute(new VoteTrustTx(fromUserId, vote));
        } catch (Exception e) {
            Main.logError("voteTrust", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
    }    
    
    @Override
    public int challengeTrust(long sessionId, int userId) {
        int fromUserId = touchSession(sessionId);
        if (fromUserId < 0)
            return fromUserId; // Error code;

        try {
            return dm.execute(new ChallengeTrustTx(fromUserId, userId));
        } catch (Exception e) {
            Main.logError("challengeTrust", e);
            return Error.EXCEPTION_NEVER_HAPPENS;
        }
    }
}
