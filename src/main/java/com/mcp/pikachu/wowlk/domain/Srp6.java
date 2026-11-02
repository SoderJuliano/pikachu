package com.mcp.pikachu.wowlk.domain;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * SRP6 do AzerothCore (SRP6.cpp): v = g^x mod N, x = SHA1(salt || SHA1(USER:PASS)),
 * todos os numeros em little-endian de 32 bytes — exatamente o que fica em
 * {@code account.salt} / {@code account.verifier}. Mesmo algoritmo do site e do contrato do launcher.
 */
public final class Srp6 {

    private static final BigInteger N =
            new BigInteger("894B645E89E1535BBDAD5B8B290650530801B18EBFBF5E8FAB3C82872A3E9BB7", 16);
    private static final BigInteger G = BigInteger.valueOf(7);
    private static final SecureRandom RANDOM = new SecureRandom();

    private Srp6() {
    }

    public static byte[] randomSalt() {
        byte[] salt = new byte[32];
        RANDOM.nextBytes(salt);
        return salt;
    }

    public static byte[] verifier(String username, String password, byte[] salt) {
        // O cliente 3.3.5a so faz upper-case de A-Z (Latin); igual ao site (upperLatin).
        byte[] h1 = sha1((upperLatin(username) + ":" + upperLatin(password)).getBytes(StandardCharsets.UTF_8));
        BigInteger x = fromLittleEndian(sha1(salt, h1));
        return toLittleEndian32(G.modPow(x, N));
    }

    public static boolean matches(String username, String password, byte[] salt, byte[] storedVerifier) {
        return MessageDigest.isEqual(verifier(username, password, salt), storedVerifier);
    }

    static String upperLatin(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) sb.append(c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
        return sb.toString();
    }

    private static byte[] sha1(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigInteger fromLittleEndian(byte[] le) {
        byte[] be = new byte[le.length];
        for (int i = 0; i < le.length; i++) be[i] = le[le.length - 1 - i];
        return new BigInteger(1, be);
    }

    private static byte[] toLittleEndian32(BigInteger v) {
        byte[] be = v.toByteArray(); // pode vir com 0x00 de sinal na frente ou menor que 32
        byte[] le = new byte[32];
        for (int i = 0; i < 32 && i < be.length; i++) le[i] = be[be.length - 1 - i];
        return le;
    }
}
