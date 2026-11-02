package com.mcp.pikachu.wowlk.domain;

import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Conta a criar, ja validada. A validacao e refeita aqui: nunca confiar no navegador
 * (qualquer um com a chave da fila consegue publicar mensagem).
 */
public record NewAccount(String username, String email, byte[] salt, byte[] verifier, String locale) {

    private static final Pattern USERNAME = Pattern.compile("^[A-Z0-9]{3,16}$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");
    private static final Pattern HEX64 = Pattern.compile("^[0-9a-fA-F]{64}$");

    public static String normalizeUsername(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    public static String normalizeEmail(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValidEmail(String email) {
        return email != null && email.length() <= 254 && EMAIL.matcher(email).matches();
    }

    /** Valida usuario/e-mail; lanca VALIDATION com mensagem curta para o jogador. */
    public static void validateIdentity(String username, String email) {
        if (!USERNAME.matcher(username).matches())
            throw new ApiException(ErrorCode.VALIDATION, "Username must be 3-16 letters (A-Z) or numbers.");
        if (username.startsWith("RNDBOT"))
            throw new ApiException(ErrorCode.VALIDATION, "This username is reserved.");
        if (!isValidEmail(email))
            throw new ApiException(ErrorCode.VALIDATION, "Enter a valid e-mail address.");
    }

    /** A partir de salt/verifier SRP6 calculados no navegador (fila do site). */
    public static NewAccount fromSrp6(String rawUsername, String rawEmail, String saltHex, String verifierHex,
                                      Integer expansion, String locale) {
        String username = normalizeUsername(rawUsername);
        String email = normalizeEmail(rawEmail);
        validateIdentity(username, email);
        if (saltHex == null || verifierHex == null || !HEX64.matcher(saltHex).matches() || !HEX64.matcher(verifierHex).matches())
            throw new ApiException(ErrorCode.VALIDATION, "Invalid SRP6 salt/verifier.");
        if (expansion != null && expansion != 2)
            throw new ApiException(ErrorCode.VALIDATION, "Unsupported expansion.");
        byte[] salt = HexFormat.of().parseHex(saltHex);
        byte[] verifier = HexFormat.of().parseHex(verifierHex);
        return new NewAccount(username, email, salt, verifier, locale);
    }

    /** A partir de senha em texto (rota HTTPS de cadastro); a senha nao e guardada. */
    public static NewAccount fromPassword(String rawUsername, String rawEmail, String password, String locale) {
        String username = normalizeUsername(rawUsername);
        String email = normalizeEmail(rawEmail);
        validateIdentity(username, email);
        if (password == null || password.length() < 8 || password.length() > 16
                || !password.chars().allMatch(c -> c >= 0x21 && c <= 0x7E)
                || password.chars().noneMatch(Character::isLetter) || password.chars().noneMatch(Character::isDigit)
                || password.equalsIgnoreCase(username))
            throw new ApiException(ErrorCode.VALIDATION,
                    "Password must be 8-16 characters with a letter and a number, no spaces, different from the username.");
        byte[] salt = Srp6.randomSalt();
        return new NewAccount(username, email, salt, Srp6.verifier(username, password, salt), locale);
    }

    public boolean prefersPortuguese() {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("pt");
    }
}
