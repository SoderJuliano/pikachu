package com.mcp.pikachu.infra;

import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Carrega os segredos (API key) de um arquivo de propriedades.
 *
 * O caminho era FIXO ("/mnt/500gb/app/secrets.txt"), e isso quebra o servidor
 * inteiro toda vez que a maquina e recriada ou o disco monta em outro lugar:
 * o bloco estatico estourava RuntimeException e todo endpoint protegido passava
 * a responder 500 "Failed to load secrets file". Aconteceu numa recriacao do
 * servidor — o caminho continuava no codigo, o disco e que nao estava mais la.
 *
 * Agora o caminho vem de (nesta ordem):
 *   1. -Dpikachu.secrets.path=/caminho/secrets.txt
 *   2. variavel de ambiente PIKACHU_SECRETS_PATH
 *   3. os caminhos conhecidos, testados na ordem
 *
 * E a ausencia do arquivo NAO derruba mais a aplicacao: sem segredo, os
 * endpoints protegidos negam acesso (401), enquanto /chat, /agent e o resto
 * seguem funcionando. Perder a chave nao pode significar perder o servidor.
 */
@Slf4j
public class SecretManager {

    private static final Properties properties = new Properties();
    private static boolean carregado = false;

    private static final List<String> CAMINHOS_PADRAO = List.of(
            "/mnt/500gb/app/secrets.txt",
            System.getProperty("user.home") + "/Documents/app/secrets.txt",
            System.getProperty("user.home") + "/app/secrets.txt",
            "/etc/pikachu/secrets.txt"
    );

    static {
        for (String caminho : candidatos()) {
            if (caminho == null || caminho.isBlank()) continue;
            Path p = Paths.get(caminho);
            if (!Files.isReadable(p)) continue;
            try (FileInputStream input = new FileInputStream(caminho)) {
                properties.load(input);
                carregado = true;
                log.info("Secrets carregados de {}", caminho);
                break;
            } catch (IOException e) {
                log.warn("Falha lendo secrets em {}: {}", caminho, e.getMessage());
            }
        }
        if (!carregado) {
            log.warn("Nenhum arquivo de secrets encontrado (tentados: {}). "
                    + "Endpoints protegidos vao negar acesso; o resto da aplicacao segue no ar. "
                    + "Defina -Dpikachu.secrets.path ou PIKACHU_SECRETS_PATH.", candidatos());
        }
    }

    private static List<String> candidatos() {
        List<String> lista = new ArrayList<>();
        lista.add(System.getProperty("pikachu.secrets.path"));
        lista.add(System.getenv("PIKACHU_SECRETS_PATH"));
        lista.addAll(CAMINHOS_PADRAO);
        return lista;
    }

    /** true se algum arquivo de secrets foi carregado. */
    public static boolean isLoaded() {
        return carregado;
    }

    public static String getSecret(String key) {
        return properties.getProperty(key);
    }
}
