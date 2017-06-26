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
package anubis.tx;

import anubis.DataModel;
import java.util.Date;
import org.prevayler.TransactionWithQuery;

/**
 * Enable/disable the anchor flag of an user.
 */
public class SetAnchorStatusTx implements TransactionWithQuery<DataModel, Integer> {
    private static final long serialVersionUID = 1L;
    int userId;
    boolean set;
    public SetAnchorStatusTx(int userId, boolean set) {
        this.userId = userId;
        this.set = set;
    }
    @Override
    public Integer executeAndQuery(DataModel dm, Date date) {
        return dm.setAnchorStatus(userId, set);
    }    
}
