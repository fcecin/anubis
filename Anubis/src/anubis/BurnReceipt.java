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

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Parses a burn receipt into its fields.
 */
public class BurnReceipt {
    
    public class UnknownVersionException extends Exception {
        public byte ver;
        public UnknownVersionException(byte ver) { this.ver = ver; }
    }
        
    public BurnReceipt(byte[] receipt) 
            throws UnknownVersionException, BufferUnderflowException 
    {
        ByteBuffer buf = ByteBuffer.wrap(receipt);
        buf.rewind();
        signature = new byte[64];
        buf.get(signature);
        version = buf.get();
        if (version != 1)
            throw new UnknownVersionException(version);
        publicKey = new byte[32];
        buf.get(publicKey);
        uid = buf.getLong();
        amount = buf.getLong();
        timestamp = buf.getInt();
        comment = new byte[buf.remaining()];
        buf.get(comment);
    }
    
    public byte version;
    byte[] publicKey;
    public long uid;
    public long amount;
    public int timestamp;
    byte[] comment;
    byte[] signature;
}
