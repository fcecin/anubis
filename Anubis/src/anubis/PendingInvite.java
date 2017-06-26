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
 * A pending e-mail validation step that creates a new UserAccount.
 * 
 * The constructor is non-deterministic. Don't create this within a 
 *   Prevayler transaction!
 */
public class PendingInvite implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public PendingInvite(int sponsorId, long amount, int nowTimestamp) {
        this.sponsorId = sponsorId;
        this.amount = amount;
        this.expirationTimestamp = nowTimestamp + Main.INVITE_TIMEOUT_MINUTES;
    }
    
    public boolean isExpired(int nowTimestamp) {
        return nowTimestamp > expirationTimestamp;
    }
    
    public int getExpirationTimestamp() { 
        return expirationTimestamp;
    }
    
    public int getSponsorId() {
        return sponsorId;
    }
    
    public long getAmount() {
        return amount;
    }
    
    public void touch(int nowTimestamp) {
        if (nowTimestamp > expirationTimestamp - 1440)
            expirationTimestamp += 1440;  // Grant an entire extra day to fill up the form.
    }
    
    // The existing validated user that is inviting the new user.
    int sponsorId;
    
    // Amount of money locked in the sponsor user's account. 
    // This is NOT a balance and this doesn't need to be updated as time passes.
    long amount;
    
    // time when this pending invite expires and can be cleaned up.
    int expirationTimestamp;
}
