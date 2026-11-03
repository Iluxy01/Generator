package app.holst;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** secp256k1 и подписи BIP-340: нужны, чтобы ретрансляторы Nostr принимали наши события. */
final class Crypto {
    private static final BigInteger P =
            new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
    static final BigInteger N =
            new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
    private static final BigInteger GX =
            new BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16);
    private static final BigInteger GY =
            new BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16);
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger THREE = BigInteger.valueOf(3);
    private static final BigInteger FOUR = BigInteger.valueOf(4);
    private static final BigInteger EIGHT = BigInteger.valueOf(8);
    private static final BigInteger SEVEN = BigInteger.valueOf(7);

    private Crypto() {}

    /** Точка в координатах Якоби; null означает бесконечность. */
    private static final class Pt {
        final BigInteger x, y, z;

        Pt(BigInteger x, BigInteger y, BigInteger z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static Pt dbl(Pt p) {
        if (p == null || p.y.signum() == 0) return null;
        BigInteger yy = p.y.multiply(p.y).mod(P);
        BigInteger s = FOUR.multiply(p.x).multiply(yy).mod(P);
        BigInteger m = THREE.multiply(p.x).multiply(p.x).mod(P);
        BigInteger x3 = m.multiply(m).subtract(TWO.multiply(s)).mod(P);
        BigInteger y3 = m.multiply(s.subtract(x3)).subtract(EIGHT.multiply(yy).multiply(yy)).mod(P);
        BigInteger z3 = TWO.multiply(p.y).multiply(p.z).mod(P);
        return new Pt(x3, y3, z3);
    }

    private static Pt add(Pt a, Pt b) {
        if (a == null) return b;
        if (b == null) return a;
        BigInteger z1z1 = a.z.multiply(a.z).mod(P);
        BigInteger z2z2 = b.z.multiply(b.z).mod(P);
        BigInteger u1 = a.x.multiply(z2z2).mod(P);
        BigInteger u2 = b.x.multiply(z1z1).mod(P);
        BigInteger s1 = a.y.multiply(z2z2).multiply(b.z).mod(P);
        BigInteger s2 = b.y.multiply(z1z1).multiply(a.z).mod(P);
        if (u1.equals(u2)) {
            return s1.equals(s2) ? dbl(a) : null;
        }
        BigInteger h = u2.subtract(u1).mod(P);
        BigInteger r = s2.subtract(s1).mod(P);
        BigInteger hh = h.multiply(h).mod(P);
        BigInteger hhh = hh.multiply(h).mod(P);
        BigInteger v = u1.multiply(hh).mod(P);
        BigInteger x3 = r.multiply(r).subtract(hhh).subtract(TWO.multiply(v)).mod(P);
        BigInteger y3 = r.multiply(v.subtract(x3)).subtract(s1.multiply(hhh)).mod(P);
        BigInteger z3 = h.multiply(a.z).multiply(b.z).mod(P);
        return new Pt(x3, y3, z3);
    }

    private static Pt mul(BigInteger k, Pt p) {
        Pt r = null;
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = dbl(r);
            if (k.testBit(i)) r = add(r, p);
        }
        return r;
    }

    /**
     * Заранее посчитанные кратные G по четыре бита: подпись становится в несколько раз быстрее,
     * а значит и живые штрихи не копятся в очереди на отправку.
     */
    private static final class GTable {
        static final Pt[][] T = build();

        private static Pt[][] build() {
            Pt[][] t = new Pt[64][16];
            Pt base = g();
            for (int i = 0; i < 64; i++) {
                t[i][1] = base;
                for (int j = 2; j < 16; j++) t[i][j] = add(t[i][j - 1], base);
                for (int d = 0; d < 4; d++) base = dbl(base);
            }
            return t;
        }
    }

    private static Pt mulG(BigInteger k) {
        Pt r = null;
        for (int i = 0; i < 64; i++) {
            int digit = (k.testBit(i * 4) ? 1 : 0) | (k.testBit(i * 4 + 1) ? 2 : 0)
                    | (k.testBit(i * 4 + 2) ? 4 : 0) | (k.testBit(i * 4 + 3) ? 8 : 0);
            if (digit != 0) r = add(r, GTable.T[i][digit]);
        }
        return r;
    }

    /** Возвращает {x, y} в обычных координатах. */
    private static BigInteger[] affine(Pt p) {
        BigInteger zi = p.z.modInverse(P);
        BigInteger zi2 = zi.multiply(zi).mod(P);
        return new BigInteger[] {p.x.multiply(zi2).mod(P), p.y.multiply(zi2).multiply(zi).mod(P)};
    }

    private static Pt g() {
        return new Pt(GX, GY, BigInteger.ONE);
    }

    static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) d.update(part);
            return d.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] tagged(String tag, byte[]... parts) {
        byte[] h = sha256(tag.getBytes(StandardCharsets.UTF_8));
        byte[][] all = new byte[parts.length + 2][];
        all[0] = h;
        all[1] = h;
        System.arraycopy(parts, 0, all, 2, parts.length);
        return sha256(all);
    }

    private static byte[] bytes32(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        int len = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - len, out, 32 - len, len);
        return out;
    }

    private static BigInteger num(byte[] b) {
        return new BigInteger(1, b);
    }

    /** Открытый ключ BIP-340: только координата x. */
    static byte[] publicKey(byte[] secret) {
        return bytes32(affine(mulG(num(secret)))[0]);
    }

    /** Подпись одним ключом: открытая часть считается один раз. */
    static final class Signer {
        private final BigInteger d;
        private final byte[] px;

        Signer(byte[] secret) {
            BigInteger d0 = num(secret);
            if (d0.signum() == 0 || d0.compareTo(N) >= 0) {
                throw new IllegalArgumentException("bad key");
            }
            BigInteger[] pub = affine(mulG(d0));
            d = pub[1].testBit(0) ? N.subtract(d0) : d0;
            px = bytes32(pub[0]);
        }

        byte[] sign(byte[] msg32, byte[] aux32) {
            byte[] t = bytes32(d);
            byte[] auxHash = tagged("BIP0340/aux", aux32);
            for (int i = 0; i < 32; i++) t[i] ^= auxHash[i];
            BigInteger k0 = num(tagged("BIP0340/nonce", t, px, msg32)).mod(N);
            if (k0.signum() == 0) throw new IllegalStateException("zero nonce");
            BigInteger[] r = affine(mulG(k0));
            BigInteger k = r[1].testBit(0) ? N.subtract(k0) : k0;
            byte[] rx = bytes32(r[0]);
            BigInteger e = num(tagged("BIP0340/challenge", rx, px, msg32)).mod(N);
            byte[] s = bytes32(k.add(e.multiply(d)).mod(N));
            byte[] sig = new byte[64];
            System.arraycopy(rx, 0, sig, 0, 32);
            System.arraycopy(s, 0, sig, 32, 32);
            return sig;
        }
    }

    static byte[] sign(byte[] secret, byte[] msg32, byte[] aux32) {
        return new Signer(secret).sign(msg32, aux32);
    }

    static boolean verify(byte[] pub, byte[] msg32, byte[] sig) {
        BigInteger x = num(pub);
        if (x.compareTo(P) >= 0 || sig.length != 64) return false;
        BigInteger c = x.modPow(THREE, P).add(SEVEN).mod(P);
        BigInteger y = c.modPow(P.add(BigInteger.ONE).divide(FOUR), P);
        if (!y.multiply(y).mod(P).equals(c)) return false;
        if (y.testBit(0)) y = P.subtract(y);
        byte[] rb = new byte[32];
        byte[] sb = new byte[32];
        System.arraycopy(sig, 0, rb, 0, 32);
        System.arraycopy(sig, 32, sb, 0, 32);
        BigInteger r = num(rb);
        BigInteger s = num(sb);
        if (r.compareTo(P) >= 0 || s.compareTo(N) >= 0) return false;
        BigInteger e = num(tagged("BIP0340/challenge", rb, pub, msg32)).mod(N);
        Pt q = new Pt(x, y, BigInteger.ONE);
        Pt res = add(mul(s, g()), mul(N.subtract(e), q));
        if (res == null) return false;
        BigInteger[] a = affine(res);
        return !a[1].testBit(0) && a[0].equals(r);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) {
            sb.append(Character.forDigit((v >> 4) & 0xF, 16));
            sb.append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }

    static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
