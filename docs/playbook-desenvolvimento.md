# Playbook de desenvolvimento do Próspero

## Como consultar

Guia de prevenção de erros, ampliado por solicitação do usuário em 2026-10-17 com 21 temas de arquitetura, 21 de segurança e 18 de SQL. Cada linha resume gatilho, ação e custo; os IDs locais permitem revisão sem copiar checklists. Referência seletiva: abrir a seção ligada à tarefa ou sintoma. Não é checklist obrigatório de padrões a implementar. [Rules](rules.md), [guia documental](documentacao.md) e [ADRs](adrs/README.md) contêm os contratos; este arquivo ajuda a decidir e diagnosticar sem acrescentar arquitetura.

| Necessidade | Consultar |
| --- | --- |
| Começar uma tarefa / escolher documentos | Entrada e rastreabilidade |
| API ou caso de uso | Fronteiras e contratos |
| Retry, evento duplicado, cache, pico ou fluxo distribuído | Arquitetura, transações e eventos (A01–A21) |
| Query, migração, consulta lenta ou conexão esgotada | Persistência e SQL (D01–D18) |
| Endpoint, auth, arquivo, dependência ou configuração nova | Segurança (S01–S21) |
| Container saudável mas API inacessível | Operação e diagnóstico |
| Decidir se adicionar uma solução | Simplicidade e fechamento |

## Entrada e rastreabilidade

Consultar memória no escopo Prospero e confrontar com Git, documentos e código. Invocar refinar-task e registrar cinco perspectivas independentes medium antes da implementação. Classificar impacto com o guia: PRD para comportamento, HLD para estrutura e ADR para decisão material. Atualizar fonte existente quando suficiente.

Exemplos: refatorar um Helper sem mudar comportamento pode ficar no refinamento/PR; novo fluxo FO→MO→ME precisa desenho e contratos; se um ADR proíbe REST entre serviços e a proposta o introduz, registrar `[ADR-VIOLATION]` antes de implementar. Criar um documento adicional só após explicar ao usuário a necessidade e o benefício; não adotar FDD/LLD por catálogo.

## Fronteiras e contratos

REST traduz DTO/status e chama Actor; Actor coordena domínio/Helpers puros e JPA separado. Não criar interfaces ou camadas vazias sem necessidade. Usar `/api/v1`, Swagger, DTOs e Problem Details conforme ADR-003. Revisar se `201`, `202` ou `204` representa o resultado real e os headers/corpo esperados pelo cliente.

Valores financeiros exigem escala, sinal, overflow e arredondamento explícitos, sem `double`. Não importar as escalas do Minerva como regra de negócio. Testar construção direta além de fábricas, para evitar invariantes contornáveis. Testes internos e planilhas têm finalidades complementares (ADR-004).

## Arquitetura, transações e eventos

Use o gatilho para selecionar a linha; não implemente todos os padrões. Réplicas, webhooks, flags e serviços externos abaixo são orientações condicionais, não componentes já adotados. Para toda escolha, registrar problema observado, alternativa menor, custo e evidência de recuperação.

| ID / tópico | Quando consultar | Conduta resumida e trade-off |
| --- | --- | --- |
| A01 — Idempotência | Webhook, consumer, escrita ou retry | Chave com escopo e intenção estáveis; deduplicar atomicamente com efeito/resposta. Mesma chave e payload diferente deve conflitar; armazenamento e retenção têm custo. |
| A02 — Transações distribuídas | Banco + fila/serviço externo | Transações locais, outbox e SAGA conforme ADR-002; compensação não é rollback global. Aceitar estados intermediários e recuperação; evitar locks durante I/O remoto. |
| A03 — Consistência eventual | Estado replicado em eventos/projeções | Declarar atraso aceitável, versão/idade e reconciliação. Cotações podem atrasar; admissão ao matching exige reserva válida. Desempenho não autoriza saldo incorreto. |
| A04 — Réplicas de leitura | Escala de leitura | Medir gargalo antes; leitura atrasada não serve para reserva nem leitura imediata da própria escrita sem estratégia. Réplicas custam recursos; quatro bancos do Próspero não são réplicas. |
| A05 — Teorema CAP | Falha ou partição de rede | Descrever qual operação recusa/espera e qual admite leitura defasada. CAP trata partição; não escolher “dois de três” universalmente nem atribuir HA a Redis/PostgreSQL únicos. |
| A06 — Processamento efetivamente único | Entrega Kafka/webhook | Admitir reentrega, combinar deduplicação e efeito local atômico; confirmar após persistência segura. Transação Kafka não cobre automaticamente PostgreSQL, serviço externo ou mutação do livro em memória. |
| A07 — Controle de pressão | Pico, consumidor, workers concorrentes | Limitar filas, lotes, concorrência e tempo; medir lag e saturação. Pausar/rejeitar com recuperação explícita; aumentar workers não supera escritor único por ativo. |
| A08 — Efeito manada | Cache falha ou expira em massa | Evitar recomputação simultânea por chave; expiração distribuída com variação aleatória e tentativas espaçadas. Cache velho só se o domínio permitir; coordenação adiciona estado. |
| A09 — Invalidação de cache | Escrita em dado cacheado | Definir fonte, chave, versão e invalidação após commit; tratar corrida de repovoamento. TTL limita idade, mas não garante coerência nem substitui evento recuperável. |
| A10 — Linhas quentes e efeito celebridade | Conta/ativo/partição concentrando carga | Medir contenção por chave; reduzir trabalho na transação e separar chaves independentes. Dividir reserva ou livro sem preservar invariantes quebra consistência/ordem. |
| A11 — Disjuntor de chamadas | Dependência externa síncrona | Timeout, orçamento de retry e abertura/recuperação controladas. Fallback deve ser semanticamente seguro; nunca fabricar aprovação financeira. Não introduzir HTTP entre MS contra ADR-001. |
| A12 — Chaves de funcionalidade | Ligar/desligar sem publicação | Dono, valor seguro, prazo de remoção e testes nos estados. Flag não substitui autorização; desligar entrada não abandona SAGAs já iniciadas. |
| A13 — Evolução de esquema | Estrutura/contrato existente | Expandir, migrar e só depois contrair; compatibilidade entre versões e consumidores atrasados. Evitar DDL longo no caminho de requisição; mais etapas reduzem risco de parada. |
| A14 — Preenchimento retroativo | Completar histórico | Lotes por chave, checkpoint, idempotência e limite de carga; conciliar com novas escritas. Sucesso parcial exige retomada; observar WAL, locks e espaço. |
| A15 — Escritas duplas | Dois destinos | Mapear falha entre efeitos; outbox/CDC só conforme necessidade e ADRs. Retry isolado não garante atomicidade; reconciliação e deduplicação têm custo operacional. |
| A16 — Tabelas espelho | Migração estrutural arriscada | Avaliar cópia, captura de mudanças, conciliação e troca reversível de leitura/escrita. Exige espaço e plano de corte; não duplicar tabela para migração simples. |
| A17 — Limitação de taxa | Entrada pública, login ou abuso | Limitar por identidade/ação e orçamento global; resposta e prazo de retomada claros. Evitar punir todos por IP compartilhado; manter limite de concorrência além da taxa. |
| A18 — Princípios de invalidação | Revisão transversal do cache | Reaplicar A09 em update/delete, eventos atrasados, reentrega e reinício; testar leitura após escrita. Não criar um segundo mecanismo de invalidação conflitante. |
| A19 — Inicialização a frio | Startup/retomada | Medir migração, aquecimento, pool e replay; prontidão só após condições reais. Aquecer sem liberar negociação incompleta; não confundir processo vivo com serviço pronto. |
| A20 — Checklist de arquitetura | Todo fluxo novo, antes de implementar | Identificar proprietário, invariantes, fronteiras, consistência, falhas/retry, limites, autorização e sinais de diagnóstico. Reusar PRD/HLD/ADR; marcar N/A com motivo. |
| A21 — Anti-overengineering | Novo padrão/camada/serviço | Problema concreto, solução menor e critério de remoção. Introduzir somente o necessário; abstração antecipada adiciona contratos, operação e testes sem benefício comprovado. |

No Próspero, timeout não prova ausência de execução: **não liberar reserva financeira automaticamente por TTL**. Após negócio efetivado, recuperar para frente. Testar reentrega, queda entre efeitos e concorrência, sem prometer conclusão sem participantes. As garantias de transação Kafka têm fronteira explícita; ver [documentação do Kafka](https://kafka.apache.org/41/design/design/).

## Persistência e SQL

**Orientação solicitada pelo usuário:** não usar `LIKE` nas consultas do projeto; não contornar com `ILIKE`, regex ou busca de substring sem planejamento. Evitar varreduras completas desnecessárias, resultados ilimitados e carregamento de tabela inteira para filtrar na aplicação. Projetar consultas por chave/filtro seletivo, colunas necessárias, ordenação estável e paginação limitada. A mesma revisão vale para JPQL, SQL nativo e consultas geradas pelo ORM.

`LIMIT` reduz o resultado, mas não garante poucas linhas examinadas. `Seq Scan` pode ser o plano mais barato para tabela pequena ou operação que precisa de grande parte dos dados; justificar pelo volume/plano e orçamento, sem desabilitar `enable_seqscan` para esconder o problema. Uma varredura intencional de manutenção deve ter lotes, progresso e limites. Isso é diferente de introduzir consulta ampla no caminho de uma API. Ver [EXPLAIN](https://www.postgresql.org/docs/16/using-explain.html).

| ID / tópico | Quando consultar | Conduta resumida e trade-off |
| --- | --- | --- |
| D01 — N+1 queries | Loop/listagem com relacionamento | Contar SQL e linhas; projeção, fetch adequado ou lote limitado. Join indiscriminado multiplica linhas; paginar coleção com fetch exige conferir resultado e plano. |
| D02 — Falta de índice | Filtro/join/order lento | Conferir seletividade, estatísticas e plano com volume representativo; índice alinhado ao acesso. Predicado/função/cast precisa permitir uso; criar índice também custa escrita e espaço. |
| D03 — Índice inútil ou em excesso | Índice “por garantia” | Verificar sobreposição, uso e custo de manutenção. Índice de unicidade protege invariante mesmo sem leitura frequente; não remover constraint com base só em contagem de uso. |
| D04 — Deadlock | Transações bloqueiam entre si | Inspecionar espera e ordem de locks; ordenar aquisições e encurtar transações. Reexecutar transação inteira apenas com semântica segura e tentativas limitadas. |
| D05 — Lock/transação longa | Latência concorrente | Medir tempo de retenção; tirar I/O externo da transação e reduzir escopo. Lock protege invariantes, mas serializa concorrentes; evitar bloqueio amplo sem evidência. |
| D06 — Pool esgotado/vazamento | Timeout de conexão; idle in transaction | Conferir conexões retidas, duração e encerramento. Devolver recursos em todos os caminhos; aumentar pool sem orçamento do banco pode piorar saturação. |
| D07 — Race check-then-act | Verificar e depois inserir/alterar | Constraint, update condicional, lock ou versão com teste de corrida. Checagem prévia ajuda UX, mas não substitui atomicidade; interpretar conflito sem perder efeitos. |
| D08 — Isolamento e anomalias | Lost update/write skew/leitura incoerente | Definir invariante e concorrência; escolher bloqueio/versão/isolamento adequado. Serializable pode abortar; retry exige transação completa, limites e idempotência. |
| D09 — Paginação com OFFSET | Listagens | Preferir cursor por chave ordenada e desempate único em volumes crescentes. OFFSET pode bastar em conjunto pequeno; cursor perde salto arbitrário e precisa contrato estável. |
| D10 — Armadilhas de NULL | NOT IN, UNIQUE, agregados/comparações | Distinguir ausência de zero/vazio; usar semântica explícita e considerar NOT EXISTS. NULL em lista altera NOT IN; unicidade/constraints devem refletir ausência permitida. |
| D11 — Tipos errados | Dinheiro, quantidade, IDs, datas | Sem double financeiro; escala/arredondamento/overflow definidos. Separar data de liquidação de instante/fuso; tipo da coluna e parâmetro devem concordar. Não herdar escala do doador. |
| D12 — Validação só na aplicação | Invariante sem constraint | NOT NULL, CHECK, UNIQUE e FK **local** quando aplicáveis; validar também no domínio. Constraint não protege sozinha limite agregado; nenhuma FK entre bancos de serviços. |
| D13 — Bloat e autovacuum | UPDATE/DELETE frequente, crescimento | Medir tuplas mortas, transações antigas e manutenção. Ajustar com evidência; evitar VACUUM FULL rotineiro, que pode bloquear e exigir espaço adicional. |
| D14 — Diagnóstico de query lenta | Investigação de desempenho | Guardar query, cardinalidade, parâmetros sintéticos, estimativa/real e buffers. EXPLAIN ANALYZE executa a instrução: medir em ambiente de teste, inclusive efeitos de DML. |
| D15 — Timeouts | Query/lock preso | Definir orçamento de statement, lock e transação alinhado à API/worker. Cancelamento não confirma rollback/conclusão remota; conferir estado antes de repetir efeito. |
| D16 — Busca de texto | Nome/título/descrição | Identificador: igualdade parametrizada e índice adequado. Texto linguístico: avaliar full-text search e índice com idioma definido; sem LIKE/ILIKE. Full-text não equivale a substring ou similaridade. |
| D17 — Disciplina da camada de dados | Mapeamento, transações e SQL dinâmico | Mapear NULL/erros explicitamente, fechar recursos e conferir linhas afetadas. Parâmetros para valores, allowlist para nomes/ordenação; não concatenar entrada nem esconder falha como sucesso. |
| D18 — Revisão de query/migration | Toda query ou migração nova | Conferir D01–D17 aplicáveis: autorização/filtro, projeção, limite, plano, concorrência, constraints, volume e recuperação. Flyway/criar-migration com recuperação explícita; não exigir índice artificial. |

Busca textual precisa de requisito próprio; avaliar `tsvector`/`tsquery` e índice GIN somente quando justificável. Trocar `LIKE` por outra função que varre tudo não atende ao objetivo. Índices e full-text search acrescentam escrita, armazenamento e manutenção; ver [tipos de índice](https://www.postgresql.org/docs/16/indexes-types.html) e [busca textual](https://www.postgresql.org/docs/16/textsearch-intro.html). Para locks, conferir a [documentação PostgreSQL](https://www.postgresql.org/docs/16/explicit-locking.html). Não transportar comandos SQLite nem encerrar processos de outros projetos.

## Segurança

Aplicar pelo mecanismo real e pela fronteira da tarefa. Login, cookies, upload e webhook não estão presumidos como funcionalidades existentes. Recursos locais também precisam de limites e validação; o servidor decide efeitos financeiros e acesso por conta.

| ID / tópico | Quando consultar | Conduta resumida e trade-off |
| --- | --- | --- |
| S01 — SQL injection | SQL/JPQL dinâmico | Parametrizar valores; nomes/direções só de allowlist. Escaping manual e proibir LIKE não evitam injeção. Privilégio mínimo no banco reduz impacto, sem substituir correção. |
| S02 — XSS | HTML/template com dado externo | Preservar escaping Angular e codificação por contexto; sanitizar HTML somente se necessário. Não usar bypass de confiança para calar erro; CSP complementa, não corrige saída insegura. |
| S03 — CSRF | Escrita autenticada por cookie | Proteção do framework/token e validação de origem conforme fluxo; SameSite como complemento. CORS não substitui CSRF; autenticação via cookie exige análise específica. |
| S04 — Armazenamento de senha | Login/cadastro | Biblioteca mantida, hash adaptativo com salt, parâmetros atuais e upgrade planejado; avaliar Argon2id. Custo deve resistir ataques sem esgotar servidor; nunca plaintext, hash rápido ou criptografia reversível. |
| S05 — Brute force/enumeração | Login/reset/erros auth | Resposta sem revelar existência, limite por identidade/origem e monitoramento. Bloqueio indiscriminado permite DoS contra a vítima; equilibrar proteção e recuperação de acesso. |
| S06 — Sessão e cookies | Emissão/validação de sessão | Se adotados: expiração, rotação, revogação e atributos HttpOnly/Secure/SameSite adequados ao transporte/fluxo. Evitar token em URL/log; cookie seguro não elimina XSS/CSRF. |
| S07 — Autorização e IDOR | ID de recurso/admin | Validar operação, recurso e conta no servidor em toda requisição; negar por padrão. UUID imprevisível não é autorização; testar acesso cruzado e privilégios mínimos. |
| S08 — Secrets | Env, Compose, CI, logs | Fora do Git/imagem/saída; menor exposição, rotação e scanner. Arquivo .env não é cofre; não ler/imprimir segredo para provar que está configurado. |
| S09 — Webhooks de terceiros | Recebimento externo | Se existir: assinatura sobre bytes originais, janela/replay, ID idempotente e efeito persistido antes de confirmar. IP/origem não substituem assinatura; prever rotação e reentrega. |
| S10 — Integridade de valores | Preço/valor/status do cliente | Limite/gatilho são intenção válida da boleta; servidor valida tick/túnel. Saldo, reserva, execução e liquidação são calculados/autorizados no servidor, nunca aceitos como verdade do cliente. |
| S11 — Tokens de acesso a recurso | Convite, voucher, QR, download | Aleatórios, escopo/finalidade/expiração; consumo único atômico quando necessário. Evitar enumeração e vazamento por logs/referrer; assinatura não dá confidencialidade. |
| S12 — Entrada/mass assignment | Bind de request/modelo | DTO de campos permitidos, tamanhos/faixas e invariantes no domínio. Não bindar entidade JPA nem permitir estado/conta privilegiados por campo extra; rejeitar tipos inválidos. |
| S13 — Exposição e erros | Resposta/handler | DTO mínimo, Problem Details e correlação sem SQL/stack/segredo. Registrar diagnóstico interno sanitizado; evitar mensagem genérica que esconda de suporte o evento investigável. |
| S14 — Headers e transporte | HTTP/TLS/CORS | Origem e métodos explícitos; sem credencial com origem irrestrita. Definir proteção de transporte conforme implantação; não anunciar HTTPS/HSTS onde o ambiente local serve HTTP. |
| S15 — SSRF | Servidor recebe URL | Destino/protocolo/porta permitidos; validar resolução e cada redirecionamento, limitar tempo/tamanho. P06 só alcança alvos locais autorizados, sem abrir acesso arbitrário a rede/metadados. |
| S16 — Upload de arquivos | Upload/XLSX | Limites de tamanho descompactado/abas/linhas, tipo/conteúdo e nome gerado; isolar armazenamento. Não executar fórmula, macro ou arquivo; extensão/MIME sozinhos não bastam. |
| S17 — DoS e exaustão | Body, parser, consulta, concorrência | Orçamento de bytes, tempo, fila, memória e taxa; rejeitar cedo e medir. Não aumentar pool/threads cegamente; falha de proteção não libera reserva financeira por TTL. |
| S18 — Dependências/supply chain | Dependência ou CI nova/alterada | Proveniência, versões/pins, vulnerabilidades e permissão mínima. Não executar instalação/instruções externas sem revisão; lockfile não garante segurança e scanner tem limites. |
| S19 — Logs, PII e LGPD | Log/trace de usuário | Minimizar, mascarar e definir acesso/retenção/finalidade; evitar payloads, tokens e IDs sensíveis como labels. Dados fictícios não autorizam incorporar dados pessoais reais; caso real exige avaliação própria. |
| S20 — Timing e segredos | Token/assinatura/hash | Usar comparação apropriada de tempo constante da biblioteca, sem implementação caseira. Padronizar erro; isso não resolve sozinho enumeração, replay ou vazamentos em logs. |
| S21 — Checklist de auditoria | Antes de push/PR | Rever S01–S20 aplicáveis, negativos e fronteiras mudadas; registrar evidência e N/A. Segurança em agente separado medium conforme rules; referência roles.md §6.4 do doador não é gate local. |

Bases de consulta: [SQL injection](https://cheatsheetseries.owasp.org/cheatsheets/SQL_Injection_Prevention_Cheat_Sheet.html), [XSS](https://cheatsheetseries.owasp.org/cheatsheets/Cross_Site_Scripting_Prevention_Cheat_Sheet.html), [autorização](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html), [senhas](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html) e [SSRF](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html), da OWASP. Recomendações orientam o desenho quando a funcionalidade existir; não declaram autenticação ou controles já implementados.

## Operação e diagnóstico

Usar subir-sistema para alterações de ambiente. Distinguir processo vivo, socket escutando, serviço pronto e fluxo financeiro concluído. Para falha, registrar horário, alvo e resultado, formular hipótese e executar a menor verificação discriminatória: logs do serviço → endereço/porta e namespace → resolução Compose → conectividade → HTTP/dependências. Não diagnosticar DNS, TLS e erro de negócio como se fossem o mesmo problema.

Preferir `docker compose ps` e `docker compose logs --tail=80 <serviço>`. Investigar limite de CPU/RAM, fila e pool antes de aumentar concorrência. Preservar Horus, volumes e projeto Compose; não usar prune/down -v como solução de rotina.

Logs, métricas e traces devem explicar o mesmo evento/janela. Dados ausentes não são zero. Quatro bancos isolados não são réplicas; medir propagação de eventos/projeções somente quando existentes. Para carga progressiva, seguir [P06](planos/P06-carga-observabilidade.md), com orçamento do gerador e telemetria, sem stress implícito.

## Simplicidade e fechamento

Antes de adicionar padrão, camada, biblioteca ou infraestrutura: qual problema observado resolve? Qual alternativa menor? Qual evidência mostrará que bastou? Não adicionar cache, réplica, cluster, circuit breaker ou motor genérico por antecipação.

Delegar testes, revisão e segurança a agentes separados medium. Atualizar documentos afetados, inventário quando houver dependência e memória de continuidade; registrar evidências e PR por tarefa. Não confundir refinamento com aprovação de código, nem execução local com CI remota. Não fazer merge/release automaticamente.

## Origem e seleção

Síntese autoral da análise dos cinco [playbooks Minerva](https://github.com/Cris042/Minerva/tree/76a991d7296cddba5e595a07e9b3b6f8da581b78/docs/playbooks), de Cristóvão Augusto, fixados nesse commit:

| Fonte | Aproveitado | Não adotado automaticamente |
| --- | --- | --- |
| [playbook-backend.md](https://github.com/Cris042/Minerva/blob/76a991d7296cddba5e595a07e9b3b6f8da581b78/docs/playbooks/playbook-backend.md) | Idempotência, escrita dupla, pressão e simplicidade | Políticas universais de consistência, sufixos ou réplicas |
| [playbook-database.md](https://github.com/Cris042/Minerva/blob/76a991d7296cddba5e595a07e9b3b6f8da581b78/docs/playbooks/playbook-database.md) | N+1, concorrência, planos, pools e migrações | Receitas SQLite, índice por regra genérica ou matar processos |
| [playbook-security.md](https://github.com/Cris042/Minerva/blob/76a991d7296cddba5e595a07e9b3b6f8da581b78/docs/playbooks/playbook-security.md) | Fronteiras de entrada, segredos e supply chain | Defaults de autenticação e liberação de toda reserva por TTL |
| [playbook-sistemas-redes.md](https://github.com/Cris042/Minerva/blob/76a991d7296cddba5e595a07e9b3b6f8da581b78/docs/playbooks/playbook-sistemas-redes.md) | Hipótese/evidência por camada | Cloud, Kubernetes e BGP sem relação com a stack local |
| [playbook-documentacao.md](https://github.com/Cris042/Minerva/blob/76a991d7296cddba5e595a07e9b3b6f8da581b78/docs/playbooks/playbook-documentacao.md) | Escolha de documento por necessidade | Cadeia obrigatória FDD/LLD, personas e Obsidian |

A orientação atual do usuário substitui sugestões do doador de ILIKE e busca indiscriminada. Também não importar “preço sempre do banco” para negar preço limite da boleta, TTL como liberação automática de reserva, nem aprovação por arquivo roles.md inexistente aqui. A [validação desta revisão](evidencias/arquitetura-pdf-validacao.md) registra o agente que analisou as cinco fontes e os limites.

Nenhum desses arquivos foi promovido integralmente a regra local. O conteúdo vigente é esta seleção, subordinada às decisões do Próspero.
