package com.mcp.pikachu.application;

import com.mcp.pikachu.domain.model.ChatRequest;
import com.mcp.pikachu.domain.port.in.Llama3RagUseCase;
import com.mcp.pikachu.domain.port.out.LlmClientPort;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Serviço didático de RAG (Retrieval-Augmented Generation) para o modelo Llama 3.
 * 
 * Fluxo RAG:
 * 1. Ingestion: Carrega e divide a base de conhecimento (chunks) na inicialização.
 * 2. Retrieval: Busca os trechos mais relevantes para a pergunta do usuário.
 * 3. Augmentation: Injeta os trechos encontrados no prompt como contexto técnico.
 * 4. Generation: Envia o prompt enriquecido para o Llama 3 gerar a resposta final.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Llama3RagService implements Llama3RagUseCase {

    private static final String KNOWLEDGE_FILE = "java_17_27_spring_boot_2_6_4_2_finetuning.txt";
    private static final String DEFAULT_MODEL = "llama3";
    private static final int MAX_RETRIEVED_CHUNKS = 3;

    private final LlmClientPort llmClientPort;
    private final List<String> knowledgeChunks = new ArrayList<>();
    private String fullKnowledgeFallback = "";

    @PostConstruct
    public void initKnowledgeBase() {
        log.info("Inicializando Base de Conhecimento RAG a partir de [{}]...", KNOWLEDGE_FILE);
        try {
            ClassPathResource resource = new ClassPathResource(KNOWLEDGE_FILE);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String fullText = reader.lines().collect(Collectors.joining("\n"));
                this.fullKnowledgeFallback = fullText;

                // Divide a base por seções lógicas (delimitadores de seção ou blocos)
                String[] sections = fullText.split("(?m)^[-=]{20,}\\s*$");
                for (String section : sections) {
                    String trimmed = section.trim();
                    if (!trimmed.isEmpty() && trimmed.length() > 50) {
                        knowledgeChunks.add(trimmed);
                    }
                }
                log.info("Base RAG carregada com sucesso: {} chunks criados.", knowledgeChunks.size());
            }
        } catch (Exception e) {
            log.error("Erro ao carregar base de conhecimento RAG: {}", e.getMessage(), e);
        }
    }

    @Override
    public String execute(ChatRequest request) {
        log.info("Processando requisição Llama3 com RAG...");

        String userPrompt = request.prompt() == null ? "" : request.prompt();

        // 1. ETAPA DE RETRIEVAL (Recuperação dos trechos mais relevantes)
        String retrievedContext = retrieveRelevantContext(userPrompt);

        // 2. ETAPA DE AUGMENTATION (Enriquecimento do Prompt com o Contexto RAG)
        String augmentedPrompt = buildAugmentedPrompt(userPrompt, retrievedContext);

        // 3. ETAPA DE GENERATION (Chamada ao Modelo Llama 3)
        ChatRequest ragRequest = new ChatRequest(augmentedPrompt, request.language(), request.imageBase64());
        return llmClientPort.callModel(DEFAULT_MODEL, ragRequest);
    }

    /**
     * Recupera os chunks mais relevantes baseado na pontuação de termos da pergunta.
     */
    private String retrieveRelevantContext(String query) {
        if (knowledgeChunks.isEmpty()) {
            return fullKnowledgeFallback;
        }

        Set<String> queryTerms = extractQueryTerms(query);
        if (queryTerms.isEmpty()) {
            // Se a pergunta for genérica, utiliza o cabeçalho/resumo inicial
            return knowledgeChunks.get(0);
        }

        // Pontuação de cada chunk com base na frequência e relevância dos termos
        List<ScoredChunk> scoredChunks = new ArrayList<>();
        for (String chunk : knowledgeChunks) {
            int score = calculateRelevance(chunk, queryTerms, query);
            scoredChunks.add(new ScoredChunk(chunk, score));
        }

        // Ordena pelos maiores scores e seleciona os TOP-N chunks
        scoredChunks.sort((a, b) -> Integer.compare(b.score, a.score));

        List<String> topChunks = scoredChunks.stream()
                .filter(sc -> sc.score > 0)
                .limit(MAX_RETRIEVED_CHUNKS)
                .map(sc -> sc.chunk)
                .toList();

        if (topChunks.isEmpty()) {
            // Se nenhum termo específico pontuar, retorna o chunk inicial (resumo executivo)
            return knowledgeChunks.get(0);
        }

        return String.join("\n\n---\n\n", topChunks);
    }

    private int calculateRelevance(String chunk, Set<String> queryTerms, String fullQuery) {
        String lowerChunk = chunk.toLowerCase();
        int score = 0;

        // Bônus para correspondência de frase exata (ex: "Java 27", "Spring Boot 4", "ano atual")
        String lowerQuery = fullQuery.toLowerCase().trim();
        if (lowerQuery.length() > 3 && lowerChunk.contains(lowerQuery)) {
            score += 20;
        }

        // Pontuação por termo individual
        for (String term : queryTerms) {
            if (lowerChunk.contains(term)) {
                score += 5;
                // Termos numéricos ou versões ganham peso extra (ex: "27", "25", "4.1", "2026")
                if (term.matches(".*\\d+.*")) {
                    score += 10;
                }
            }
        }
        return score;
    }

    private Set<String> extractQueryTerms(String query) {
        if (query == null) return Collections.emptySet();
        Set<String> stopwords = Set.of(
                "o", "a", "os", "as", "um", "uma", "de", "do", "da", "dos", "das", "em", "no", "na",
                "nos", "nas", "por", "para", "com", "como", "qual", "quais", "que", "quem", "e", "ou",
                "se", "the", "of", "in", "and", "to", "is", "what", "which", "how"
        );

        return Arrays.stream(query.toLowerCase().split("[^a-zA-Z0-9_.-]+"))
                .filter(w -> w.length() > 1 && !stopwords.contains(w))
                .collect(Collectors.toSet());
    }

    private String buildAugmentedPrompt(String userPrompt, String context) {
        return """
                Você é um assistente técnico especialista em Java e Spring Boot com acesso a uma base de conhecimento atualizada via RAG (Retrieval-Augmented Generation).
                
                Utilize EXCLUSIVAMENTE o contexto técnico fornecido abaixo para responder à pergunta do usuário de forma clara, direta e precisa. Se o contexto contiver informações específicas sobre datas, anos, versões ou recursos (como Java 27, ano de 2026, Spring Boot 4), priorize-as com exatidão.
                
                === CONTEXTO RECUPERADO DA BASE DE CONHECIMENTO (RAG) ===
                %s
                ========================================================
                
                PERGUNTA DO USUÁRIO:
                %s
                """.formatted(context, userPrompt);
    }

    private record ScoredChunk(String chunk, int score) {}
}
