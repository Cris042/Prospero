# ADR-007 — Coletor e medição de pausas do front office

- Data: 2026-10-17.
- Estado: **proposta de G1 como baseline de medição**; adoção/configuração final pendente dos experimentos. Nenhuma flag de runtime foi alterada.
- Origem: solicitação do usuário para comparar G1, Parallel e ZGC e explicar ajustes; P06/V01 e RNF04/RNF07 do [PRD](../prds/PRD-001-simulador-prospero.md).
- Decisor da adoção final: usuário; esta recomendação não registra aceite inexistente nem um vencedor medido.

## Contexto e estado real

O cenário descrito pelo usuário, FO alocando centenas de MB/s em JSON, eventos e listas, é hipótese de teste: não há medição desse pico no Próspero. Alocação não se converte em uma porcentagem fixa de pausa. Vida dos objetos, referências, promoção, dados vivos, heap disponível, CPU e coletor mudam o trabalho realizado. Parte pode ocorrer com a aplicação rodando; pausas stop-the-world e espera por memória precisam ser observadas separadamente.

Os quatro Dockerfiles configuram `MaxRAMPercentage=65`, `InitialRAMPercentage=15` e `ActiveProcessorCount=2`; o Compose limita cada serviço a **384 MiB e 1 CPU**. Não fixam coletor. Não inferir G1 ativo por ser default em muitos ambientes: confirmar o coletor/heap efetivos no processo testado. O `MAVEN_OPTS` do estágio de build não configura a aplicação em execução. Fontes: [Dockerfile FO](../../front-office/Dockerfile), [Compose](../../docker-compose.yml).

## Proposta e alternativas

Recomendar **G1 como primeiro baseline explícito do FO** no experimento controlado, por equilibrar vazão e controle de pausas, com Parallel como comparação de throughput e ZGC como candidato se a cauda exigir e houver CPU/heap suficientes. Não selecionar por benchmark de terceiros. A recomendação aplica-se ao FO; os demais serviços poderão ter decisões diferentes. O gerador Go tem runtime próprio.

| Alternativa | Benefício esperado | Custo/limite a medir | Papel proposto |
| --- | --- | --- | --- |
| Manter ergonomia atual | Preserva a fundação e fornece controle sem nova flag | Coletor depende do ambiente; não equivale a G1 comprovado | Registrar antes de qualquer mudança |
| G1: `-XX:+UseG1GC` | Coleta regional, parte do trabalho concorrente, objetivo de pausa | Pausas de evacuação/marcação e eventual Full GC; meta não é prazo garantido | Primeiro baseline candidato |
| Parallel: `-XX:+UseParallelGC` | Favorece throughput com coleta paralela | Coleta stop-the-world; CPU limitada pode reduzir benefício e aumentar cauda | Controle de throughput |
| ZGC: `-XX:+UseZGC` | Prioriza pausas curtas, executando muito trabalho concorrente | Disputa CPU e exige espaço para alocar durante coleta; stalls e OOM continuam possíveis | Avaliar com folga de recursos, sem prometer ganho no container atual |

Referências Java 26: [coletores](https://docs.oracle.com/en/java/javase/26/gctuning/available-collectors.html), [G1](https://docs.oracle.com/en/java/javase/26/gctuning/garbage-first-g1-garbage-collector1.html), [Parallel](https://docs.oracle.com/en/java/javase/26/gctuning/parallel-collector1.html) e [ZGC](https://docs.oracle.com/en/java/javase/26/gctuning/z-garbage-collector1.html). A documentação de baixa pausa não é garantia de latência HTTP nem de matching <1 ms. No JDK 26, ZGC já é geracional; `ZGenerational` foi removida. Não adicionar flags históricas/experimentais sem suporte conferido.

## O que cada configuração faz

| Opção | Significado e aplicação |
| --- | --- |
| `-XX:MaxRAMPercentage=65` — atual | Teto percentual usado pela ergonomia do heap sobre a memória considerada pela JVM; não limita RSS total. Em 384 MiB, ordem de grandeza de 250 MiB, sujeita à ergonomia/alinhamento |
| `-XX:InitialRAMPercentage=15` — atual | Dimensionamento inicial percentual do heap; não reserva todo o limite do container |
| `-XX:ActiveProcessorCount=2` — atual | Sobrescreve processadores percebidos pela JVM; influencia ergonomia/threads, sem conceder CPU. Difere da quota real de 1 CPU: comparar percepção alinhada à quota em experimento separado |
| `-Xms` / `-Xmx` — experimento | Heap inicial/máximo explícitos; não combinar inadvertidamente com percentuais. Reservar memória nativa e observar RSS. Heap maior não corrige retenção ilimitada |
| `-XX:+UseG1GC`, `-XX:+UseParallelGC`, `-XX:+UseZGC` — alternativas | Selecionam um único coletor por processo; não usar juntos |
| `-XX:MaxGCPauseMillis=N` — ajuste posterior | Objetivo de pausa para G1/Parallel, não SLA; não é controle de pausa do ZGC. Alvo mais agressivo pode aumentar frequência/custo e reduzir vazão |
| `-Xlog:gc*,safepoint:stdout:time,uptime,level,tags` — diagnóstico proposto | Registra atividades do GC e safepoints para correlação; limitar logs no ambiente de teste e medir custo |

Sintaxe e semântica: [opções do Java 26](https://docs.oracle.com/en/java/javase/26/docs/specs/man/java.html). Nenhum valor novo de heap, pausa ou threads é declarado ótimo. Não fixar geração jovem, número de threads, IHOP ou reservas do coletor antes do diagnóstico. [Tuning G1](https://docs.oracle.com/en/java/javase/26/gctuning/garbage-first-garbage-collector-tuning.html), [objetivo de pausa](https://docs.oracle.com/en/java/javase/26/gctuning/ergonomics.html). A folga fora do heap é disputada por metaspace, stacks, buffers diretos, código compilado, bibliotecas e estruturas do GC; os 35% restantes não garantem ausência de OOM.

## Como responder “quanto vira pausa?”

Usar a mesma janela monotônica de carga para calcular:

```text
alocacaoEstimadaMiBPorSegundo = bytesAlocadosEstimados / segundos / 1048576
fracaoEmPausaGC = duracaoDaUniaoDosIntervalosSTWdeGC / duracaoDaJanela
```

Exemplo **aritmético, sem medição local**: em 60 s, 120 pausas não sobrepostas de 5 ms somam 600 ms, ou 1% de tempo parado por GC. Isso não explica sozinho p99 HTTP; requests enfileiram e podem atravessar pausas. Não somar fases aninhadas ou pausas já incluídas em outras durações. CPU de GC concorrente não é tempo STW; safepoints por outras causas ficam separados. MB/s não tem conversão direta em milissegundos de pausa.

Coletar taxa/bytes por operação com método declarado: amostragem JFR é estimativa, não soma exata de todos os objetos. Comparar dados vivos após ciclos representativos, ocupação/promoção, alocação, número/p50/p95/p99/máximo das pausas e fração parada; pouca amostra não sustenta p99 preciso. Correlacionar com p99 HTTP, throughput, falhas/descartes, atraso do gerador, RSS, CPU/throttling, stalls e OOM. Não forçar `System.gc()` no caminho medido para estimar dados vivos.

## Protocolo de validação e consequências

1. Inventariar build Java, coletor/flags/heap efetivos, quota, limites e dados do cenário. Separar aquecimento/JIT da janela medida. Somente endpoints técnicos existem hoje; carga financeira exige RFs implementadas.
2. Medir baseline; usar logs de GC/safepoint e gravação JFR curta, limitada por tamanho e tempo, com dados sintéticos e caminho temporário privado permitido pelo container. Não publicar JFR/heap dumps brutos com payloads, nomes ou tokens. A imagem runtime é JRE: confirmar ferramentas disponíveis; análise pode usar JDK compatível fora do processo. [JFR Java 26](https://docs.oracle.com/en/java/javase/26/docs/specs/man/jfr.html).
3. Comparar os três coletores com mesmo heap, CPU, artefato, durabilidade, tráfego e dados vivos. Registrar se algum não inicia ou não cabe; não aumentar seus recursos silenciosamente. Repetir e alternar ordem. Não executar cargas concorrentes de outros projetos para criar um resultado artificial.
4. Depois variar uma dimensão por vez: reduzir cópias/serializações/listas temporárias identificadas por perfil; limitar filas/retenção/cache e payload; então heap e quota. Não usar pools de objetos por antecipação: podem aumentar retenção/contenção. Voltar a medir custo por operação e distribuição de latência.
5. Promover configuração só com evidência de melhoria e invariantes preservadas, sem regressão de erros, memória ou vazão; confrontar RNF04 no fluxo financeiro completo na V01. Metas de pausa e orçamento específico do FO serão fixados no experimento; não inventar SLA adicional.

G1 é hipótese operacional conservadora, não ajuste suficiente para o limite atual. Se GC concorrente perder CPU para throttling ou dados vivos consumirem o heap, mudar coletor pode não resolver. Reduzir alocação pode diminuir frequência de coleta, mas não elimina retenção. ZGC permanece alternativa se G1 não atender a cauda após diagnóstico e houver orçamento; Parallel se a vazão compensar as pausas dentro dos requisitos reais.

Relacionados: [HLD-006](../hlds/HLD-006-api-carga.md), [ADR-005](ADR-005-versoes-fundacao.md), [P06/V01](../roadmap.md). Revisitar quando houver baseline financeiro, mudança de heap/quota/JDK ou cauda/GC fora do orçamento. Este documento organiza o experimento; não aplica configuração nem declara benchmark executado. O livro *Java Performance*, mencionado pelo usuário, é contexto bibliográfico; não foram fornecidos edição ou capítulos, portanto não se atribuem números ou conclusões específicas ao livro.
