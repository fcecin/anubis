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
 * An authentication session for an user. 
 */
public class UserSession implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public static final int DEFAULT_SESSION_TIMEOUT_MINUTES = 60;
    
    public UserSession(int userId) {
        this.userId = userId;
    }
    
    public void touch(int nowTimestamp) {
        lastHitTimestamp = nowTimestamp;
    }
    
    public boolean isExpired(int nowTimestamp) {
        return nowTimestamp > lastHitTimestamp + timeoutMinutes;
    }
    
    public int getUserId() {
        return userId;
    }
    
    // The user
    int userId;
    
    // Last time we got a request from this session
    int lastHitTimestamp = Timestamp.now();
        
    // Session timeout in minutes (to be counted from the 
    //   last hit time)
    int timeoutMinutes = DEFAULT_SESSION_TIMEOUT_MINUTES;
}
