# Refinamento — P05 e P07; planejamento P06

Data: 2026-10-16. Situação: pareceres iniciais consolidados antes da implementação. P05 e P07 terão PRs próprios. A P06 só recebe backlog nesta rodada; seu refinamento de implementação ocorrerá quando for iniciada.

## Objetivos e fontes

O usuário autorizou aproveitar componentes do MinervaFinancas, planejar uma API de carga progressiva/observabilidade e adaptar o agente C4 do Minerva. Fontes locais: rules, libs, roadmap, ADRs 001–005 e roadmap do middle office. A memória `working/reuso-minerva-financas.md` foi efetivamente recuperada por CLI em `prospero/Prospero`; sua análise foi confrontada com o código e contratos atuais.

Referências fixadas: MinervaFinancas `34fc98a6ac47f2ffc2bf23f055a3fd9ac15c7530`; agente Minerva `76a991d7296cddba5e595a07e9b3b6f8da581b78`. O artigo de observabilidade foi lido; fundamenta a separação entre telemetria, monitoramento e observabilidade. As métricas concretas dependem das fontes existentes, não de extrapolação do artigo.

O brief comum foi enviado a cinco agentes distintos, com esforço medium e em lotes. Nenhum recebeu o parecer de outro antes de emitir o inicial. O implementador não substituiu parecerista.

## Pareceres

| Agente | Perspectiva | P05 / P07 | Exigências centrais |
| --- | --- | --- | --- |
| revisao | Arquitetura/domínio | Seguir com ressalvas / seguir com ressalvas | Núcleo decimal técnico; adiar políticas financeiras; somente níveis C4 sustentados e configuração real comprovada. |
| testes | QA | Seguir com ressalvas / seguir com ressalvas | RED comportamental, limites/exatidão/construção; exercício real do agente, negativos e distinção entre fonte documental e runtime. |
| seguranca | Segurança | Seguir com ressalvas / seguir com ressalvas | Limitar escala/precisão antes de expandir decimal; adaptação autoral; agente sem segredos, execução ou includes remotos. |
| refinamento_p05_operacao | Operação | Seguir com ressalvas / seguir com ressalvas | Build isolado limitado, sem segunda stack; orçamento da carga e distinção de fontes ausentes; sem novas ferramentas instaladas. |
| refinamento_p05_viabilidade | Viabilidade | Seguir com ressalvas / seguir com ressalvas | Dois PRs; fechar contrato técnico e provar adaptador; planejar P06 em fases sem antecipar funções financeiras. |

A consulta de memória foi feita pelo orquestrador e por pareceristas que a declararam; viabilidade utilizou o contexto do brief e explicitou não ter feito consulta própria. Não se declara unanimidade de consultas individuais.

## Contrato técnico da P05

Entregar no MO, sem dependência compartilhada:

- `ValorEscalado(long unidades, int escala)`: representação técnica imutável e assinada; escala de destino entre 0 e 18, inclusive, validada no construtor. Zero e negativos são válidos nesse tipo genérico. `emDecimal()` preserva a escala. Igualdade/hash representam unidades **e** escala: representações numericamente equivalentes com escalas distintas não são o mesmo valor representacional.
- `ConversaoDecimalExata.converter(BigDecimal, int, RegraDeSinal)`: escala e política explícitas; políticas `QUALQUER`, `NAO_NEGATIVO` e `POSITIVO`. A política valida aquela entrada; não transforma o VO genérico em um tipo financeiro positivo.
- Entrada: não nula, escala original entre −18 e 18 e precisão de até 38 dígitos. Conferir tamanho do inteiro não escalado antes de calcular precisão ou expandir decimais. O teto técnico comporta `long` com 19 dígitos e até 18 zeros fracionários, sem impor uma escala de moeda/tick. Intermediários ficam limitados; expoentes extremos, inclusive em zero, são recusados.
- Aceitar notação científica dentro desses limites. Aceitar zeros finais excedentes quando a conversão for numericamente exata (`1.2300` → escala 2). Recusar parte fracionária não representável; não truncar nem arredondar. Resultado deve caber em `long`, inclusive seus extremos.
- Entradas ausentes, limites técnicos, sinal inválido, fração não representável e overflow falham com `IllegalArgumentException` e diagnóstico de categoria; sem status HTTP, dependência de framework ou reprodução do valor recebido na mensagem.

Não haverá soma, multiplicação financeira, reserva, persistência, endpoint ou tipo que anuncie regras de dinheiro/quantidade/preço ainda não aprovadas. Esses tipos e escalas financeiras continuam na RF03. A representação genérica é uma adaptação autoral dos requisitos de exatidão encontrados na referência; não são copiados arquivos externos nem declarada uma licença que a origem não informou.

O uso de record técnico e seus acessores imutáveis é intencional para representar/interoperar unidades e escala; não expõe estado mutável nem substitui agregados financeiros. Essa exceção de representação aos acessores de Object Calisthenics deve constar no PR.

## P07 — contrato de adaptação

Definição canônica em Markdown, catálogo e integração às regras. Fontes aprovadas do Próspero substituem FDD obrigatório. O agente devolve propostas ao orquestrador; não grava arquivos, instala/renderiza, implementa arquitetura ou aprova a si mesmo. Esforço medium; modelo/provider herdados, sem impor Claude/Sonnet.

A configuração Codex só será anunciada como nativa após leitura da referência oficial/schema e evidência no CLI instalado. A configuração deve permanecer no projeto, sem alterar autenticação, hooks ou configurações pessoais. Documentar diferença entre restrição técnica de escrita e limites de leitura/segredos/rede do processo. Se houver limitação real, oferecer delegação utilizável no runtime disponível, sem fingir descoberta nativa. Fontes incompletas ou conflitantes produzem lacunas/omissões, nunca eventos, SAGA operacional ou réplicas inventados.

## P06 — limites do planejamento

Prever controlador e gerador separados do fluxo financeiro; rampas limitadas, cancelamento, limites globais, alvos permitidos, proteção do controle e limpeza por API. Separar carga oferecida/atingida, descartes no gerador, requisições pendentes, respostas, timeouts e resultados de negócio. Percentis exigem janela, amostras e tratamento de erros; evitar ocultar espera do agendador e saturação do gerador.

Prometheus atual coleta métricas das quatro aplicações. Fontes de CPU/container/host, banco e Kafka ainda devem ser definidas. Quatro PostgreSQL independentes e Kafka de nó único/RF1 não sustentam uma medição de replicação distribuída: usar N/A, não zero. Defasagem de projeções depende de eventos reais. Os dashboards técnicos complementarão os quatro de negócio da V01. Não há stress, nova stack ou instalação nesta rodada.

## Evidências e sequência

1. QA cria testes e registra RED sobre APIs mínimas ainda sem comportamento; erro de importação não conta como RED de invariante.
2. Implementador entrega GREEN; QA executa testes puros e `verify` isolado MO/Java26. Revisão e segurança em agentes distintos.
3. Publicar PR P05 com fonte, contrato, trade-offs e backlog P06.
4. Implementar e verificar P07; exercício comportamental independente e configuração/descoberta reais quando suportadas; PR próprio.

Dispensa de XLSX da P05: ADR-004, invariante interna sem comportamento novo por API. P07 é configuração/documentação, sem nova API. Planilhas da futura P06 serão avaliadas no refinamento próprio. Não repetir Angular, toda a stack ou scanners de dependências sem mudanças que o justifiquem. Nenhum parecer constitui aceite de merge.
