# ADR-018 — Antecipar publicação da outbox após commit

- Data: 2026-10-26.
- Estado: **proposta técnica para implementação da V01b**, sem aceite novo atribuído ao usuário.
- Origem: RNF04 e decisão do usuário no [ADR-013](ADR-013-matching-execucao.md#robô-formador-de-mercado-e-primeira-medição-rnf04--rf05d): medir primeiro e otimizar o relay depois em tarefa própria com ADR.
- Responsabilidade: implementador da V01b, com revisão, QA e segurança em agentes separados; andamento no [roadmap](../roadmap.md#v01b--publicação-da-outbox-após-commit).

## Contexto e alternativas

FO e MO publicam suas outboxes em executor único com espera fixa de 200 ms. A medição hospedada de `22848c7` repetiu p99 por limite superior de bucket de 447,392426 ms entre recebimento e primeiro negócio, média de 231,789489 ms; matching p99 de 0,699049 ms. São 150 amostras após 30 de aquecimento, com contraparte em repouso. O runner compartilhado fornece evidência indicativa, sem comprovar RNF04 dedicado. Protocolo e limitações no [HLD-004](../hlds/HLD-004-matching-engine.md#medições--rnf04-rf05d).

| Alternativa | Ganho | Custo e limite |
| --- | --- | --- |
| Manter polling de 200 ms | Simplicidade e recuperação existentes | Mantém espera artificial no caminho crítico |
| Polling de 5 ms | Implementação menor | Aproximadamente 200 consultas vazias/s por serviço em repouso, contra cerca de cinco; custo contínuo de banco/CPU |
| **Sinal local após commit e polling de recuperação** | Antecipa o mesmo publicador quando há trabalho, mantendo descoberta persistente | Exige coordenação verificável de sinais, retry e shutdown |
| CDC ou novo mecanismo distribuído | Poderia externalizar descoberta | Novo conector, permissões, operação e recuperação desproporcionais à fatia |

## Escolha técnica proposta

O sinal contém somente a existência potencial de trabalho. Não transporta entidade, mensagem, conta ou contexto transacional e não publica diretamente. PostgreSQL/outbox continuam autoridades; perder o sinal não perde a mensagem. Não há schema, contrato Kafka, financeiro, dependência, serviço, credencial ou autoridade novos.

A transação efetiva da inserção registra no máximo um callback por transação. Somente confirmação bem-sucedida solicita execução antecipada. Rollback não sinaliza; falha/rejeição de agendamento após commit não altera o resultado financeiro confirmado. FO sinaliza no registro da ordem/outbox; MO sinaliza em `OutboxJpa` e no encerramento de sessão gravado diretamente, somente quando houver inserção nova. Repetição não cria novo efeito.

Cada serviço mantém um executor único para urgência, fallback e manutenção. A máquina de estados é:

| Situação | Comportamento |
| --- | --- |
| Ocioso e commit confirmado | Agenda no máximo uma tarefa urgente pendente |
| Executando e novo commit | Conserva no máximo uma tarefa urgente posterior; não publica em paralelo |
| Sinal perdido ou startup com backlog | Polling de recuperação a cada 200 ms, quando o executor estiver disponível |
| Lote completo | Continua drenando em série; confere manutenção entre lotes |
| Falha de Kafka/banco/manutenção | Aguarda pelo menos 200 ms de tempo monotônico desde a falha; sinais novos não contornam esse intervalo |
| Parando/parado | Recusa sinais silenciosamente, interrompe executor e encerra produtor; pendências persistidas sobrevivem |

Uma tarefa urgente limpa sua marca de pendência **antes** de executar o ciclo: sinal durante publicação pode reservar uma tarefa posterior. A marca permanece verdadeira enquanto a tarefa espera no executor. Rejeição concorrente com shutdown limpa a marca sem propagar exceção ao commit. Callback não faz I/O nem espera pelo relay.

Preservar seleção das linhas visíveis, `ORDER BY criado_em,id`, locks, lote de 50, limite de 20 s por lote, envio com ack e marcação transacional posterior. Falha parcial conserva as marcações permitidas pelo contrato atual e interrompe a drenagem. Ack seguido de rollback admite reentrega; consumidores continuam idempotentes. Não se promete ordem global de início/commit entre transações concorrentes, nem exactly-once financeiro distribuído.

Gauge/expurgo usam aproximadamente cinco segundos de tempo monotônico, conferidos também entre lotes, sem manutenção concorrente. Execução ocupada pode atrasá-los; sinais frequentes não aceleram consultas de contagem ou limpeza. O polling de 200 ms é oportunidade de recuperação, não SLA diante de ack lento ou lote em andamento. BO fica fora desta fatia.

## Verificação, comparação e reversão

QA separado prova RED/GREEN do agendamento, commit/rollback reais, múltiplas gravações, rajadas, sinal durante publicação, sinal perdido, falha/retry, ack seguido de rollback e shutdown. PostgreSQL real é necessário para a fronteira transacional; dublês do agendador não a comprovam. Executar os mesmos contratos FO/MO e regressões financeiras existentes, sem alterar bytes/IDs/eventos.

Uma flag técnica local permite comparar a mesma imagem com urgência desabilitada (fallback de 200 ms) e habilitada. Não desabilita mensageria nem guardas financeiras. Comparação inicial pareada no mesmo job, infraestrutura e limites: 150 amostras e 30 de aquecimento por variante, sem stacks concorrentes. O preflight lê no máximo dez páginas de cem ordens por conta, valida IDs/cursores e conta conservadoramente inclusive outras datas; margem observada não é reserva de vagas contra emissor concorrente. Orçar todas as ordens anteriores por conta/sessão; preparação não reinicia o teto de 1000 e não desfaz fatos. Medição ocorre na fase D0 existente; provas posteriores D+2 mantêm a guarda ativa. Repetições adicionais exigem margem demonstrada ou projeto CI próprio, nunca elevar limite ou apagar dados de desenvolvimento.

Registrar p50/p99 por buckets, média independente, amostras, erros, SHA/configuração e ambiente. Três snapshots agregados de CPU/memória/I/O depois de cada variante observam o gerador parado; não medem consultas SQL, custo de repouso prolongado ou capacidade. Corrigir o instrumento para não declarar cumprida desigualdade estrita quando o limite superior for exatamente a meta. Ausência de fonte de CPU/DB permanece lacuna, não zero. Melhoria observada não significa RNF04/V01 concluído.

Implantar FO/MO separadamente preservando bancos/outboxes; uma instância do relay por serviço continua obrigatória. Desabilitar urgência ou retornar à imagem anterior restaura polling sem migração, limpeza ou replay manual. Reavaliar se houver fila sem limite, retry excessivo, regressão de ordenação/idempotência, custo ocioso maior sem explicação ou ganho insuficiente. Kafka PLAINTEXT permanece risco herdado dos ADRs vigentes.
