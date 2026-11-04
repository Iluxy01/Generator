package app.holst;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CryptoTest {
    private static final String ZERO32 = "0000000000000000000000000000000000000000000000000000000000000000";

    @Test
    public void bip340Vector0() {
        byte[] sk = Crypto.unhex("0000000000000000000000000000000000000000000000000000000000000003");
        assertEquals("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9",
                Crypto.hex(Crypto.publicKey(sk)));
        byte[] sig = Crypto.sign(sk, Crypto.unhex(ZERO32), Crypto.unhex(ZERO32));
        assertEquals("e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca8215"
                + "25f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0", Crypto.hex(sig));
    }

    @Test
    public void bip340Vector1() {
        byte[] sk = Crypto.unhex("b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef");
        byte[] aux = Crypto.unhex("0000000000000000000000000000000000000000000000000000000000000001");
        byte[] msg = Crypto.unhex("243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89");
        byte[] pub = Crypto.publicKey(sk);
        assertEquals("dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659", Crypto.hex(pub));
        byte[] sig = Crypto.sign(sk, msg, aux);
        assertEquals("6896bd60eeae296db48a229ff71dfe071bde413e6d43f917dc8dcf8c78de3341"
                + "8906d11ac976abccb20b091292bff4ea897efcb639ea871cfa95f6de339e4b0a", Crypto.hex(sig));
        assertTrue(Crypto.verify(pub, msg, sig));
        sig[5] ^= 1;
        assertFalse(Crypto.verify(pub, msg, sig));
    }

    @Test
    public void roomIsDeterministicAndEncrypts() {
        Room a = Room.derive("482177301956", Room.newDeviceSecret());
        Room b = Room.derive("482177301956", Room.newDeviceSecret());
        Room other = Room.derive("111111111111", Room.newDeviceSecret());
        assertEquals("у всех с одним кодом одна метка", a.tag, b.tag);
        assertEquals(a.legacyPubHex, b.legacyPubHex);
        assertFalse("а ключи подписи разные", a.pubHex.equals(b.pubHex));
        assertFalse(a.tag.equals(other.tag));
        String ct = a.encrypt("{\"k\":\"s\"}");
        assertEquals("{\"k\":\"s\"}", b.decrypt(ct));
        assertNull(other.decrypt(ct));
    }

    @Test
    public void eventSignatureIsValid() {
        Room r = Room.derive("482177301956", Room.newDeviceSecret());
        Room.Event e = r.event(30078, "abc", r.encrypt("x"), 1700000000L);
        String sig = e.json.substring(e.json.indexOf("\"sig\":\"") + 7, e.json.length() - 2);
        assertTrue(Crypto.verify(Crypto.unhex(r.pubHex), Crypto.unhex(e.id), Crypto.unhex(sig)));
    }
}
