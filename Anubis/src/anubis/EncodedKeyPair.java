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
import java.security.KeyPair;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

/**
 * A KeyPair that we can serialize.
 * 
 * The public key object is restored from the encoded private key object.
 * That public key object is different from the one from keypair generation 
 *   for some reason, as it blows up if we try to getEncoded() it.
 */
public class EncodedKeyPair implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final byte[] prkEncoded;
    
    public EncodedKeyPair(byte[] prkEncoded) {
        this.prkEncoded = prkEncoded.clone();
    }

    public EncodedKeyPair(EdDSAPrivateKey prk) {
        this(prk.getEncoded());
    }
    
    public KeyPair getKeypair() throws InvalidKeySpecException {
        PKCS8EncodedKeySpec prkEncSpec = new PKCS8EncodedKeySpec( prkEncoded );
        EdDSAPrivateKey prk = new EdDSAPrivateKey(prkEncSpec);
        EdDSAParameterSpec spec = EdDSANamedCurveTable.getByName("ED25519-SHA-512");
        EdDSAPublicKeySpec pubKeySpec = new EdDSAPublicKeySpec(prk.getA(), spec);
        EdDSAPublicKey puk = new EdDSAPublicKey(pubKeySpec);
        KeyPair keyPair = new KeyPair(puk, prk);
        return keyPair;
    }
}
