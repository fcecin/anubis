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
import anubis.Timestamp;
import java.util.Date;
import org.prevayler.TransactionWithQuery;

/**
 * User requests a random selection of their peers to judge whether the 
 *   user's profile looks legit and should receive direct UBI money creation.
 */
public class RequestTrustTx implements TransactionWithQuery<DataModel, Integer> {
    private static final long serialVersionUID = 1L;
    int userId;
    public RequestTrustTx(int userId) {
        this.userId = userId;
    }
    @Override
    public Integer executeAndQuery(DataModel dm, Date date) {
        return dm.requestTrust(userId, Timestamp.fromDate(date));
    }
}
