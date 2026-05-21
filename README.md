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