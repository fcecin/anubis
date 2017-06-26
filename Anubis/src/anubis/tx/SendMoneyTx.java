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
 * Logged-in user attempts to send an amount to another user.
 */
public class SendMoneyTx implements TransactionWithQuery<DataModel, Long> {
    private static final long serialVersionUID = 1L;
    int fromUserId;
    int toUserId;
    long amount;
    boolean exact;
    boolean locked;
    public SendMoneyTx(int fromUserId, int toUserId, long amount, boolean exact, boolean locked) {
        this.fromUserId = fromUserId;
        this.toUserId = toUserId;
        this.amount = amount;
        this.exact = exact;
        this.locked = locked;
    }
    @Override
    public Long executeAndQuery(DataModel dm, Date date) {
        return dm.sendMoney(fromUserId, toUserId, amount, exact, locked, Timestamp.fromDate(date));
    }
}
