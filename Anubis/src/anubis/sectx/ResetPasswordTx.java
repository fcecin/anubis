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
import anubis.StoredPassword;
import java.util.Date;
import org.prevayler.TransactionWithQuery;

/**
 * Reset an user's password given their email address and password reset secret.
 */
public class ResetPasswordTx implements TransactionWithQuery<SecurityDataModel, Integer> {
    private static final long serialVersionUID = 1L;
    StoredPassword password;
    long resetCode;
    public ResetPasswordTx(StoredPassword password, long resetCode) {
        this.password = password;
        this.resetCode = resetCode;
    }
    @Override
    public Integer executeAndQuery(SecurityDataModel secdm, Date date) {
        return secdm.resetPassword(password, resetCode);
    }
}
