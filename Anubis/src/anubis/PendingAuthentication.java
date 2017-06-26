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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Given an user ID being authenticated, this gives details of their 
 *   ongoing crowdsourced authentication (setting Untrusted to Trusted status)
 *   effort.
 */
public class PendingAuthentication implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public PendingAuthentication(HashSet<Integer> authenticatorUserIds, int nowTimestamp) {
        for (int authenticatorUserId : authenticatorUserIds)
            votes.put(authenticatorUserId, null);
        startTimestamp = nowTimestamp;
    }
    
    public boolean isExpired(int nowTimestamp) {
        return nowTimestamp > startTimestamp + Main.AUTH_TIMEOUT_MINUTES;
    }
    
    public void vote(int userId, boolean vote) {
        votes.put(userId, vote);
    }
    
    public Set<Integer> getVoters() {
        return votes.keySet();
    }
    
    public void deleteVoter(int userId) {
        votes.remove(userId);
    }
    
    // checks whether the election is over
    public boolean isFinished() {
        int totalVotes = votes.size();
        int trueVotes = 0;
        int falseVotes = 0;
        int nullVotes = 0;
        Iterator it = votes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry entry = (Map.Entry)it.next();
            Boolean vote = (Boolean)entry.getValue();
            if (vote == null) 
                ++nullVotes;
            else if (vote)
                ++trueVotes;
            else
                ++falseVotes;
        }
        return (
                (trueVotes > (totalVotes / 2)) ||  // guaranteed trusted win
                (falseVotes > (totalVotes / 2)) || // guaranteed untrusted win
                ((totalVotes % 2 == 0) && (falseVotes == (totalVotes / 2))) || // untrusted wins on tie if there's an even number of voters
                (nullVotes == 0)                   // all votes have been cast
               );
    }
    
    // returns the current result. 
    // in case of a tie, the authentication fails.
    public boolean getWinner() {
        int trueVotes = 0;
        int falseVotes = 0;
        Iterator it = votes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry entry = (Map.Entry)it.next();
            Boolean vote = (Boolean)entry.getValue();
            if (vote != null) {
                if (vote)
                    ++trueVotes;
                else
                    ++falseVotes;
            }
        }
        return trueVotes > falseVotes;
    }
    
    // User ID of a chosen authenticator and their vote, if any.
    // (null value means vote not cast yet).    
    HashMap<Integer, Boolean> votes = new HashMap();
    
    // Starting Timestamp of this democratic authentication.
    // (so we can know when it expires).
    int startTimestamp;
}
