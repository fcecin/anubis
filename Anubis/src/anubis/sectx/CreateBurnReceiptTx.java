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
 * Using the server's master key, sign a message attesting that a given 
 *   quantity of money was burned, attached to an user-supplied comment.
 * Return that receipt and save a copy of it on the giver userId's 
 *   private account.
 */
public class CreateBurnReceiptTx implements TransactionWithQuery<SecurityDataModel, byte[]>{
    private static final long serialVersionUID = 1L;
    int userId;
    long amount;
    byte[] comment;
    public CreateBurnReceiptTx(int userId, long amount, byte[] comment) {
        this.userId = userId;
        this.amount = amount;
        this.comment = comment.clone();
    }
    @Override
    public byte[] executeAndQuery(SecurityDataModel secdm, Date date) {
        return secdm.createBurnReceipt(userId, amount, comment, Timestamp.fromDate(date));
    }
}