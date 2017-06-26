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
import java.util.Date;
import org.prevayler.Transaction;

/**
 * Updates the email address of an EXISTING user.
 */
public class BindUserIdToEmailTx implements Transaction<SecurityDataModel> {
    private static final long serialVersionUID = 1L;
    int userID;
    String emailAddress;
    public BindUserIdToEmailTx(int userID, String emailAddress) {
        this.userID = userID;
        this.emailAddress = emailAddress;
    }
    @Override
    public void executeOn(SecurityDataModel secdm, Date date) {
        //int boundID = secdm.getUserID(emailAddress);
        // If e-mail address already in use by whomever, don't do anything
        //if (boundID >= 0)
        //    return boundID; // may or may not be the same as userID
        // In case the userID was already bound to some other email address, 
        //   we will just overwrite it here.
        secdm.bindUserIdToEmailAddress(userID, emailAddress);
        //return userID;
    }

    
}
