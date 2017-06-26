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

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.xml.bind.DatatypeConverter;
import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPublicKey;

/**
 * The private part of persisted data (for security features).
 */
public class SecurityDataModel implements Serializable {
    private static final long serialVersionUID = 1L;
    
    // Find user IDs by e-mail address.
    // This is used for logging in with the user's e-mail address.
    HashMap<String, Integer> emailToUserId = new HashMap();

    // Invitation codes generated by the user that haven't been used yet.
    // Each invitation code is "sponsored" and locks an amount of money 
    //   at this user's DataModel UserAccount entry.
    HashMap<Long, PendingInvite> pendingInvites = new HashMap();
    
    // SessionID to the session object.
    // Exactly one entry in here for every entry in userToSessionId.
    // If the session expires, the entry can be removed.
    HashMap<Long, UserSession> userSessions = new HashMap();
    
    // Per-user private data
    HashMap<Integer, PrivateUserAccount> privateAccounts = new HashMap();
    
    // Password reset code to user id.
    HashMap<Long, Integer> passwordResetCodes = new HashMap();
    
    // The server's current master key that signs all burnMoney requests.
    EncodedKeyPair masterKeyPair;
    
    // The server's unique burn receipt ID generator.
    // This only has to be unique per key, but we keep the same count 
    //   even when we change keys because it's simpler that way (don't
    //   have to keep track of keys being reset to reused/old values).
    // AND, as a last resort, external systems should look at the timestamp
    //   of the receipt and reject receipts from the distant future or the 
    //   distant past (e.g. give the user a couple months to post them or 
    //   something like that).
    long burnReceiptUniqueIDGenerator = -1;
    
    // ========================================================================
    
    void deleteAllUserSessions(int userId) {
        PrivateUserAccount privateAccount = privateAccounts.get(userId);
        if (privateAccount != null) {
            userSessions.remove(privateAccount.sessionId);
            privateAccount.sessionId = null;
        }
    }
    
    // ========================================================================
    
    public void setMasterKeypair(EncodedKeyPair keyPair) {
        masterKeyPair = keyPair;
    }

    public EncodedKeyPair getMasterKeypair() {
        return masterKeyPair;
    }
    
    public String getMasterPublicKey()  {
        if (masterKeyPair == null)
            return null;
        try {
            EdDSAPublicKey puk = (EdDSAPublicKey)masterKeyPair.getKeypair().getPublic();
            return DatatypeConverter.printHexBinary(puk.getAbyte());
        } catch (Exception e) {
            Main.logError("getMasterPublicKey", e);
            return null;
        }
    }

    public byte[] signMessageWithMasterKey(byte[] message) {
        if (masterKeyPair == null)
            return null;
        try {
            EdDSAEngine sgr = new EdDSAEngine(MessageDigest.getInstance("SHA-512"));
            sgr.initSign(masterKeyPair.getKeypair().getPrivate());
            return sgr.signOneShot(message);
        } catch (Exception e) {
            Main.logError("signMessageWIthMasterKey", e);
            return null;
        }
    }
    
    public byte[] createBurnReceipt(int userId, long amount, byte[] comment, int nowTimestamp) {
        if (getMasterKeypair() == null)
            return null; //Error.FAILED;
        if (comment.length > Main.MAX_BURN_MONEY_COMMENT_BYTES)
            return null; //Error.LIMIT_REACHED;
        PrivateUserAccount acc = privateAccounts.get(userId);
        if (acc == null)
            return null; //Error.NOT_FOUND;
        try {
            EdDSAPublicKey puk = (EdDSAPublicKey)masterKeyPair.getKeypair().getPublic();
            
            ByteBuffer buf = ByteBuffer.allocate(53 + comment.length);
            buf.put(Main.BURN_RECEIPT_VERSION);
            buf.put(puk.getAbyte());
            buf.putLong(++burnReceiptUniqueIDGenerator);
            buf.putLong(amount);
            buf.putInt(nowTimestamp);
            buf.put(comment);
            byte[] message = buf.array();
            
            byte[] signature = signMessageWithMasterKey(message);
            
            ByteBuffer rbuf = ByteBuffer.allocate(117 + comment.length);
            rbuf.put(signature);
            rbuf.put(message);
            
            acc.burnReceipt = rbuf.array(); 
            return acc.burnReceipt; //Error.OK;
        } catch (Exception e) {
            Main.logError("createBurnReceipt", e);
            return null;
        }
    }
    
    // SET the password reset code.
    // Returns 0 if OK, negative number on error (can't set the code)
    public int getPasswordResetCode(int userId, String emailAddress, long resetCode, int nowTimestamp) {
        int emailUserId = getUserId(emailAddress);
        if (emailUserId < 0)
            return Error.NOT_FOUND;
        if (userId != emailUserId)
            return Error.FORBIDDEN;
        PrivateUserAccount acc = privateAccounts.get(userId);
        if (acc == null)
            return Error.FAILED;
        if (acc.rateLimitExceeded(60, 400, nowTimestamp))
            return Error.TOO_MANY_REQUESTS;
        passwordResetCodes.put(resetCode, userId);
        return Error.OK;
    }
    
    // Reset an user's password using their password reset code.
    public int resetPassword(StoredPassword password, long resetCode) {
        Integer userId = passwordResetCodes.get(resetCode);
        if (userId == null)
            return Error.NOT_FOUND;
        passwordResetCodes.remove(resetCode); // destroy it in any case
        PrivateUserAccount acc = privateAccounts.get(userId);
        if (acc == null)
            return Error.FAILED;
        acc.password = password;
        return Error.OK;
    }
    
    // Get private user page(s) data.
    // Without the password!
    public PrivateUserAccount getPrivateUserAccount(int userId) {
        PrivateUserAccount acc = privateAccounts.get(userId);
        if (acc == null)
            return null;
        return acc.cloneForViewing();
    }
    
    // This OVERWRITES any previous e-mail mapping to user ID!
    // The caller must be SURE they're not allowing someone to set up a 
    //   duplicate e-mail address to this account! (just because the email
    //   address is used both as a login "user" code, and as a password 
    //   recovery tool).
    public void createUser(PrivateUserAccount newPrivateAccount, int newUserId) {
        privateAccounts.put(newUserId, newPrivateAccount);
        // the email address is optional. if it is an empty string, it means
        // we don't map it to the account.
        if (newPrivateAccount.emailAddress.length() > 0)
            emailToUserId.put(newPrivateAccount.emailAddress, newUserId);
    }
    
    // How many pending invitations an user has
    public int getPendingInvitationCount(int userId) {
        PrivateUserAccount acc = privateAccounts.get(userId);
        if (acc == null)
            return Error.NOT_FOUND;
        return acc.pendingInvitationCodes.size();
    }
    
    // create an invitation to another user; code is given. 
    // returns false if can't create the invitation for whatever reason.
    public boolean createPendingInvite(long invitationCode, PendingInvite invite) {
        // invitationCode a randomly-generated number
        // in the extremely unlikely case of a collision, return false
        //   so the caller may try again with a different random code.
        if (pendingInvites.containsKey(invitationCode))
            return false;
        
        PrivateUserAccount privateAccount = privateAccounts.get(invite.sponsorId);
        if (privateAccount == null) {
            if (invite.sponsorId < 0) {
                // The sponsorId is negative, which means "no one."
                // That is a legit case for it not having a profile
            } else {
                // This OTOH is NOT legit. You're trying to create an invitation
                //   that is sponsored by a non-existing user registration.
                // This should never happen. In case it does, you have a bug 
                //   and we just return here.
                return false;
            }
        } else {
            // user gets to know a list of their own issued pending invites
            //   (the ones they are sponsoring).
            privateAccount.pendingInvitationCodes.add(invitationCode);
        }
        
        pendingInvites.put(invitationCode, invite);
        return true;
    }
    
    public PendingInvite getPendingInvite(long invitationCode) {
        return pendingInvites.get(invitationCode);
    }
    
    // Returns the sponsor's userId, or a negative value on error.
    public int checkInvite(long invitationCode, int timestampNow) {
        PendingInvite pendingInvite = pendingInvites.get(invitationCode);
        if (pendingInvite == null)
            return Error.NOT_FOUND;
        pendingInvite.touch(timestampNow);
        return pendingInvite.sponsorId;
    }
    
    public void deletePendingInvite(long invitationCode) {
        PendingInvite invite = pendingInvites.get(invitationCode);
        if (invite != null) {
            // Delete invite at the user account (user's list of 
            //  their own pending invites)
            PrivateUserAccount privateAccount = privateAccounts.get(invite.sponsorId);
            if (privateAccount != null)
                privateAccount.pendingInvitationCodes.remove(invitationCode);
            
            // Delete the invite
            pendingInvites.remove(invitationCode);
        }
    }
    
    // check for cleanups due to time passing
    // (called at least once a day during the server tick())
    // returns a list of expired PendingInvites that have been removed
    public ArrayList<PendingInvite> collectGarbage(ArrayList<Integer> deletedUserIds, int timestampNow) {
        
        ArrayList<PendingInvite> deletedPendingInvites = new ArrayList();

        // Users deleted because they can't pay for their account fees
        deletedUserIds.forEach((userId) -> { deleteUser(userId); });

        Iterator it;
        
        // Delete user invites that expired
        it = pendingInvites.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry pair = (Map.Entry)it.next();
            Long invitationCode = (Long)pair.getKey();
            PendingInvite invite = (PendingInvite)pair.getValue();
            if (invite.isExpired(timestampNow)) {
                
                // Delete invite at the user account (user's list of 
                //  their own pending invites)
                PrivateUserAccount privateAccount = privateAccounts.get(invite.sponsorId);
                if (privateAccount != null)
                    privateAccount.pendingInvitationCodes.remove(invitationCode);

                // So the caller can release minBalances of invites at the DM
                deletedPendingInvites.add(invite);
                
                // Delete the invite
                it.remove();
            }
        }

        // Delete user sessions that expired
        it = userSessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry pair = (Map.Entry)it.next();
            UserSession session = (UserSession)pair.getValue();
            if (session.isExpired(timestampNow))
                it.remove();
        }
        
        // Delete all password reset codes. These things cannot linger.
        // (if an user requested a password reset too near the daily tick, 
        //   then tough luck. just ask for a new code.)
        passwordResetCodes.clear();
        
        return deletedPendingInvites;
    }
    
    public void bindUserIdToEmailAddress(int userId, String emailAddress) {
        // in the unlikely event the user is setting their email address
        //   to one that is already being used in the system (that is, if 
        //   someone is not just fucking with someone else by cloning their
        //   e-mail address) then this call will simply silently return and 
        //   do nothing.
        // TODO: we could (or should) turn this into an error code later.
        if (emailAddress.length() > 0) {
            if (emailToUserId.containsKey(emailAddress)) {
                // either it is mapped to someone else or it is mapped to yourself.
                // in either case, there's nothing to do.
                return;
            }
        }
        
        PrivateUserAccount privateAccount = privateAccounts.get(userId);
        if (privateAccount != null) {
            
            // if address is being set to empty, user can't login by their
            //   previous email address anymore.
            if (emailAddress.length() == 0)
                if (privateAccount.emailAddress.length() > 0)
                    emailToUserId.remove(privateAccount.emailAddress);
            
            privateAccount.emailAddress = emailAddress;

            // map email->ID if the email isn't empty
            if (emailAddress.length() == 0)
                emailToUserId.put(emailAddress, userId);
        }
    }
    
    public void deleteSession(long sessionId) {
        UserSession session = userSessions.get(sessionId);
        if (session != null) {
            deleteAllUserSessions(session.userId);
            userSessions.remove(sessionId);
        }
    }
    
    public boolean setUserSession(int userId, long sessionId, UserSession session) {
        // sessionId is a randomly-generated number
        // in the extremely unlikely case of a collision, return false
        //   so the caller may try again with a different random session 
        //   number.
        if (userSessions.containsKey(sessionId)) 
            return false;
        
        PrivateUserAccount privateAccount = privateAccounts.get(userId);
        
        // why are you trying to set the session of an user
        // that doesn't exist? never happens
        if (privateAccount == null)
            return false; 
        
        deleteAllUserSessions(userId);
        userSessions.put(sessionId, session);
        privateAccount.sessionId = sessionId;
        return true;
    }
    
    public int touchSession(long sessionId, int timestampNow) {
        UserSession session = userSessions.get(sessionId);
        if (session == null)
            return Error.NOT_FOUND;
        if (session.isExpired(timestampNow)) {
            deleteSession(sessionId);
            return Error.EXPIRED;
        }
        session.touch(timestampNow);
        return session.getUserId();
    }
    
    public void deleteUser(int userId) {
        deleteAllUserSessions(userId);
        PrivateUserAccount privateAccount = privateAccounts.get(userId);
        if (privateAccount != null) {
            if (privateAccount.emailAddress.length() > 0)
                emailToUserId.remove(privateAccount.emailAddress);
            for (Long invitationCode : privateAccount.pendingInvitationCodes)
                pendingInvites.remove(invitationCode);
        }
        privateAccounts.remove(userId);
    }
    
    public int getUserId(String emailAddress) {
        Integer id = emailToUserId.get(emailAddress);
        if (id == null)
            return Error.NOT_FOUND;
        return id;
    }
    
    public int setUserPassword(int userId, StoredPassword password) {
        PrivateUserAccount privateAccount = privateAccounts.get(userId);
        if (privateAccount == null)
            return Error.NOT_FOUND;
        privateAccount.password = password;
        return Error.OK;
    }
    
    public boolean testUserPassword(int userId, StoredPassword password) {
        PrivateUserAccount privateAccount = privateAccounts.get(userId);
        if (privateAccount != null)
            return password.equals(privateAccount.password);
        else
            return false;
    }
    
    public String getPasswordSalt(int userId) {
        PrivateUserAccount privateAccount = privateAccounts.get(userId);
        if (privateAccount == null)
            return null;
        return privateAccount.password.getSalt();
    }
}