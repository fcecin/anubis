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

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * This is the public API of the Anubis server.
 * This RMI interface is used by a web server to talk to the Anubis server.
 * The end-users talk to a web server, and the web server translates each 
 *   user request to one or more calls to the Anubis API.
 */
public interface ServerInterface extends Remote {
    
    // ===== Testing (REMOVE) ================================================

    // Force a day advance (testing)
    //void forceTick() throws RemoteException;
    
    // ===== Admin backdoors =================================================
    
    // Shut down the server
    int shutdown(long sessionId) throws RemoteException;

    // Set an user's anchor status
    int setAnchor(long sessionId, int userId, boolean set) throws RemoteException;
    
    // ===== Unauthenticated requests ========================================
    
    // Get an user's public profile
    UserAccount getUserPublicPage(int userId) throws RemoteException;
    
    // Get user names given user IDs
    HashMap<Integer, String> getUserNames(HashSet<Integer> userIds) throws RemoteException;
    
    // Get user name given user ID (null if not found)
    String getUserName(int userId) throws RemoteException;
    
    // Get generic server stats
    ServerStats getServerStats() throws RemoteException;
    
    // Return the server's Ed25519 public master key (used to sign burnMoney() 
    //   receipts) as an hex string, or null if no master keypair configured.
    String getPublicKey() throws RemoteException;
    
    // Check whether an invitation code is still valid.
    // This also bumps the expiration of the invite sufficiently into the 
    //   future if it is about to expire.
    // Return the sponsor user's userId or a negative value on error.
    int checkInvite(long invitationCode) throws RemoteException;

    // Someone accepts an invitation code to create their user account
    // Returns the User ID of the created user, or a negative value on error.
    int acceptInvite(long invitationCode, String emailAddress, 
            StoredPassword password, String name, ArrayList<String> profile) 
            throws RemoteException;

    // Delete an invitation code.
    // Returns Error code (0 == no error).
    int cancelInvite(long invitationCode) throws RemoteException;
    
    // Request a password reset code for the given userId and e-mail address.
    long getPasswordResetCode(int userId, String emailAddress) throws RemoteException;
    
    // Reset user password.
    int resetPassword(StoredPassword password, long resetCode) throws RemoteException;
    
    // Get the userId for a given e-mail address so we can log in users
    //   using their password-recovery e-mail address on file.
    int getUserId(String emailAddress) throws RemoteException;
    
    // Get the salt of a password associated with an userId
    //   (so the web server can hash login requests)
    String getPasswordSalt(int userId) throws RemoteException;
    
    // Authenticate an user (without logging them into a session).
    boolean authenticate(int userId, StoredPassword password) throws RemoteException;

    // Log in presenting userId number and password, get a session ID.
    // You can only have one session at a time, so this will destroy any 
    //   previous session.
    long login(int userId, StoredPassword password) throws RemoteException;
    
    // ===== Authenticated requests ==========================================
    
    // Log out from a session ID.
    void logout(long sessionId) throws RemoteException;
    
    // Delete your own account. Return 0==OK or negative error code.
    int deleteAccount(long sessionId) throws RemoteException;
    
    // Get basic info given a session Id
    SessionInfo getSessionInfo(long sessionId) throws RemoteException;
    
    // Get a logged-in user's page
    UserPrivatePage getUserPrivatePage(long sessionId) throws RemoteException;
    
    // Change name, email, profile
    // Return negative error code or (0) if OK.
    int editPersonalInfo(long sessionId, String name, String emailAddress, 
            ArrayList<String> profile) throws RemoteException;
    
    // Change password
    // Return negative error code or (0) if OK.
    int changePassword(long sessionId, StoredPassword newPassword) throws RemoteException;
    
    // Send money to another user
    // Returns amount actually sent or a negative value on error.
    // if exact is true, the send fails if there's not enough funds instead
    //   of sending the entire balance.
    long sendMoney(long sessionId, int toUserId, long amount, boolean exact) throws RemoteException;
    
    // Burn money, signing a message with server's master key stating that 
    //   X amount of money has been burnt, at a given time, with a given 
    //   attached comment--e.g. the "destination address" in some other system).
    // FIXME/TODO: User pays 1 satoshi per byte in the comment as extra fee, plus whatever 
    //   the sendMoney transaction fee is.
    // Returns the binary/serialized burn receipt.
    byte[] burnMoney(long sessionId, long amount, final byte[] comment) throws RemoteException;
    
    // Create an invitation to another user.
    // Return the invitation code or a negative number on some error.
    // The invitation code has to be sent to that other user somehow (that's
    //  up to the client(web server) to assist the user with).
    long createInvite(long sessionId) throws RemoteException;
    
    // User add validation link to another user
    // Return Error.OK (0) if all OK, or a negative error code on error.
    int addValidation(long sessionId, int linkUserId) throws RemoteException;
        
    // User remove validation link to another user
    // Return Error.OK (0) if all OK, or a negative error code on error.
    int removeValidation(long sessionId, int linkUserId, 
            boolean inbound, boolean outbound) throws RemoteException;

    // User check validation link to another user.
    // Returns null on error (also if you shouldn't validate that user e.g.
    //   it is yourself).
    ValidationCheck checkValidation(long sessionId, int linkUserId) throws RemoteException;
    
    // User starts an authentication referendum for their profile by paying 
    //   for it from their account.
    // Returns 0 (Error.OK) if everything went well, or a negative error code
    //   otherwise.
    int requestTrust(long sessionId) throws RemoteException;
    
    // User votes in the authentication referendum for which they were 
    //   selected to participate.
    // Returns 0 (Error.OK) if everything went well, or a negative error code
    //   otherwise.
    int voteTrust(long sessionId, boolean vote) throws RemoteException;
    
    // Allows any user to trigger the re-verification of any other account 
    //   that is currently set to "trusted" by paying a server-determined 
    //   large fee.
    // Returns 0 (Error.OK) if everything went well, or a negative error code
    //   otherwise (e.g. not enough funds,).
    int challengeTrust(long sessionId, int userId) throws RemoteException;
}


