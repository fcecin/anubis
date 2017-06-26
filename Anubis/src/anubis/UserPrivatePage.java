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
package anubis;

import java.io.Serializable;

/**
 * Data describing an user's private view of their own page.
 */
public class UserPrivatePage implements Serializable {
    private static final long serialVersionUID = 1L;
    
    // The user's own ID
    public int userId;
    
    // The public part of the account
    public UserAccount account;
    
    // The private part of the account
    public PrivateUserAccount privateAccount;
}
