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
import java.util.Objects;
import org.mindrot.jbcrypt.BCrypt;

/**
 * A hashed and salted password turned into a binary blob.
 */
public class StoredPassword implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public static final int DEFAULT_BCRYPT_WORKLOAD = 12;
    
    // From https://gist.github.com/craSH/5217757
    public static StoredPassword hashPassword(final String plaintextPassword) {
        String salt = BCrypt.gensalt(DEFAULT_BCRYPT_WORKLOAD, Main.getSecureRandom());
        return hashPassword(plaintextPassword, salt);
    }

    public static StoredPassword hashPassword(final String plaintextPassword, final String salt) {
        String hashedPassword = BCrypt.hashpw(plaintextPassword, salt);
        return new StoredPassword(hashedPassword, salt);
    }

    final String hashedPassword;
    final String salt;
    
    public StoredPassword(final String hashedPassword, final String salt) {
        this.hashedPassword = hashedPassword;
        this.salt = salt;
    }
    
    public String getSalt() {
        return salt;
    }
    
    @Override
    public boolean equals(Object other) {
        if (other == null)
            return false;
        if (other == this)
            return true;
        if (!(other instanceof StoredPassword))
            return false;
        
        StoredPassword o = (StoredPassword)other;

        return (
                (hashedPassword.equals(o.hashedPassword))
                && (salt.equals((o.salt)))
               );
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 13 * hash + Objects.hashCode(this.hashedPassword);
        hash = 13 * hash + Objects.hashCode(this.salt);
        return hash;
    }

}
