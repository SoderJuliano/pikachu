# Projeto Pikachu

## Descrição

Este projeto é uma aplicação que utiliza diferentes serviços de API de inteligência artificial para processar solicitações de chat e retornar respostas.

## Estrutura do Projeto

- **src/main/java/com/mcp/pikachu/**: Contém a lógica principal da aplicação, incluindo a configuração do aplicativo e as interfaces de serviço.
- **src/main/resources/application.yaml**: Arquivo de configuração do Spring Boot.
- **src/test/java/com/mcp/pikachu/**: Contém os testes unitários para o projeto.

## Serviços Disponíveis

- Gemma3Service.java
- Llama3Service.java
- LlamaTinyService.java
- Qwen25Service.java

## Adaptações de Entrada/ Saída

- src/main/java/com/mcp/pikachu/adapter/in/web/: Contém o controlador web e as exceções globais.
- src/main/java/com/mcp/pikachu/adapter/out/ollama/: Contém a adaptação do cliente para a API Ollama.
## Módulo wowlk — contas do WoW Classic Plus LK (sem IA)

Pacote `com.mcp.pikachu.wowlk`. Cria contas no AzerothCore (`acore_auth`, MariaDB local) e
faz login do launcher. Contratos: `launcher-wowlk-classic-plus/docs/API_CONTRACT.md` e
`wowlk-classic-plus-site/docs/QUEUE_CONTRACT.md`.

**Rotas** (`/launcher/v1`): `GET /health`, `POST /auth/login` (`username` do launcher ou
`login` do site = usuário ou e-mail), `POST /auth/refresh`, `POST /auth/logout`,
`GET /status` (painel do site, sem login, cache de 30 s), `POST /auth/register` (senha ou salt/verifier SRP6), `GET /manifest/{client}` (entrega `manifest-dir/<cliente>.json`; 404 se não existir).

**Fila de cadastro:** a cada 2 min lê a chave de cadastro na Abra API, cria a conta **ativa**
(jogador comum, sem `account_access`), apaga só a mensagem processada (por id) e manda e-mail
texto de "conta ativada". Erro transitório: tenta de novo no próximo ciclo; na 3ª falha manda
e-mail de desculpas e remove. Nome/e-mail em uso ou payload inválido não são re-tentados.
Logs com prefixo `[wowlk]` (`cadastro OK`, `cadastro FALHOU`, `cadastro RECUSADO`).

**Troca de senha:** evento `password.recover` da fila de eventos, processado a cada 2 min. Só aplica
se usuário e e-mail baterem; conta GM/admin fica de fora; 5 tentativas por usuário por hora; derruba as
sessões do launcher. Os outros tipos de evento só são contados no log e continuam na fila.

**Segredos** (no `secrets.txt`, nunca no git):
`WOWLK_QUEUE_REGISTRATION_KEY`, `WOWLK_QUEUE_EVENTS_KEY`, `WOWLK_DB_URL`, `WOWLK_DB_USER`,
`WOWLK_DB_PASSWORD`, opcional `WOWLK_QUEUE_PRIVATE_KEY_PATH` (fila cifrada do site).
O usuário do banco só tem SELECT/INSERT e UPDATE de `salt`/`verifier` em `acore_auth.account`, SELECT em `account_banned`/`account_access`
e tudo em `wowlk_site` (sessões e histórico da fila, criado sozinho).
