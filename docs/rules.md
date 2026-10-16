# Regras do projeto Prospero

## Autoridade e escopo

Este documento é o contrato canônico, independente de ferramenta, para pessoas e agentes. O [AGENTS.md](../AGENTS.md) aponta para ele. Instruções explícitas atuais do usuário prevalecem; decisões arquiteturais e seus motivos ficam nos [ADRs](adrs/README.md). Divergências materiais devem ser expostas, não resolvidas silenciosamente.

A [arquitetura original](../prospero-arquitetura.md) continua como referência do domínio e dos requisitos. As substituições tecnológicas e estruturais são enumeradas no índice dos ADRs. Não importar regras do Minerva relativas a deploy, Obsidian, agentes ou fornecedores sem decisão para o Prospero.

O projeto é de estudo, roda localmente e não contrata serviços, hospedagem, APIs ou ferramentas pagas. Não há dados nem operações financeiras reais. Saídas autorais, documentação, commits e PRs serão em pt-BR; identificadores e termos técnicos podem manter suas convenções.

## Arquitetura e código

- Quarkus, Java 26 e Maven Wrapper nos quatro serviços. Cada um deve compilar e testar de forma independente; versões exatas estão registradas em libs.md e ADR-005.
- Docker Compose para todos os componentes: quatro aplicações Java, Angular, Kafka, quatro PostgreSQL, Redis, OpenTelemetry Collector, Loki, Grafana, Jaeger e Prometheus. O runner e o ai-memory terão execução local própria, sem serviços pagos.
- Cada serviço é dono de seu banco, usuário e migrações. Proibidos acessos a bancos alheios, entidades JPA compartilhadas entre serviços e dependências de código de implementação de outro microsserviço.
- Integração entre serviços por contratos de eventos Kafka. Autonomia não significa que uma operação ponta a ponta possa terminar sem seus participantes: indisponibilidade deve permitir espera, recuperação ou compensação explícita.
- Siga [ADR-001](adrs/ADR-001-arquitetura-backend.md) e [ADR-002](adrs/ADR-002-padroes-dominio.md): DDD, domínio puro, Actors, Helpers sem estado, persistência isolada, SAGA, outbox e idempotência.
- Valores monetários, quantidades e preços usam objetos de valor sem `double`. Escalas, arredondamento, overflow e calendário precisam de definição antes das respectivas implementações.
- Não criar biblioteca compartilhada por antecipação. A necessidade e o acoplamento de qualquer contrato ou tipo compartilhado devem ser justificados.

## Contratos HTTP e testes

Todo endpoint de negócio segue [ADR-003](adrs/ADR-003-contratos-api.md): `/api/v1`, OpenAPI/Swagger, DTOs e códigos HTTP adequados. GET não exige corpo; respostas sem conteúdo não ganham DTO artificial. Status HTTP descreve o resultado da requisição, não presume conclusão de uma SAGA.

Todo endpoint de negócio deve ter teste automatizado por API, incluindo casos de sucesso e erros aplicáveis. A elegibilidade da planilha é regida pelo [ADR-004](adrs/ADR-004-testes-planilha.md). Seus cenários, incluindo carga e verificação, só podem usar APIs. Testes internos complementam precisão, determinismo, concorrência, persistência e arquitetura; não estão proibidos.

Não há percentual mínimo de cobertura aprovado. Cada tarefa deve demonstrar os comportamentos e invariantes que altera, sem substituir evidências por um número de cobertura. Não usar sleeps fixos para validar fluxos assíncronos: definir condição observável, intervalo e prazo máximo.

## Roadmap, branches e PRs

1. Leia a tarefa no [roadmap](roadmap.md), seus requisitos, dependências e ADRs. Resolva decisões que bloqueiam o aceite antes de codificar.
2. Use branch própria, por exemplo `task/p01-governanca` ou `task/rf05-matching`. Faça commits pequenos, com assunto imperativo em pt-BR; não existe convenção histórica a preservar neste workspace.
3. Entregue implementação, testes proporcionais, documentação e evidências. Atualize [libs](libs.md) na mesma tarefa que introduzir ou alterar dependências.
4. Abra um PR por tarefa usando o [modelo](../.github/PULL_REQUEST_TEMPLATE.md): o que mudou, por quê, trade-offs, requisitos, validação e limitações. Não faça merge automaticamente; a aprovação da entrega cabe ao usuário.
5. Registre URL e estado do PR no roadmap e no ai-memory. Sem Git, remoto ou autenticação, prepare o resumo local e informe o impedimento; isso não equivale a abrir um PR. Não marque a tarefa concluída antes da revisão e do merge. O usuário autorizou continuar a P02 e a governança de skills/CI enquanto o destino remoto da P01 ainda está pendente; isso não transforma a P01 em mergeada.

## Skills e verificação independente

Use o [catálogo local](skills/README.md) para escolher skills por necessidade, não por palavra-chave. As 14 skills estão em `.agents/skills/`, com adaptadores locais e fontes/checksums fixados. Ler o adaptador antes do upstream; comandos externos, merge e release dependem do escopo autorizado, não da instalação da skill.

Revisão, testes e segurança devem rodar em **agentes separados entre si e do implementador, todos com esforço medium**. A revisão usa `pr-audit`, testes usam `tdd-workflow`/planejamento de evidências e segurança usa `security-audit`. Relatórios registram escopo, comandos/resultados, achados e limites. Nenhum agente aprova o próprio trabalho como revisão independente. O usuário decide merge.

Toda nova tarefa do roadmap inicia obrigatoriamente por `refinar-task`: consultar ai-memory, regras e ADRs, distribuir um brief comum para cinco perspectivas independentes medium e consolidar critérios antes da implementação. Respeitar slots do runtime, em lotes quando necessário. Registrar o refinamento no projeto e sua síntese na memória. Não manter `continuidade.md`.

O fluxo da tarefa inclui refinamento multiagente e planejamento de verificação, implementação/TDD, execuções independentes, correções, revisão/segurança e PR. Usar `criar-migration`, `gerar-adr`, `worktrees`, `iss-audit`, `deep-research`, `loop-engineering`, `humanizer` e `release` nos gatilhos do catálogo. Não exigir runtime OMO, Obsidian nem confirmações já concedidas.

A CI mantém três gates: revisão automática, testes e segurança. O runner padrão é local/self-hosted, rotulado `prospero`, sem consumir runner hospedado pago. Jobs não executam código de forks no runner local, não fazem merge nem publicam releases. O ambiente de testes deve usar projeto Compose, imagens e portas exclusivos; sua limpeza nunca pode alcançar o ambiente de desenvolvimento. O [guia de CI](ci.md) descreve instalação, limites e evidências.

## Memória e retomada

O ai-memory apoiará a continuidade local, com armazenamento persistente, sem LLM ou API paga obrigatória. Sua instalação e integração foram antecipadas por instrução do usuário e devem ser verificadas antes de declarar o fluxo ativo, conforme a [documentação oficial](https://github.com/akitaonrails/ai-memory). Não manter arquivo paralelo de continuidade: o roadmap registra o status e o ai-memory guarda contexto, decisões operacionais e handoffs.

No início de cada sessão, leia regras, roadmap, tarefa e ADRs relevantes; confronte com arquivos e Git. Se o ai-memory estiver disponível, consulte decisões e handoffs do Prospero. Ao encerrar ou trocar de agente, registre tarefa, progresso, decisões, alternativas descartadas, verificações, impedimentos e próximo passo. Não armazene credenciais nem tokens na memória ou documentação.

Memórias são auxiliares: nunca substituem regras, ADRs, manifests ou estado real. Se a ferramenta estiver indisponível, continue com os registros locais e declare a indisponibilidade. Não afirmar instalação, captura ou recuperação sem verificá-las.

## Inventário e manutenção documental

`docs/libs.md` diferencia decisão de adoção: registrar nome, finalidade, versão escolhida, versão resolvida, status e fonte de verificação. Sem manifest ou imagem configurada, uma dependência permanece planejada. Evitar tags `latest` ao implementar containers.

Alterações arquiteturais devem atualizar ou substituir o ADR pertinente, preservando o histórico. Documentos de proposta original precisam de aviso quando deixarem de expressar decisões vigentes. Proteja alterações preexistentes e não publique dados privados para produzir evidências.
