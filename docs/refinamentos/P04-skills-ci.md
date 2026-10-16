# P04 — Refinamento complementar de skills e CI

Data: 2026-10-16. Situação: pareceres consolidados; implementação/validação em andamento. Esta rodada foi introduzida durante a P02 por ampliação explícita do usuário; não é apresentada como refinamento anterior ao código já escrito.

## Brief comum

Prospero local e sem serviços pagos; quatro MS Quarkus3.40.1/Java26 e Angular22.2.3 em Compose16. Incorporar skills com fontes fixadas e adaptações locais, `refinar-task` obrigatória por nova tarefa, ai-memory real e remoção de continuidade.md. Revisão, testes e segurança em agentes separados medium. Três workflows: revisão, testes e segurança. Sem merge/release automáticos; comentários de PR em pt-BR autorizados. Proteger Horus e desenvolvimento usando projeto, imagens, portas e volumes exclusivos de CI. Repositório privado autorizado após validar stack. A escolha de agentes IA dentro da CI foi perguntada e permanece aberta; checks determinísticos podem avançar sem API paga.

## Pareceres independentes

| Agente efetivo | Perspectiva | Posição | Exigências |
| --- | --- | --- | --- |
| revisao | Arquitetura | Seguir com ressalvas | Builds por módulo, fronteiras preservadas, isolamento de projeto/imagens/portas, distinguir checks de revisão semântica. |
| testes | QA | Seguir com ressalvas | Java/Angular/E2E/smoke reais, resultados negativos claros, preservar stack existente e evidência vinculada ao commit. |
| seguranca | Segurança | Seguir com ressalvas | Bloquear forks no job, permissões mínimas, job de comentário sem checkout, runner dedicado, segredos fora dos logs. |
| refinamento_operacao | Operação | Seguir com ressalvas | Serializar carga, medir recursos, memória persistente independente e limpeza restrita. |
| refinamento_viabilidade | Viabilidade | Seguir com ressalvas | Entregar checks locais antes da ativação remota; reconciliar documentos e declarar runner/CI pendentes até comprovação. |

Foram cinco agentes distintos com esforço medium, em lotes respeitando os slots disponíveis. Nenhum implementou este escopo nem aprovou seu próprio código. Não houve divergência que exigisse tréplica; as ressalvas são complementares.

## Memória e fontes

A consulta CLI real ao ai-memory em `prospero/Prospero` retornou sem resultados na primeira execução, pois a instalação era nova. Depois foi gravada e recuperada a página `working/fundacao-e-governanca.md`. O servidor e os dados ficam fora do repositório. A captura automática ainda exige confiança nos hooks e recarga do Codex; essa condição não é confundida com a consulta manual já funcional.

Foram lidos rules, ADRs001–004 e o roadmap. A pesquisa de CI está em [pesquisas/ci-cd.md](../pesquisas/ci-cd.md). Decisões do usuário prevalecem sobre fontes upstream e não foram reabertas sem necessidade.

## Critérios e limites

Entregar 14 skills locais com proveniência, README e gatilhos; memória instalada/configurada sem API paga; estado no roadmap e memória. CI: três workflows, comentários pt-BR sem aprovação automática, versão/hash de actions e scanners fixados, fork negado antes do runner e isolamento/limpeza restritos. Gates devem falhar em erro real; não transformar ausência de scanner ou job em aprovação.

Não ativar agentes de IA em CI sem escolha/autenticação definidas. Não registrar o host compartilhado com Horus como runner com acesso ao seu Docker socket. A ativação exige runner isolado e recursos disponíveis; a escrita dos workflows é revisável antes disso. Plano GitHub Free privado pode impedir proteção obrigatória de branch, sem autorizar contratação de plano.
