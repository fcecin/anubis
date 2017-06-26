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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * This is the 100% PUBLIC, core data model of the system. 
 * Everything persisted here is snapshotted regularly to a file and 
 *    made downloadable by anyone interested in auditing the system.
 */
public class DataModel implements Serializable {
    private static final long serialVersionUID = 1L;
    
    // Configuration option: maximum number of valid accounts.
    // Plan: start with 1M max, eventually try to allow for 0.1% of 
    //   humanity (7.5M). 1M should be around 16GB of RAM for UserAccounts.
    public static final int MAX_USER_ACCOUNTS = 1000000;
    
    // reserved internal account numbers: [0..MIN-1)
    public static final int MIN_INTERNAL_ACCOUNT_ID = 65536;
    
    // Permanent internal account where all fees are sent to.
    public static final int SERVER_ACCOUNT_ID = 0;

    // Account that holds the crowdsourced authentication reward funds.
    public static final int AUTH_FUNDS_ACCOUNT_ID = 1;
    
    // ========================================================================
    
    // Days (elapsed since the Epoch) represented by the data model.
    // Whenever a day change is detected in wall-clock time, this is 
    //   incremented and the economy is updated: all validated users 
    //   receive their daily UBI deposit, and the demurrage charge 
    //   and account maintenance fees are applied to all deposits.
    int epochDay = EpochDay.now();
    
    // Generates unique UserAccount.id values.
    // This can wrap around, and unused IDs of accounts deleted/collected 
    //    can be reused. We won't manage more than 2 billion accounts 
    //    simultaneously: if we run out of IDs, we have run out of space!
    int userAccountIDGenerator = -1;
    
    // All user records by user ID.
    // IDs are used in the verify/verifiedby graph!
    HashMap<Integer, UserAccount> accounts = new HashMap();

    // Internal accounts. These are accounts created by the server to handle 
    //   temporary deposits that may be then moved to different locations.
    //   E.g. user invite deposits can either go to the invited user if they
    //   register, or they can go back to the user that invited them if they
    //   don't register.
    // These accounts are deleted when their value is zero during the daily
    //   snapshotting cleanup.
    HashMap<Integer, Balance> internalAccounts = new HashMap();
    
    // Ongoing pending authentications of an user ID
    HashMap<Integer, PendingAuthentication> pendingAuthentications = new HashMap();
    
    // Generates internal account IDs.
    int internalAccountIDGenerator = MIN_INTERNAL_ACCOUNT_ID;

    // Cache for some global statistics
    long totalMoney; // FIXME/TODO: update it upon money burn
    long totalTx;
    int totalTrusted; // users
    int totalDays; // tick counter
    // Transaction counter last 24 hours
    // FIXME: this is persisted and thus doesn't really work when there are 
    //   jumps in the execution time (server shutdown and restore multiple 
    //   hours later.
    //   you can't make these transient as java doesn't initialize transient
    //   fields on deserialization. that could only be fixed by overriding
    //   readObject/writeObject. I may do that but I don't want to right now.
    HashMap<Integer, Long> txCount = new HashMap();
    int txCountLastHour = -1;

    //==================================================================
    
    void countTx() {
        ++totalTx;
        
        int currentHour = (int)(Instant.now().getEpochSecond() / 3600) % 24;
        if (currentHour != txCountLastHour) {
            txCountLastHour = currentHour;
            txCount.put(currentHour, 1L);
        } else {
            Long prevCount = txCount.get(currentHour);
            if (prevCount == null)
                prevCount = 0L; // whatever. never happens.
            txCount.put(currentHour,  prevCount + 1);
        }
    }

    // Charge an useraccount with a server fee
    long chargeFee(UserAccount acc, int internalAccountId, short logTxCode, 
            long amount, int userId, int nowTimestamp, boolean exact, boolean ignoreLocked) 
    {
        long sourceBalance;
        if (ignoreLocked)
            sourceBalance = acc.balance;
        else
            sourceBalance = acc.getUnlockedBalance();
        long charge = Math.min(sourceBalance, amount);
        if ((charge <= 0) || (exact && (charge < amount)))
            return 0;
        acc.balance -= charge;
        sendMoneyToInternalAccount(internalAccountId, charge);
        acc.log(new LogEntry(nowTimestamp, logTxCode, -amount, userId));
        return charge;
    }
    
    long chargeFee(UserAccount acc, int internalAccountId, short logTxCode, 
            long amount, int nowTimestamp, boolean exact, boolean ignoreLocked) 
    {
        return chargeFee(acc, internalAccountId, logTxCode, amount, -1, nowTimestamp, exact, ignoreLocked);
    }
    
    
    long payUserFromInternalAccount(int internalAccountId, UserAccount acc, long amount, short logCode, int userId, int nowTimestamp) {
        Balance bal = getInternalAccount(internalAccountId);
        long prevBal = bal.get();
        long charge = Math.min(prevBal, amount);
        bal.set(prevBal - charge);
        acc.balance += charge;
        acc.log(new LogEntry(nowTimestamp, logCode, charge, userId));
        return charge;
    }

    long payUserFromInternalAccount(int internalAccountId, UserAccount acc, long amount, short logCode, int nowTimestamp) {
        return payUserFromInternalAccount(internalAccountId, acc, amount, logCode, -1, nowTimestamp);
    }

    // used to implement both self trust request and trust challenge by others
    // source is user requesting the trust election
    // target is user that should be set to trusted/untrusted
    // when source==target, it is a self request to go from untrusted to trusted
    //   (reverse is invalid in this case)
    // when source!=target, it is a challenge to attempt to set someone else's status 
    //   from trusted to untrusted (reverse is invalid in this case)
    // the fees, txcodes and number of voters involved are different in the two 
    //   cases, but the overall logic has a lot of common code, hence this method.
    int doRequestTrust(int sourceUserId, int targetUserId, int nowTimestamp) 
    {
        int minVoters, totalVoters;
        long perVoteFee;
        short logTxCode;
        if (sourceUserId == targetUserId) {
            // self request
            minVoters = 1;
            totalVoters = Main.AUTH_TOTAL_VOTES;
            perVoteFee = Main.AUTH_PER_VOTE_FEE;
            logTxCode = LogEntry.ACCOUNT_AUTHENTICATION_FEE;
        } else {
            // other's challenge
            minVoters = Main.AUTH_TOTAL_VOTES;
            totalVoters = Main.AUTH_TOTAL_VOTES;
            perVoteFee = Main.AUTH_CHALLENGE_PER_VOTE_FEE;
            logTxCode = LogEntry.ACCOUNT_AUTH_CHALLENGE_FEE;
        }
                
        UserAccount srcAcc = accounts.get(sourceUserId);
        if (srcAcc == null)
            return Error.INVALID_SOURCE;
        
        UserAccount targAcc = accounts.get(targetUserId);
        if (targAcc == null)
            return Error.INVALID_DESTINATION;
        
        // Target already is having a vote run for its trusted status
        //   initiated by someone else.
        if (targAcc.authSelfUserId >= 0)
            return Error.ALREADY_EXISTS;
        
        boolean targetTrusted = (targAcc.isAuthentic() || targAcc.isAnchor());
        
        if (sourceUserId == targetUserId) {
            // Self request, and target already trusted
            if (targetTrusted)
                return Error.NOTHING_TO_DO;
        } else {
            // Others' challenge, and target already untrusted
            if (! targetTrusted)
                return Error.NOTHING_TO_DO;
            
            // Can't challenge anchors
            if (targAcc.isAnchor())
                return Error.FORBIDDEN;
        }
        
        // Choose the people who will be doing the voting.
        // If there are not enough people available, return an error.
        HashSet<Integer> authenticatorUserIds = new HashSet();
        
        // Deterministically throw a dice several times.
        // If we can't find the voters we need in the allotted dice rolls then 
        //    we give up and tell the user to try again later.
        Set<Integer> allUserIds = accounts.keySet();
        Random rnd = new Random(targetUserId * nowTimestamp);
        for (int i = 0; i < 10000; ++i) {
            
            // choose an user account in a sufficiently random fashion
            int r = rnd.nextInt() % allUserIds.size();
            Iterator it = allUserIds.iterator();
            int voterId = 0;
            for (int j = 0; j <= r; ++j)
                voterId = (int)it.next();
            
            // if voter is trusted and isn't busy, add them.
            UserAccount voterAcc = accounts.get(voterId);
            if (
                    (voterAcc.isAnchor() || voterAcc.isAuthentic()) && // trusted
                    (voterAcc.authOtherUserId < 0) && // not busy voting already
                    (voterAcc.authSelfUserId < 0) && // also voter's trust not currently challenged
                    (!authenticatorUserIds.contains(voterId)) && // don't add twice
                    (voterId != sourceUserId) && // the challenger can't vote on it
                    (voterId != targetUserId) // can't vote for self
               )
            {
                // add this voter to our candidate voters set
                authenticatorUserIds.add(voterId);
                // we already have the maximum number of voters
                if (authenticatorUserIds.size() >= totalVoters)
                    break;
            }
        }
        
        // If we couldn't find the minimum amount of voters to vote on the 
        //   election, then return an error. The requester will have to try 
        //   again later.
        if (authenticatorUserIds.size() < minVoters)
            return Error.LIMIT_REACHED;
        
        // Check if the funds are there to pay for the amount of voters 
        //   we actually managed to select.
        long fee = authenticatorUserIds.size() * perVoteFee;
        if (srcAcc.getUnlockedBalance() < fee)
            return Error.INSUFFICIENT_FUNDS;
        
        // ---- we're clear of all possible errors. it's happening. ----

        // Target account is the one that is waiting for its trusted status
        //  to be judged. The source account is who has triggered the election.
        targAcc.authSelfUserId = sourceUserId;
        
        // Mark all the voter accounts with "you're voting for this dude"
        //   so they can display them to their users when they log in.
        for (int voterId : authenticatorUserIds) {
            UserAccount voterAcc = accounts.get(voterId);
            voterAcc.authOtherUserId = targetUserId;
        }
        
        // Charge the source account to the server account (it's just simpler).
        chargeFee(srcAcc, AUTH_FUNDS_ACCOUNT_ID, logTxCode, fee, targetUserId, 
                nowTimestamp, true, false);
        
        // Add the pending auth object and return.
        PendingAuthentication pendingAuth = 
                new PendingAuthentication(authenticatorUserIds, nowTimestamp);
        pendingAuthentications.put(targetUserId, pendingAuth);
        return Error.OK;
    }
    
    // Force finishing an user trust election. Caller is responsible for 
    //   checking that the election actually ended AND ALSO REMOVING THE 
    //   PENDINGAUTH OBJECT FROM THE MAP. Otherwise this method does all
    //   other cleanup.
    void finishElection(int targetUserId, int nowTimestamp) {
        
        // Fetch the user being judged.
        UserAccount targAcc = accounts.get(targetUserId);
        if (targAcc == null)
            return;
        
        // Fetch the election object.
        PendingAuthentication pendingAuth 
                = pendingAuthentications.get(targetUserId);
        if (pendingAuth == null)
            return;
        
        // Get the result and apply it.
        boolean trustVoteResult = pendingAuth.getWinner();
        if (trustVoteResult) {
            if ((! targAcc.isAuthentic()) && (! targAcc.isAnchor()))
                ++totalTrusted; // stats
            targAcc.setAuthenticFlag();
        }
        else {
            if ((targAcc.isAuthentic()) && (! targAcc.isAnchor()))
                --totalTrusted; // stats
            targAcc.clearAuthenticFlag();
        }
        
        // record the time of last trust election for user
        targAcc.lastVerificationTimestamp = nowTimestamp;
        
        // Flag the user that was being authenticated as no longer being
        //   the subject of an election for its trust status.
        int sourceUserId = targAcc.authSelfUserId; // still need it below
        targAcc.authSelfUserId = -1;
                
        // Release all voters that haven't already voted in THIS election, 
        //   and mark each as another voter to refund the user that PAID 
        //   for the election.
        int refundVotes = 0;
        Set<Integer> voters = pendingAuth.getVoters();
        for (int voterId : voters) {
            UserAccount voterAcc = accounts.get(voterId);
            if ((voterAcc != null) && (voterAcc.authOtherUserId == targetUserId)) {
                voterAcc.authOtherUserId = -1;
                ++refundVotes;
            }
        }
        
        // refund user that triggered the election for votes not cast.
        // if the internal account runs out of funds (posssible due to 
        //   demurrage) then pay the user whatever is left if anything.
        UserAccount srcAcc = accounts.get(sourceUserId);
        long perVoteFee;
        short logCode;
        if (sourceUserId == targetUserId) {
            // self request
            perVoteFee = Main.AUTH_PER_VOTE_FEE;
            if (trustVoteResult)
                logCode = LogEntry.ACCOUNT_AUTHENTICATION_APPROVED;
            else
                logCode = LogEntry.ACCOUNT_AUTHENTICATION_REJECTED;
        } else {
            // other's challenge
            perVoteFee = Main.AUTH_CHALLENGE_PER_VOTE_FEE;
            if (trustVoteResult)
                logCode = LogEntry.ACCOUNT_AUTH_CHALLENGE_APPROVED;
            else
                logCode = LogEntry.ACCOUNT_AUTH_CHALLENGE_REJECTED;
        }
        if (srcAcc != null) {
            payUserFromInternalAccount(AUTH_FUNDS_ACCOUNT_ID, srcAcc, 
                    refundVotes * perVoteFee, logCode, targetUserId,
                    nowTimestamp);
        }
        
        // ***************************************************************
        // We return now and THE CALLER is the one that's going to remove
        //   the expired entry from the pendingAuthentications map!!!!
        // ***************************************************************
    }
    
    // "pre-deletion" of user account.
    // this does everything we need to do to delete an UserAccount, except
    //  removing the account object from the "accounts" map, which is something
    //  the caller will do.
    public void finishUserAccount(int userId, UserAccount acc, int nowTimestamp) {
        
        // if there is an election for this dude, get rid of it
        if (acc.authSelfUserId >= 0) {
            finishElection(userId, nowTimestamp);
            pendingAuthentications.remove(userId);
        }
        
        // if this user was selected to vote on something, remove them from it,
        //  and check if that didn't just end the election.
        if (acc.authOtherUserId >= 0) {
            PendingAuthentication pendingAuth = 
                    pendingAuthentications.get(acc.authOtherUserId);
            if (pendingAuth != null) {
                pendingAuth.deleteVoter(userId);
                if (pendingAuth.isFinished()) {
                    finishElection(acc.authOtherUserId, nowTimestamp);
                    pendingAuthentications.remove(acc.authOtherUserId);
                }
            }
        }
        
        // remove all validation link pointers in other accounts that are
        //   pointing to this account being deleted
        for (int vin : acc.validationIn) {
            UserAccount peerAcc = accounts.get(vin);
            if (peerAcc != null)
                peerAcc.validationOut.remove(userId);
        }
        for (int vout : acc.validationOut) {
            UserAccount peerAcc = accounts.get(vout);
            if (peerAcc != null)
                peerAcc.validationIn.remove(userId);
        }
        
        // stats: any money this user had is destroyed
        totalMoney -= acc.balance;
        
        // stats
        if (acc.isAnchor() || acc.isAuthentic())
            --totalTrusted;
    }
    
    //==================================================================
    
    public HashMap<Integer, String> getUserNames(HashSet<Integer> userIds) {
        countTx();
        
        HashMap<Integer, String> idsToNames = new HashMap();
        for (int userId : userIds) {
            UserAccount acc = accounts.get(userId);
            if (acc != null)
                idsToNames.put(userId, acc.name);
        }
        return idsToNames;
    }

    public ServerStats getServerStats() {
        countTx();
        
        ServerStats stats = new ServerStats();
        stats.totalDays = totalDays;
        stats.epochDay = epochDay;
        stats.userCount = accounts.size();
        stats.totalTrusted = totalTrusted;
        stats.totalMoney = totalMoney;
        stats.totalTx = totalTx;
        stats.recentTx = 0;
        Iterator it = txCount.values().iterator();
        while (it.hasNext())
            stats.recentTx += (Long)it.next();
        Balance balance = internalAccounts.get(SERVER_ACCOUNT_ID);
        if (balance != null)
            stats.serverBalance = balance.get();
        Runtime runtime = Runtime.getRuntime();
        stats.usedMemoryBytes = runtime.totalMemory() - runtime.freeMemory();
        return stats;
    }
    
    public SessionInfo getUserInfo(int userId) {
        UserAccount acc = accounts.get(userId);
        if (acc == null)
            return null;
        
        SessionInfo info = new SessionInfo();
        info.userId = userId;
        info.name = acc.name;
        info.unlockedBalance = acc.getUnlockedBalance();
        return info;
    }
    
    public UserAccount getUserAccount(int userId) {
        countTx();
        
        return accounts.get(userId);
    }
    
    public int setAnchorStatus(int userId, boolean set) {
        UserAccount acc = accounts.get(userId);
        if (acc == null)
            return Error.NOT_FOUND;
        if (acc.isAnchor() == set)
            return Error.NOTHING_TO_DO;
        if (set) {
            acc.setAnchorFlag();
            if (! acc.isAuthentic())
                ++totalTrusted; // stats
        }
        else {
            acc.clearAnchorFlag();
            if (! acc.isAuthentic())
                --totalTrusted; // stats
        }
        return Error.OK;
    }
    
    public void sendMoneyToInternalAccount(int internalAccountId, long amount) {
        Balance balance = internalAccounts.get(internalAccountId);
        if (balance == null)
            internalAccounts.put(internalAccountId, new Balance(amount));
        else 
            balance.set(balance.get() + amount);
    }

    public Balance getInternalAccount(int internalAccountId) {
        Balance balance = internalAccounts.get(internalAccountId);
        if (balance == null) {
            balance = new Balance(0L);
            internalAccounts.put(internalAccountId, balance);
        }
        return balance;        
    }
        
    public void releaseMinBalances(ArrayList<PendingInvite> deletedPendingInvites, int nowTimestamp) {
        for (PendingInvite pendingInvite : deletedPendingInvites) {
            UserAccount account = accounts.get(pendingInvite.getSponsorId());
            if (account != null) {
                account.minBalance -= pendingInvite.getAmount();
                if (account.minBalance < 0)
                    account.minBalance = 0; // Should never happen.
                
                log(pendingInvite.sponsorId, new LogEntry(nowTimestamp, LogEntry.INVITE_CANCEL_FUNDS_UNLOCKED, pendingInvite.getAmount()));
            }
        }
    }
    
    public void touchLoginTimestamp(int userId, int nowTimestamp) {
        UserAccount acc = accounts.get(userId);
        if (acc != null) {
            acc.lastLoginTimestamp = nowTimestamp;
        }        
    }
    
    // Edit an user's name and profile
    public int editPersonalInfo(int userId, String name, ArrayList<String> profile, int nowTimestamp) {
        countTx();
        
        UserAccount acc = accounts.get(userId);
        if (acc == null)
            return Error.INVALID_SOURCE;
        acc.name = name;
        acc.profile = profile;
        
        chargeFee(acc, SERVER_ACCOUNT_ID, LogEntry.TXFEE_EDIT_INFO, 
                Main.TRANSACTION_FEE, nowTimestamp, false, true);
        
        return Error.OK;
    }
    
    // This does NOT generate an invitation code. That's done LATER, after
    //  we LOCK an user's funds here! (invitation codes are in the secdm).
    // attempt to lock the funds necessary for inviting another user in.
    // return 0 if OK, or a negative value if we don't have enough funds to
    //   cover the gift amount to the new user.
    // ALSO returns error if we're past the validation links limit!
    public int createInvite(int sponsorId, long amount, int nowTimestamp) {
        countTx();
        
        UserAccount sponsor = accounts.get(sponsorId);
        if (sponsor == null)
            return Error.INVALID_SOURCE;
        
        // Don't let untrusted (non-audited and non-anchor) users invite 
        //  other users. This avoids account creation spam and also helps to 
        //  avoid new users spending their balances into anything other than
        //  paying for the crowdsourced auditing process on their own accounts
        //  (so they can be trusted and begin receiving the UBI).
        if ((!sponsor.isAnchor()) && (!sponsor.isAuthentic()))
            return Error.NOT_TRUSTED;        
        
        // If amount is too small, refuse it
        // (TODO: this is no longer an API parameter. this may be removed if 
        //   it stays that way).
        if (amount < Main.MIN_INVITE_AMOUNT)
            return Error.INSUFFICIENT_AMOUNT;
        
        // Check if user has the amount
        if (sponsor.getUnlockedBalance() < amount + Main.TRANSACTION_FEE)
            return Error.INSUFFICIENT_FUNDS;
        
        // Check if the user is already at their maximum number of unique 
        //  user IDs present in all their in/out validation links.
        if (sponsor.getAllValidationLinkUserIds().size() >= Main.MAX_UNIQUE_VALIDATION_IDS)
            return Error.SOURCE_LIMIT_REACHED;
        
        // Otherwise we're good: lock the balance for the invite...
        sponsor.minBalance += amount;
        sponsor.log(new LogEntry(nowTimestamp, LogEntry.INVITE_CREATE_FUNDS_LOCKED, -amount));
        
        // ...and charge a fee to help prevent spam
        chargeFee(sponsor, SERVER_ACCOUNT_ID, LogEntry.TXFEE_CREATE_INVITE, 
                Main.TRANSACTION_FEE, nowTimestamp, true, false);
                
        return Error.OK;                
    }
    
    public int createUser(UserAccount newUserAccount, int sponsorId, int timestampNow) {
        countTx();
        
	Integer newUserId = getNewUserId();
        if (newUserId >= 0) {
            accounts.put(newUserId, newUserAccount);
            
            // Only way the new account's balance field isn't zero 
            //  is if this is an --invite_anchor that creates money. 
            // Otherwise, it is zero and this does nothing; the new user's 
            //  balance is fed AFTER this call with EXISTING money in the 
            //  system (money supplied by the user that is sponsoring this 
            //  new user account).
            if (newUserAccount.balance > 0) {
                totalMoney += newUserAccount.balance;
                newUserAccount.log(new LogEntry(timestampNow, LogEntry.INVITE_ACCEPT_RECEIVE_MONEY, newUserAccount.balance, sponsorId));
            }
            
            // hard-code the first reciprocal validation link
            if (sponsorId >= 0) {
                UserAccount sponsorAccount = accounts.get(sponsorId);
                if (sponsorAccount != null) {
                    // sponsor ----trust----> newuser
                    sponsorAccount.validationOut.add(newUserId);
                    newUserAccount.validationIn.add(sponsorId);
                    // newuser ----trust----> sponsor
                    newUserAccount.validationOut.add(sponsorId);
                    sponsorAccount.validationIn.add(newUserId);
                }
            }
            
            // stats
            if (newUserAccount.isAnchor() || newUserAccount.isAuthentic())
                ++totalTrusted;
        }
        return newUserId;
    }
    
    public int deleteUser(int userId, int nowTimestamp) {
        UserAccount acc = accounts.get(userId);
        if (acc == null)
            return Error.NOT_FOUND; // should never happen
        
        // clean up everything acc points to
        finishUserAccount(userId, acc, nowTimestamp);
        
        // and now we can get rid of the account
        accounts.remove(userId);
        return Error.OK;
    }
    
    public void log(int userId, LogEntry entry) {
        UserAccount acc = accounts.get(userId);
        if (acc != null)
            acc.log(entry);
    }
    
    public int getNewUserId() {
        
        if (accounts.size() >= DataModel.MAX_USER_ACCOUNTS)
            return Error.FAILED;
        
        do {
            // Valid IDs: 0 to INT_MAX.
            // negative values are used for errors.
            if (++userAccountIDGenerator < 0)
                userAccountIDGenerator = 0;
        } while (accounts.containsKey(userAccountIDGenerator));
        
        return userAccountIDGenerator;
    }
    
    public int getEpochDay() {
        return epochDay;
    }
    
    public ArrayList<Integer> tick(int nowTimestamp) {
        
        ArrayList<Integer> deletedUserIds = new ArrayList();
        
        // stats
        ++totalDays;
        
        // advance server simulation time (the days elapsed since epoch)
        ++epochDay;
        
        // Count some stuff again
        totalMoney = 0;
        totalTrusted = 0;

        // Money destruction, generation, and fees moving around.
        // To be correct, all demurrage must be applied to all existing 
        //   money at the closing of the current day/timestmap, and all new 
        //   money and fee movement should happen after that--at the beginning 
        //   of the new "economic day."
        // We don't want to loop twice on all lists, so we are trying to 
        //   recycle a single loop on each and still be correct while mixing 
        //   the demurrage step (previous-day logic) with the rest 
        //   (next-day logic).
        Iterator it;
        
        // Demurrage of all internal accounts
        it = internalAccounts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry pair = (Map.Entry)it.next();
            Balance balance = (Balance)pair.getValue();
            long bal = balance.get();
            // Demurrage
            bal = Main.applyDailyDemurrage(bal);
            balance.set(bal);
            
            // Stats update
            totalMoney += bal;
            
            // I don't think we ever delete internal accounts here.
            // Code that creates internal accounts should delete them.
            // On the other hand, we'd lose nothing by deleting internal 
            //   accounts with zero or negative balances--that condition
            //   is implicit on a missing account entry.
        }
        
        // UBI, fees, demurrage and cleanup on all user accounts
        it = accounts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry pair = (Map.Entry)it.next();
            int userId = (int)pair.getKey();
            UserAccount acc = (UserAccount)pair.getValue();
            
            // refresh stats
            if (acc.isAnchor() || acc.isAuthentic())
                ++totalTrusted;

            // Demurrage
            long reducedBalance = Main.applyDailyDemurrage(acc.balance);
            long demCharge = acc.balance - reducedBalance;
            acc.balance -= demCharge;
            acc.log(new LogEntry(nowTimestamp, LogEntry.DEMURRAGE, -demCharge));
            
            // Check if the account is ACTIVE.
            // Inactive accounts:
            // - Don't pay UBI;
            // - Have their balance DESTROYED progressively faster as the 
            //   inactivity time increases.
            int minutesElapsed = nowTimestamp - acc.lastLoginTimestamp;
            boolean active = minutesElapsed < Main.ACCOUNT_INACTIVITY_MINUTES;
            
            // If account is INACTIVE, then destroy some of its money now.
            // Destruction rate is days of inactivity times the daily account 
            //   maintenance fee for now.
            if (! active) {
                long daysInactive = 1 + (minutesElapsed / (24 * 60));
                long inactivityCharge = daysInactive * Main.USER_ACCOUNT_DAILY_FEE;
                long actualCharge = Math.min(acc.balance, inactivityCharge);
                acc.balance -= actualCharge;
                acc.log(new LogEntry(nowTimestamp, LogEntry.INACTIVE_ACCOUNT, -actualCharge));
            }
            
            // NEXT DAY (fresh money):

            // Pay UBI if account is Trusted and is active
            if (active && (acc.isAuthentic() || acc.isAnchor())) {
                acc.balance += Main.UBI_AMOUNT;
                acc.log(new LogEntry(nowTimestamp, LogEntry.UBI, Main.UBI_AMOUNT));
            }
            
            // Stats update (internal accounts already counted, so this 
            //   must happen before we send the account upkeep fee below)
            totalMoney += acc.balance;
            
            // Server fee
            //long charge = Math.min(acc.balance, Main.USER_ACCOUNT_DAILY_FEE);
            //acc.balance -= charge;
            //sendMoneyToInternalAccount(SERVER_ACCOUNT_ID, charge);
            long upkeepCharge = chargeFee(acc, SERVER_ACCOUNT_ID, 
                    LogEntry.ACCOUNT_MAINTENANCE_FEE, 
                    Main.USER_ACCOUNT_DAILY_FEE, nowTimestamp, false, true);
            
            // Deletion
            if ((acc.balance < 0) ||
                ((acc.balance == 0) && (upkeepCharge < Main.USER_ACCOUNT_DAILY_FEE))
               )
            {
                deletedUserIds.add((Integer)pair.getKey()); // secdm will clean up after itself after we return
                finishUserAccount(userId, acc, nowTimestamp); // clean up everything acc points to
                it.remove();  // and get rid of the account object
            }
        }
        
        // Finish all expired user authentic-account elections.
        it = pendingAuthentications.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry entry = (Map.Entry)it.next();
            int userId = (int)entry.getKey();
            PendingAuthentication pendingAuth = (PendingAuthentication)entry.getValue();
            if (pendingAuth.isExpired(nowTimestamp)) {
                finishElection(userId, nowTimestamp); // Resolve the election.
                it.remove(); // DELETE THE ELECTION OBJECT. WE do this, not finishElection().
            }
        }
        
        return deletedUserIds;
    }
    
    public long sendMoney(int fromUserId, int toUserId, long amount, boolean exact, boolean locked, int nowTimestamp) {
        countTx();
        
        UserAccount from = accounts.get(fromUserId);
        if (from == null)
            return Error.INVALID_SOURCE;
        UserAccount to = accounts.get(toUserId);
        if (to == null)
            return Error.INVALID_DESTINATION;

        if (amount <= 0)
            return Error.INVALID_AMOUNT;
        
        // If locked==true then this is an internal server call that is 
        //    sending locked funds themselves and unlocking them, so the
        //    balance available is the entire thing.
        long balance;
        if (locked)
            balance = from.balance;
        else
            // minBalance are committed (locked) funds that can't be sent
            //   until the commitment is released (e.g. pending invites)
            balance = from.getUnlockedBalance(); // don't count the locked part
        
        long totalCharge = amount + Main.TRANSACTION_FEE;
        long availableToCharge = Math.min(balance, totalCharge);
        if ((availableToCharge <= 0) || (exact && (availableToCharge != totalCharge)))
            return Error.INSUFFICIENT_FUNDS;
        
        chargeFee(from, SERVER_ACCOUNT_ID, LogEntry.TXFEE_SEND_MONEY, 
                  Main.TRANSACTION_FEE, nowTimestamp, true, false);
        
        long amountToSend = Math.min(balance, amount);
        from.balance -= amountToSend;
        to.balance += amountToSend;
        
        // locked funds sent (released), so reduce the total locked amount
        if (locked) {
            from.minBalance -= amountToSend;
            if (from.minBalance < 0)
                from.minBalance = 0; // never happens
            
            from.log(new LogEntry(nowTimestamp, LogEntry.INVITE_ACCEPT_FUNDS_UNLOCKED, -amountToSend, toUserId));
            to.log(new LogEntry(nowTimestamp, LogEntry.INVITE_ACCEPT_RECEIVE_MONEY, amountToSend, fromUserId));

        } else {
            from.log(new LogEntry(nowTimestamp, LogEntry.SEND_MONEY, -amountToSend, toUserId));
            to.log(new LogEntry(nowTimestamp, LogEntry.RECEIVE_MONEY, amountToSend, fromUserId));
        }

        return amountToSend;
    }

    // return Error.OK(0) if everything OK, or negative error code on error.
    public int addValidation(int thisUserId, int otherUserId, int nowTimestamp) {
        countTx();
        
        UserAccount thisAcc = accounts.get(thisUserId);
        if (thisAcc == null)
            return Error.INVALID_SOURCE;
        
        UserAccount otherAcc = accounts.get(otherUserId);
        if (otherAcc == null)
            return Error.INVALID_DESTINATION;
        
        // Check hard cap met
        if (thisAcc.getAllValidationLinkUserIds().size() >= Main.MAX_UNIQUE_VALIDATION_IDS)
            return Error.SOURCE_LIMIT_REACHED;
        if (otherAcc.getAllValidationLinkUserIds().size() >= Main.MAX_UNIQUE_VALIDATION_IDS)
            return Error.DESTINATION_LIMIT_REACHED;

        if (! thisAcc.validationOut.contains(otherUserId)) {
            // If this outbound validation is not reciprocated yet, then
            //   charge a small fee to avoid spam.
            if (! otherAcc.validationOut.contains(thisUserId)) {
                long charge = 
                        chargeFee(thisAcc, SERVER_ACCOUNT_ID,
                                  LogEntry.TXFEE_VALIDATION_INITIATION, 
                                  Main.NONRECIPROCAL_OUTBOUND_VALIDATION_FEE,
                                  nowTimestamp, true, false);
                if (charge <= 0)
                    return Error.INSUFFICIENT_AMOUNT;
            }
            thisAcc.validationOut.add(otherUserId);
            otherAcc.validationIn.add(thisUserId);
        } else
            return Error.ALREADY_EXISTS;

        return Error.OK;
    }

    // return Error.OK(0) if everything OK, or negative error code on error.
    public int removeValidation(int thisUserId, int otherUserId, boolean inbound, boolean outbound) {
        countTx();
        
        UserAccount thisAcc = accounts.get(thisUserId);
        if (thisAcc == null)
            return Error.INVALID_SOURCE;
        UserAccount otherAcc = accounts.get(otherUserId);
        if (otherAcc == null)
            return Error.INVALID_DESTINATION;
        
        if (inbound) {
            if (thisAcc.validationIn.contains(otherUserId)) {
                thisAcc.validationIn.remove(otherUserId);
                otherAcc.validationOut.remove(thisUserId);
            } 
        }
        
        if (outbound) {
            if (thisAcc.validationOut.contains(otherUserId)) {
                thisAcc.validationOut.remove(otherUserId);
                otherAcc.validationIn.remove(thisUserId);
            }
        }
        
        return Error.OK;
    }
    
    // return null on error or a response object if all OK.
    public ValidationCheck checkValidation(int thisUserId, int otherUserId) {
        // can't add validation links to yourself
        if (thisUserId == otherUserId)
            return null; 
        
        // you don't exist
        UserAccount thisAcc = accounts.get(thisUserId);
        if (thisAcc == null)
            return null; // FIXME log/err reporting back

        // the other user doesn't exist
        if (! accounts.containsKey(otherUserId))
            return null; // FIXME log/err reporting back
        
        boolean inbound = thisAcc.validationIn.contains(thisUserId);
        boolean outbound = thisAcc.validationOut.contains(otherUserId);
        
        return new ValidationCheck(inbound, outbound);
    }
    
    public int requestTrust(int userId, int nowTimestamp) {
        return doRequestTrust(userId, userId, nowTimestamp);
    }
    
    public int voteTrust(int userId, boolean vote, int nowTimestamp) {
        UserAccount acc = accounts.get(userId);
        if (acc == null)
            return Error.NOT_FOUND;
        
        // See if you're actually selected to vote for someone.
        // Whether I'm trusted or not to vote was resolved prior to this 
        //   field being set, and we don't resolve it again here.
        if (acc.authOtherUserId < 0)
            return Error.NOTHING_TO_DO;
        
        // Fetch the election object.
        PendingAuthentication pendingAuth 
                = pendingAuthentications.get(acc.authOtherUserId);
        if (pendingAuth == null) {
            acc.authOtherUserId = -1;
            return Error.EXPIRED; // too late; polls are closed
        }
        
        // Register your vote.
        pendingAuth.vote(userId, vote);
        
        // I'm done voting and I'm free to vote in another election.
        int targetUserId = acc.authOtherUserId; // we need a copy
        acc.authOtherUserId = -1;
        
        // Figure out the refund amount by seeing if the target of the 
        //    election is evaluating themselves or whether is someone
        //    ELSE forcing their re-evaluation (i.e. a challenge, which
        //    is more expensive and pays out more to voters)
        // (if the target account died for whatever reason just pay the 
        //    smaller fee and let the excess rot in the server's internal
        //    account used to pay for all this. simpler.)
        long perVoteFee = Main.AUTH_PER_VOTE_FEE;
        UserAccount targAcc = accounts.get(targetUserId);
        if (targAcc != null && targAcc.authSelfUserId != targetUserId)
            perVoteFee = Main.AUTH_CHALLENGE_PER_VOTE_FEE; // it's a challenge
        
        // Pay the voter with funds from the internal account that we use
        //  to hold the funds. If there's isn't enough money there for 
        //  whatever reason, give them whatever is left, if anything.
        // The reward logtxcode is the same regardless of whether it was a 
        //  challenge to remove trust or a self-request to become trusted.
        payUserFromInternalAccount(AUTH_FUNDS_ACCOUNT_ID, acc, 
                perVoteFee, LogEntry.ACCOUNT_AUTHENTICATION_REWARD, 
                nowTimestamp);
        
        // Check if election was just finished because of this.
        // If it is finished, then do all the resolultion and cleanup.
        if (pendingAuth.isFinished()) {
            // Resolve the election.
            finishElection(targetUserId, nowTimestamp);
            // DELETE THE ELECTION OBJECT. WE do this, not finishElection().
            pendingAuthentications.remove(targetUserId);
        }
        
        return Error.OK;
    }
    
    public int challengeTrust(int sourceUserId, int targetUserId, int nowTimestamp) {
        if (sourceUserId == targetUserId)
            return Error.FORBIDDEN;
        return doRequestTrust(sourceUserId, targetUserId, nowTimestamp);        
    }
    
    public int burnMoney(int userId, long amount, int nowTimestamp) {
        UserAccount acc = accounts.get(userId);
        if (acc == null)
            return Error.NOT_FOUND;
        if (amount <= 0)
            return Error.INVALID_AMOUNT;
        if (acc.getUnlockedBalance() < amount + Main.TRANSACTION_FEE)
            return Error.INSUFFICIENT_FUNDS;
        acc.balance -= amount;
        totalMoney -= amount;
        acc.log(new LogEntry(nowTimestamp, LogEntry.BURN_MONEY, -amount));
        chargeFee(acc, SERVER_ACCOUNT_ID, LogEntry.TXFEE_BURN_MONEY, 
                  Main.TRANSACTION_FEE, nowTimestamp, true, false);
        return Error.OK;
    }
    
    // Only in case of an error after burnMoney when trying to sign the
    //  burn money receipt.
    public int unburnMoney(int userId, long amount, int nowTimestamp) {
        UserAccount acc = accounts.get(userId);
        if (acc == null)
            return Error.NOT_FOUND;
        acc.balance += amount;
        totalMoney += amount;
        payUserFromInternalAccount(SERVER_ACCOUNT_ID, acc, Main.TRANSACTION_FEE, 
                LogEntry.REFUND_TXFEE_BURN_MONEY, nowTimestamp);
        acc.log(new LogEntry(nowTimestamp, LogEntry.UNBURN_MONEY, amount));
        return Error.OK;
    }
}
