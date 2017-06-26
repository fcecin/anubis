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
 * An user has asked to use an invitation code, so the web server is asking
 *   us whether the code is still valid. In case it is, we also bump the 
 *   expiration of the invite into the future so the user has some wall-clock
 *   time to actually fill out the form before the invite expires.
 */
public class CheckInviteTx implements TransactionWithQuery<SecurityDataModel, Integer> {
    private static final long serialVersionUID = 1L;
    long invitationCode;
    public CheckInviteTx(long invitationCode) {
        this.invitationCode = invitationCode;
    }
    @Override
    public Integer executeAndQuery(SecurityDataModel secdm, Date date) {
        return secdm.checkInvite(invitationCode, Timestamp.fromDate(date));
    }
}
