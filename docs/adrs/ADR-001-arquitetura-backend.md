# ADR-001 — Arquitetura e organização do backend

- Data: 2026-10-16
- Estado: aceita; implementação pendente
- Decisor: usuário
- Origem: planejamento do Prospero e [proposta histórica no Git](https://github.com/mclovin137/Prospero/blob/c84aea222a17c28422e0050df9f477b487980470/prospero-arquitetura.md), seções 5, 6 e 14.

## Contexto

O projeto precisa demonstrar o ciclo de negociação em quatro contextos independentes, rodando localmente sem serviços pagos. O usuário substituiu Spring Boot/JDBC por Quarkus, Java 26 e JPA e escolheu isolamento físico dos bancos. A [ADR de estrutura do Minerva](https://github.com/Cris042/MinervaFinancas/blob/34fc98a6ac47f2ffc2bf23f055a3fd9ac15c7530/docs/adrs/adr-003-estrutura-do-backend.md) é referência de organização por funcionalidade, não um contrato importado integralmente.

## Decisão

Adotar os serviços `front-office`, `middle-office`, `matching-engine` e `back-office`, cada um com build Maven Wrapper, configuração, imagem, migrações e testes executáveis independentemente. Um build agregador pode facilitar o trabalho local, sem ser obrigatório para compilar um serviço.

Cada serviço terá um container PostgreSQL com banco e credenciais exclusivos. Um serviço não acessa tabelas de outro, compartilha entidades persistidas ou depende do artefato de implementação de outro microsserviço. O matching mantém o livro e seu algoritmo em memória, sem consulta JPA por ordem; seu banco fica disponível para persistência de suporte definida em tarefa própria, sem inventar tabelas para justificar sua existência.

Serviços colaboram via Kafka, com contratos de eventos versionados e correlação. Não haverá chamadas HTTP entre microsserviços. HTTP é a fronteira para Angular e consumidores de teste. A queda de um participante pode atrasar a conclusão da operação; autonomia exige inicialização e recuperação próprias, não conclusão fictícia sem participantes.

Organizar pacotes sob `br.prospero.<contexto>.<funcionalidade>`, com subpacotes criados conforme a necessidade:

```text
ordem/
  rest/      # entrada HTTP e mapeamento de status
  actor/     # coordenação do caso de uso e transação local
  helper/    # apoio puro, sem estado e sem I/O
  dominio/   # entidades de negócio, objetos de valor e invariantes
  dto/       # contratos de entrada e saída
  jpa/       # entidades de persistência e repositórios
```

REST chama Actor. Actor utiliza domínio/Helpers puros e repositórios JPA separadamente; Helper não acessa banco, Kafka ou HTTP. Entidades de domínio não dependem de Quarkus, HTTP ou JPA. Adaptadores de Kafka também acionam casos de uso sem passar pela camada REST. Repositórios são a fronteira de persistência; domínio nunca recebe `EntityManager`.

Usar nomes descritivos: `EnviarOrdemActor`, `OrdemRequestDTO`, `OrdemResponseDTO`, `OrdemRepository`. Classes usam PascalCase e membros camelCase. Indentação Java/TypeScript: quatro/dois espaços; YAML: dois espaços. A ferramenta de formatação e convenções adicionais serão verificadas na P02. Não há obrigação de criar interface ou camada vazia para toda classe.

Docker Compose executará os quatro serviços Java, Angular, Kafka, quatro PostgreSQL, Redis e observabilidade. Java 26 é uma escolha aceita, mas a combinação de JDK, Quarkus, plugins e imagens ainda precisa de verificação na P02. Se houver incompatibilidade, apresentar evidências e alternativas ao usuário; não rebaixar Java silenciosamente. Compilação nativa não é requisito.

## Alternativas e trade-offs

- Spring/JDBC: preservaria a proposta inicial, mas contraria a stack escolhida.
- Um PostgreSQL com bancos separados: consumiria menos recursos; descartado em favor do isolamento por container.
- Integração HTTP síncrona: simplificaria algumas consultas, mas introduziria acoplamento de disponibilidade entre serviços.
- Domínio anotado como entidade JPA: reduziria mapeamentos; descartado para preservar testes puros e separar modelo financeiro de persistência.

A decisão aumenta o consumo local de memória e a quantidade de configurações e mapeamentos. Em troca, explicita fronteiras, falhas e autonomia. Contratos de eventos ainda exigem coordenação de evolução entre produtores e consumidores.

## Validação e revisão

Na P02, compilar cada serviço isoladamente e verificar credenciais exclusivas, migrações e inicialização sem outro serviço Java disponível. Testes de arquitetura devem impedir REST → JPA e dependências de infraestrutura no domínio. A integração validará recuperação quando um consumidor ficar indisponível. Rever se o custo local inviabilizar a execução ou a separação provocar ciclos entre contextos.

Relacionados: [ADR-002](ADR-002-padroes-dominio.md), [ADR-003](ADR-003-contratos-api.md), [regras](../rules.md).

Nota documental — P08: a origem acima é histórica; o desenho vigente está no [HLD do ecossistema](../hlds/HLD-001-arquitetura-prospero.md) e HLDs dos serviços. Esta migração não altera o contexto ou a decisão.
