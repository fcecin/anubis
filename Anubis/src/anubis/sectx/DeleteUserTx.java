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
 * Delete all data for an userId from the secdm.
 */
public class DeleteUserTx implements Transaction<SecurityDataModel> {
    private static final long serialVersionUID = 1L;
    int userId;
    public DeleteUserTx(int userId) {
        this.userId = userId;
    }
    @Override
    public void executeOn(SecurityDataModel secdm, Date date) {
        secdm.deleteUser(userId);
    }
}
