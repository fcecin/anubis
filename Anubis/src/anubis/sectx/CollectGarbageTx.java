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

import anubis.PendingInvite;
import anubis.SecurityDataModel;
import anubis.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import org.prevayler.TransactionWithQuery;

/**
 * Run periodic cleanup tasks on the SecurityDataModel.
 */
public class CollectGarbageTx implements TransactionWithQuery<SecurityDataModel, ArrayList<PendingInvite>> {
    private static final long serialVersionUID = 1L;
    ArrayList<Integer> deletedUserIds;
    public CollectGarbageTx(ArrayList<Integer> deletedUserIds) {
        this.deletedUserIds = deletedUserIds;
    }
    @Override
    public ArrayList<PendingInvite> executeAndQuery(SecurityDataModel secdm, Date date) {
        return secdm.collectGarbage(deletedUserIds, Timestamp.fromDate(date));
    }
}
