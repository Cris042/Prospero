# ADR-003 — Contratos HTTP, versionamento, Swagger e DTOs

- Data: 2026-10-16
- Estado: aceita; implementação pendente
- Decisor: usuário
- Origem: decisão de documentar e testar todos os endpoints de negócio.

## Contexto

Angular e o runner precisam distinguir criação, processamento pendente, conclusão e rejeição. Códigos ou corpos inconsistentes podem provocar leitura indevida de JSON, cache desatualizado, prompts de autenticação ou repetição de comandos. A implementação assíncrona não pode anunciar uma execução financeira que ainda não ocorreu.

## Decisão

Versionar endpoints de negócio no caminho, começando por `/api/v1`. Usar recursos em português, minúsculos, no plural e com hífen quando necessário, por exemplo `/api/v1/ordens`. Mudança incompatível exige nova versão; mudança aditiva preserva os consumidores existentes e seus testes. Endpoints técnicos de saúde, métricas, OpenAPI e Swagger seguem os caminhos do Quarkus, fora desse versionamento.

Todo endpoint terá OpenAPI com parâmetros, DTOs, obrigatoriedade, restrições, exemplos, respostas de sucesso/erro e headers relevantes. Disponibilizar Swagger UI no ambiente local de containers, não apenas em dev mode. Verificar a extensão e os caminhos reais na P02 conforme o [guia oficial do Quarkus](https://quarkus.io/guides/openapi-swaggerui/).

Entradas e respostas com corpo terão DTOs separados, como `OrdemRequestDTO` e `OrdemResponseDTO`. Nunca serializar entidades JPA. GET e DELETE não exigem corpo; `204` não possui corpo nem DTO de resposta. Não criar campos fictícios apenas para satisfazer uma convenção.

### Escolha do código HTTP

O contrato de cada operação justificará os códigos aplicáveis, seguindo a [semântica HTTP](https://www.rfc-editor.org/rfc/rfc9110.html):

| Código | Uso no projeto |
| --- | --- |
| 200 | Sucesso com representação; uma consulta pode mostrar uma operação ainda pendente. |
| 201 | Recurso criado; incluir `Location`. Não significa que a ordem foi executada. |
| 202 | Trabalho aceito e ainda não concluído; devolver identificador e endereço de acompanhamento. |
| 204 | Sucesso sem conteúdo; cliente não deve tentar ler JSON. |
| 400 | JSON, parâmetros ou tipos malformados. |
| 401 / 403 | Autenticação ausente/inválida / permissão insuficiente, quando esses controles existirem. |
| 404 | Recurso inexistente. |
| 405 / 415 | Método / tipo de conteúdo não suportado. |
| 409 | Conflito com estado atual, como cancelamento incompatível com execução concluída. |
| 422 | Entrada compreendida, mas inválida pelas regras semânticas avaliadas nessa requisição. |
| 500 / 503 | Falha inesperada / indisponibilidade temporária. |

Se a validação de risco acontecer depois de `202`, a rejeição será exposta no estado da ordem; não há alteração retroativa do HTTP inicial. A indisponibilidade de um consumidor não implica `503` se o comando puder ser aceito e persistido com segurança. O endpoint deve explicitar se cria um recurso (`201`) ou aceita processamento (`202`); não escolher apenas com base no verbo POST.

Erros HTTP usarão DTO compatível com [Problem Details — RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html), com tipo, título, status, detalhe e referência da ocorrência. Extensões incluirão código estável de negócio e identificador de rastreio; mensagens não expõem SQL, stack traces ou credenciais. A escolha do catálogo de códigos será feita junto aos contratos de cada tarefa.

### Comportamento do navegador e runner

Toda tarefa com endpoint deve verificar status, corpo e headers, inclusive erros. Avaliar `Content-Type`, `Location`, cache e CORS conforme a interação real. Respostas financeiras e de acompanhamento usarão `Cache-Control: no-store` nesta versão local. Se houver origens diferentes, declarar origens e headers necessários, sem habilitar indiscriminadamente credenciais.

Não usar redirecionamentos para representar resultado de negócio ou falha de autenticação de API. O cliente tratará respostas sem corpo e falhas explicitamente. Não repetir POST automaticamente sem contrato de idempotência que evite duplicação. Autenticação e seus efeitos, incluindo `WWW-Authenticate`, serão decididos antes dos endpoints que dela dependam.

## Alternativas e trade-offs

Retornar `200` para qualquer resultado simplificaria um cliente ingênuo, mas esconderia falhas. Expor JPA economizaria DTOs e mapeamentos, mas acoplaria contratos ao banco. Versionamento por header pouparia caminhos; o prefixo foi escolhido por ser mais visível no estudo e nas planilhas.

O custo é manter DTOs, documentação e testes alinhados. Swagger não substitui teste executável nem justifica respostas não implementadas.

## Validação e revisão

Testar contrato, erros e headers por API; testar no cliente a ausência de corpo em `204` e o acompanhamento de `202`. Conferir OpenAPI no container local. Revisar o ADR se uma mudança exigir romper compatibilidade ou adotar outro protocolo de entrada.

Relacionados: [ADR-001](ADR-001-arquitetura-backend.md), [ADR-004](ADR-004-testes-planilha.md).
