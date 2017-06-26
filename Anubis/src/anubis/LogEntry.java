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

/**
 * An entry into a log.
 */
public class LogEntry implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public LogEntry(int timestamp, short code, long amount, int userId) {
        this.timestamp = timestamp;
        this.code = code;
        this.amount = amount;
        this.userId = userId;
    }
    
    public LogEntry(int timestamp, short code, long amount) {
        this(timestamp, code, amount, -1);
    }
    
    public int getTimestamp() { return timestamp; }
    public short getCode() { return code; }
    public long getAmount() { return amount; }
    public int getUserId() { return userId; }
    
    // When this happened, in minutes since epoch (class Timestamp)
    final int timestamp;
    
    // What happened
    //
    // FIXME: compress this as the lower 8/16 bits of the "amount" long: not
    //        too many transactions will use the full 64 bits for tx amounts...
    final short code;
    
    // Amount +/- credited/debited to this account (0 if no change)
    final long amount;
    
    // Another userId (account) involved in the transaction (optional)
    final int userId;
        
    // ---------------------------------------------------------
    //  TX codes without userId argument
    // ---------------------------------------------------------
    
    // Daily UBI payment
    public static final short UBI                               = 1;
    
    // Daily demurrage charge
    public static final short DEMURRAGE                         = 2;
    
    // Daily account maintenance fee
    public static final short ACCOUNT_MAINTENANCE_FEE           = 3;
    
    // No charge; amount locked due to creating an invite code.
    public static final short INVITE_CREATE_FUNDS_LOCKED        = 4;
    
    // No charge; amount unlocked due to cancelling an invite code.
    public static final short INVITE_CANCEL_FUNDS_UNLOCKED      = 5;
    
    // Charged when you request to be set to Trusted status.
    public static final short ACCOUNT_AUTHENTICATION_FEE        = 6;

    // Credited when you are rewarded for voting to authenticate an user.
    public static final short ACCOUNT_AUTHENTICATION_REWARD     = 7;
    
    // Credited when your request to authenticate your profile is accepted
    //   and you receive a refund (if any) for the votes not cast.
    public static final short ACCOUNT_AUTHENTICATION_APPROVED   = 8;

    // Credited when your request to authenticate your profile is rejected
    //   and you receive a refund (if any) for the votes not cast.
    public static final short ACCOUNT_AUTHENTICATION_REJECTED   = 9;
    
    // Daily partial monetary destruction of inactive accounts.
    public static final short INACTIVE_ACCOUNT                  = 10;

    // Charged when you request someone to be set to Untrusted status.
    public static final short ACCOUNT_AUTH_CHALLENGE_FEE        = 11;

    // Credited when your challenge to someone's profile is accepted
    //   and you receive a refund (if any) for the votes not cast.
    public static final short ACCOUNT_AUTH_CHALLENGE_APPROVED   = 12;

    // Credited when your challenge to someone's profile is rejected
    //   and you receive a refund (if any) for the votes not cast.
    public static final short ACCOUNT_AUTH_CHALLENGE_REJECTED   = 13;
    
    // Debit when you burn money (export it to an external system).
    public static final short BURN_MONEY                        = 14;
    
    // Money credited back when burn money fails for whatever reason.
    public static final short UNBURN_MONEY                      = 15;
    
    // ---------------------------------------------------------
    //  TX codes with userId argument
    // ---------------------------------------------------------
    
    // Amount sent to (userId).
    public static final short SEND_MONEY                        = 32;
    
    // Amount received from (userId).
    public static final short RECEIVE_MONEY                     = 33;
    
    // Amount sent to (userId) for accepting our invite. The 
    //   amount sent is deducted from the locked funds amount.
    public static final short INVITE_ACCEPT_FUNDS_UNLOCKED      = 34;
    
    // Amount received from (userId) for accepting their invite.
    public static final short INVITE_ACCEPT_RECEIVE_MONEY       = 35;
    
    // ---------------------------------------------------------
    //  TX codes for transaction fees
    //  Charged for transactions (writes to Prevayler, generating 
    //   disk writes to the transaction replay log) or for 
    //   expensive queries.
    // ---------------------------------------------------------

    public static final short TXFEE_EDIT_INFO                   = 64;
    
    public static final short TXFEE_VALIDATION_INITIATION       = 65;
    
    public static final short TXFEE_CREATE_INVITE               = 66;
    
    public static final short TXFEE_SEND_MONEY                  = 67;

    public static final short TXFEE_BURN_MONEY                  = 68;
    
    // This is a credit. Used to refund TXFEE_BURN_MONEY for a failed
    //   burn money operation resulting in an UNBURN_MONEY credit.
    public static final short REFUND_TXFEE_BURN_MONEY           = 69;
}