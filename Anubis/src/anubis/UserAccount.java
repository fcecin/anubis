/*
 * Copyright (C) 2017 The Anubis Project
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
import java.util.ArrayList;
import java.util.HashSet;

/**
 * The one and only account of one human player of the game.
 */
public class UserAccount implements Serializable {
    private static final long serialVersionUID = 1L;

    // Values for the flags field
    public static final short USER_FLAG_AUTHENTIC = 1;    
    public static final short USER_FLAG_ANCHOR = 2;
    
    // 2K is enough to paste a bunch of URLs and provide basic info.
    public static final int PROFILE_STRING_LENGTH_LIMIT = 2048;
    
    // Non-blank lines limit (excess is trimmed)
    public static final int PROFILE_LINES_LIMIT = 15;
    
    // The guiness world record in 2011 was 225 chars
    public static final int NAME_STRING_LENGTH_LIMIT = 256;
    
    // max number of entries in the user's log.
    // we don't want this to be too big for a few reasons:
    // (1) this is to add minimal usability only. it's not for archiving;
    // (2) to simplify the RMI API and the web client (for now), every client 
    //     request that retrieves the private profile will get ALL of the log 
    //     objects;
    // (3) and of course this eats RAM which is used to persist EVERYTHING.
    public static final int LOG_MAX_SIZE = 30;

    // ======================================================================
    
    // The name chosen by the user to present themselves.
    public String name;

    // The person's public profile (8Kb at most, should be enough).
    // Each entry is a "paragraph." An item is either a valid URL, or
    //    some other sort of text.
    public ArrayList<String> profile = new ArrayList();
    
    // Social graph: who I validate.
    // Validation means "I know this person in real life and I can validate 
    //   in real life that this account represents them, as I can find and 
    //   contact them."
    public HashSet<Integer> validationOut = new HashSet();
    
    // Social graph: back-pointers (who validates me)
    public HashSet<Integer> validationIn = new HashSet();

    // A partial, recent transactions log
    public ArrayList<LogEntry> log = new ArrayList();
    
    // Who this user is currently invited to authenticate:
    // - If a negative value, no one.
    // - Otherwise it is (>=0) and is the user ID of another untrusted user
    //   that this trusted user has been invited to vote yes/no on their
    //   authentication process.   
    public int authOtherUserId = -1;
    
    // Who has requested an authentication of this user:
    // - If a negative value, no one (no vote running for this user)
    // - If the ID is the same as this user's ID, then it is the user 
    //   themselves that requested their own authentication (only possible
    //   when their status is still unverified)
    // - Otherwise it is >= 0 and it is the ID of some OTHER user that
    //   challenged this user's Trusted/verified/authenticated status
    //   (this user has to be verified/authenticated/trusted for that 
    //   to be possible).
    public int authSelfUserId = -1;

    // Timestamp this person identity was created.
    public int creationTimestamp;
    
    // Last login timestamp (to detect inactive accounts).
    public int lastLoginTimestamp;

    // Timestamp of last crowdsourced authentication/verified/trusted 
    //   status result. Zero if never.
    public int lastVerificationTimestamp;
    
    // Total points balance in 0.0001's. ("1" point is stored as 10000).
    // Every day, all account balances are reduced by the demurrage charge 
    //    (e.g. 5%/year), then reduced by "0.001" (stored as 1). And if they 
    //    are verified they are then increased by a constant income amount 
    //    (e.g. "33", stored as 330000).
    // An UserAccount receives UBI if the account status = "valid"
    public long balance;
    
    // Minimum balance. This is incremented and decremented by all of the 
    //   pending invites that this user issues and deletes or expire.
    public int minBalance;

    // User auditing level.
    // bit 0: user is successfully audited by the last crowdsourced auditing 
    //        process. (this DOES NOT require a path to the "anchors."; users
    //        merely use path-to-anchor as an heuristic to decide.) 
    //        Audited == Authenticated == Verified == "Trusted (verified)" status
    // bit 1: user is an anchor (flag set by server admins). an anchored
    //        user account is necessarily one of a person that the server 
    //        administration knows in real life and exists, is alive and is
    //        bound and controls that account with 100% certainty 
    //        (e.g. the server admins' own profiles are anchors).
    //        when set, this bit overrides bit 0.
    public short flags;
    
    // ======================================================================
    
    public static ArrayList<String> trimProfile(ArrayList<String> profile) {
        ArrayList<String> pout = new ArrayList();
        int profileSize = 0;
        int i = 0;
        for (; i < profile.size(); i++) {
            String item = profile.get(i);
            item = item.trim(); // Trim individual profile entries 
            if (item.length() == 0) {
                profile.remove(i--); // Remove empty lines
                continue;
            }
            pout.add(item);
            profileSize += item.length();
            if (profileSize > PROFILE_STRING_LENGTH_LIMIT)
                break;
            if (i > PROFILE_LINES_LIMIT)
                break;
        }
        pout.trimToSize();
        return pout;
    }
    
    // ======================================================================
    
    public boolean isAuthentic() { return (flags & USER_FLAG_AUTHENTIC) > 0; }
    public boolean isAnchor() { return (flags & USER_FLAG_ANCHOR) > 0; }
    
    public void setAuthenticFlag() { flags |= USER_FLAG_AUTHENTIC; }
    public void clearAuthenticFlag() { flags &= ~USER_FLAG_AUTHENTIC; }
    
    public void setAnchorFlag() { flags |= USER_FLAG_ANCHOR; }
    public void clearAnchorFlag() { flags &= ~USER_FLAG_ANCHOR; }
    
    // amount of uncommitted, spendable money
    public long getUnlockedBalance() {
        long unlocked = balance - minBalance;
        if (unlocked < 0)
            unlocked = 0; // never happens
        return unlocked;
    }
    
    // number of unique user IDs among validationIn and validationOut
    public HashSet<Integer> getAllValidationLinkUserIds() {
        HashSet<Integer> total = new HashSet();
        total.addAll(validationIn);
        total.addAll(validationOut);
        return total;
    }

    // Truncate collections that have size limits, such as the user's profile.
    // This is called on account creation to trim user-supplied information.
    public void trim() {
        profile = trimProfile(profile);
        name = name.trim();
        if (name.length() > NAME_STRING_LENGTH_LIMIT)
            name = name.substring(0, NAME_STRING_LENGTH_LIMIT);
    }

    public void log(LogEntry entry) {
        while (log.size() > LOG_MAX_SIZE - 1)
            log.remove(0);
        log.add(entry);
    }
}
