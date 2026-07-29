package com.mcp.pikachu.infra;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.*;

@Slf4j
@Component
public class TimeoutFilter implements Filter {

    // A chave anterior era "app.timeout.qwen", que NAO existe no
    // application.yaml — o default de 120s do proprio @Value era o valor real em
    // producao, metade do que o yaml declara. Agora le a chave que existe.
    @Value("${app.timeout.default:240}")
    private long timeoutSeconds;

    // Endpoints de streaming (SSE) gerenciam o proprio timeout e nao podem ser
    // interrompidos por este filtro: a resposta ja foi parcialmente enviada, e
    // cancelar a thread no meio derruba a conexao. No cliente (undici/fetch) isso
    // chega como o erro opaco "terminated".
    //
    // /chat ficou de fora desta lista quando foi criado. Resultado: TODA resposta
    // do modo IDE que passasse de 120s (modelo com raciocinio + prompt grande e
    // varias iteracoes de ferramenta passam disso com folga) morria no meio do
    // stream, sem mensagem de erro util.
    private static final Set<String> STREAMING_PATHS = Set.of(
            "/qwen3.6-17b",
            "/llama3-stream",
            "/chat"
    );

    private static final String SSE_CONTENT_TYPE = "text/event-stream";

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (request instanceof HttpServletRequest httpRequest && isStreaming(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        Future<?> future = executor.submit(() -> {
            try {
                chain.doFilter(request, response);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        try {
            future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Request timeout after {}s", timeoutSeconds);
            // Se algo ja foi escrito, mexer no status/body corrompe a resposta.
            if (!httpResponse.isCommitted()) {
                httpResponse.setStatus(HttpServletResponse.SC_GATEWAY_TIMEOUT);
                httpResponse.getWriter().write("Request timeout");
            }
        } catch (Exception e) {
            log.error("Request failed in TimeoutFilter", e);
            if (!httpResponse.isCommitted()) {
                httpResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }

    // Rede de seguranca alem da lista: qualquer endpoint novo que produza SSE
    // (declarado no produces ou pedido pelo Accept do cliente) tambem escapa do
    // filtro, sem precisar lembrar de vir editar este arquivo.
    private boolean isStreaming(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && STREAMING_PATHS.contains(uri)) return true;
        String accept = request.getHeader("Accept");
        return accept != null && accept.toLowerCase().contains(SSE_CONTENT_TYPE);
    }
}
