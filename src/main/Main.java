package main;

public class Main {
    // Exemplos de JDK 27

    void main() {
        System.out.println("Hello world do Java 27!");

        char a = 'a';
        int numeroAlto = 150;
        double valorDouble = 42.5;
        String texto = "Pikachu";

        testValue(a);
        testValue(numeroAlto);
        testValue(valorDouble);
        testValue(texto);
    }

    public static void testValue(Object obj) {
        /*
         * =========================================================================
         * ⏪ COMO ERA FEITO NO JAVA 21 (Pattern Matching for switch - JEP 441):
         * =========================================================================
         * No Java 21, pattern matching no switch aceitava APENAS Tipos por Referência
         * (Classes, Wrappers, Records, Strings). Tipos primitivos NÃO eram aceitos.
         *
         * switch (obj) {
         *     case Character c            -> System.out.println("Char: " + c);
         *     case Integer i when i > 100 -> System.out.println("Int alto (>100): " + i);
         *     case Integer i              -> System.out.println("Int normal: " + i);
         *     case Double d               -> System.out.println("Double: " + d);
         *     case String s               -> System.out.println("String: " + s);
         *     default                     -> System.out.println("Outro tipo: " + obj);
         * }
         *
         * Desvantagens no Java 21:
         * 1. Exigia Wrappers (Integer, Character, Double) em vez de tipos primitivos.
         * 2. `instanceof int i` não compilava (exigia `instanceof Integer i`).
         * 3. Sem conversões primitivas automáticas (ex: se passasse short ou byte,
         *    não caía em `case Integer`, precisava de um `case Byte` explícito).
         * =========================================================================
         * ⏩ COMO FICA NO JAVA 27 (Primitive Types in Patterns - JEP 532):
         * =========================================================================
         * Tipos primitivos (char, int, double, byte...) são suportados diretamente
         * no pattern matching, tanto em `instanceof` quanto em `switch`.
         */

        // 1. Pattern Matching com instanceof no Java 27 (agora aceita tipos primitivos!)
        if (obj instanceof byte b) {
            System.out.println("Byte detectado: " + b);
        } else if (obj instanceof int i) {
            System.out.println("Inteiro detectado com instanceof: " + i);
        }

        // 2. Switch com tipos primitivos e Guards no Java 27
        switch (obj) {
            case char c            -> System.out.println("Char: " + c);
            case int i when i > 100 -> System.out.println("Int alto (>100): " + i);
            case int i             -> System.out.println("Int normal: " + i);
            case double d          -> System.out.println("Double: " + d);
            case String s          -> System.out.println("String: " + s);
            default                -> System.out.println("Outro tipo: " + obj);
        }
    }
}
