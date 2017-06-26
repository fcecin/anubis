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
package anubis.sectx;

import anubis.SecurityDataModel;
import anubis.Timestamp;
import java.util.Date;
import org.prevayler.TransactionWithQuery;

/**
 * Any authenticated request to the Anubis server will generate this 
 *   transaction to the secdm first. This checks what is the userId for 
 *   a given session key, and touches the session so it stays alive,
 *   pushing its expiration timestamp further into the future (e.g. +1 hour).
 * If the session is expired, it is removed, and a negative userid is
 *   returned. If no session was found, a negative userid is returned.
 */
public class TouchSessionTx implements TransactionWithQuery<SecurityDataModel, Integer> {
    private static final long serialVersionUID = 1L;
    long sessionId;
    public TouchSessionTx(long sessionId) {
        this.sessionId = sessionId;
    }
    @Override
    public Integer executeAndQuery(SecurityDataModel secdm, Date date) {
        return secdm.touchSession(sessionId, Timestamp.fromDate(date));
    }
}
