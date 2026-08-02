package com.mcp.pikachu.domain.model;

import java.util.List;
import java.util.Map;

/**
 * Requisicao do modo AGENTE — conversa estruturada com tool calling NATIVO.
 *
 * Diferente de ChatRequest, que carrega UMA string concatenada (system + pedido
 * + resultados de ferramenta + texto do proprio modelo, tudo colado). Naquele
 * formato o modelo nao tem fronteira entre "o que eu disse", "o que a ferramenta
 * devolveu" e "o que o usuario pediu" — ele reinfere isso da formatacao a cada
 * rodada, e erra.
 *
 * Pior: com a chamada de ferramenta sendo TEXTO que o modelo escreve, "planejei
 * a chamada" e "emiti a chamada" viram a mesma coisa pra ele. Medido no fio: o
 * modelo escrevia TOOL_CALL 13 vezes dentro do proprio raciocinio, se convencia
 * de que tinha executado, e a rodada terminava sem emitir nada.
 *
 * Aqui cada mensagem tem PAPEL (system/user/assistant/tool) e as ferramentas
 * chegam no formato nativo do Ollama, entao emitir uma chamada e uma acao
 * tipada — impossivel de confundir com pensamento.
 *
 * @param messages conversa completa; cada item {role, content} e, quando role
 *                 for "tool", tambem {tool_name}
 * @param tools    schema das ferramentas no formato do Ollama
 *                 ({type:"function", function:{name, description, parameters}})
 * @param language idioma da resposta
 */
public record AgentRequest(
        List<Map<String, Object>> messages,
        List<Map<String, Object>> tools,
        String language
) {}
