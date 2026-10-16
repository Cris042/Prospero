# Verificação independente de skills, CI e memória

Executada por agente de testes separado do implementador, em 16/10/2026. Evidências anteriores da aplicação permanecem em [P02-testes](P02-testes.md); não foram repetidas sem alteração relevante.

## CI e integridade

- `python3 scripts/verificar-documentacao.py`: aprovado, com 42 documentos, 170 links locais e 14 skills com hashes conferidos antes de adicionar este relatório. A primeira execução encontrou somente o resumo `docs/entregas/P02.md` ainda não criado; o arquivo foi entregue e a checagem final aprovou.
- `python3 scripts/verificar-estrutura.py`: aprovado; serviços sem dependências de implementação entre si, REST isolado de JPA e domínio/Helpers puros.
- `bash -n scripts/ci-revisao.sh scripts/ci-seguranca.sh scripts/ci-testes.sh scripts/memoria.sh`: aprovado. Todos os scripts Python foram analisados com `ast.parse`, sem erros.
- Os três workflows foram carregados com PyYAML; conferidos ausência de `pull_request_target`, gate de PR interno, runner com label `prospero`, permissão geral `contents: read` e checkout sem persistir credenciais. Scripts de comentário passaram análise JavaScript dentro de função assíncrona, como em `github-script`.
- Comentários foram executados com mocks locais de contexto e cliente GitHub: produziram corpo com quebras de linha e SHA esperado. Não houve chamadas GitHub, envio de comentários nem uso de token de escrita.
- `PROSPERO_CI_TAG=ci-123-1 docker compose -p prospero-ci-123-1 -f docker-compose.yml -f docker-compose.ci.yml config --format json`: configuração analisada em memória, sem imprimir credenciais. Conferidos 16 serviços, todas as dez portas publicadas em `127.0.0.1` com `published: 0`, cinco imagens próprias com tag exclusiva e todos os volumes/redes com nome do projeto, sem recursos externos.
- Preflight de memória: mínimo artificial de 1 MiB aprovou e mínimo impossível de 999999999 MiB recusou com saída 1. Identidade CI não numérica foi recusada com saída 2 antes de qualquer operação Docker.

Não foi iniciada segunda stack completa: o host compartilhado não tem orçamento comprovado para isso. O isolamento de configuração foi verificado estaticamente; a descoberta de portas efetivamente alocadas e o cleanup após execução completa da CI continuam dependentes do runner dedicado. Não houve registro de runner no host do Horus, execução GitHub Actions nem validação remota dos gatilhos/status. Scanners Gitleaks/Trivy pertencem à auditoria de segurança separada; este relatório não declara seus resultados.

## Memória real e persistência

A CLI ai-memory 3.1.0 consultou `fundacao` no escopo explícito `prospero/Prospero` e encontrou `working/fundacao-e-governanca.md`. A página completa foi recuperada por `read-page --json`, incluindo escopo, título, corpo e frontmatter.

Foi reiniciado somente o serviço `ai-memory` do Compose `infra/memoria/compose.yml`. A leitura posterior retornou JSON exatamente igual ao anterior. SHA-256 da representação JSON com chaves ordenadas: `80af9356a9167d81f5d7f661d23ee2b240bf54c6278fb2871ba1a3ddd4a91b1d`.

Também foi criado backup privado fora do repositório, em diretório com permissão 0700 e arquivo 0600. A restauração direta pela CLI recusou a existência de outro processo ai-memory, mesmo com destino diferente. Para preservar o servidor ativo, a restauração foi realizada em container efêmero com apenas o diretório de recuperação montado, sem acesso ao diretório ativo.

O conteúdo restaurado foi iniciado em servidor de teste separado na porta loopback 49375; sua página retornou o mesmo JSON completo. O backup não inclui o arquivo bearer-token: ele foi copiado separadamente para o diretório privado de recuperação, sem imprimir seu conteúdo. Encerrado somente o servidor de teste; a memória ativa permaneceu em 49374. Backup e cópia de recuperação ficaram fora do Git para inspeção privada do operador.

A comparação dos IDs dos 34 containers já em execução antes do teste confirmou que continuavam presentes depois da operação. Nenhum projeto Horus ou microsserviço de desenvolvimento foi parado pelo teste.

Esses resultados comprovam consulta, persistência após reinício e recuperação em diretório/servidor separado. Não comprovam captura automática pelos hooks: confiança manual pelo usuário e nova sessão permanecem necessárias conforme [operação da memória](../memoria.md).

## Reteste pontual do staging de dependências

Após o ajuste de `scripts/ci-seguranca.sh` para evitar resolução repetida de POMs via Maven Central, `bash -n scripts/ci-seguranca.sh` aprovou novamente. A leitura confirmou staging criado por `mktemp -d` sob `RUNNER_TEMP`, cópia restrita aos manifests npm e aos diretórios `target/quarkus-app/lib` dos quatro serviços, montagem somente leitura no scanner e `--offline-scan`. A ausência de um diretório de dependências faz `cp` falhar sob `set -e`, sem declarar scan aprovado.

O trap remove somente o caminho temporário único em `scan_dir`, entre aspas e após `--`; ele não remove os artefatos de origem nem o cache. O cache Trivy é montado separadamente em `/cache`, permanece após o container e não é alvo do cleanup. Sua retenção entre jobs depende da política de `RUNNER_TEMP` do runner; não foi comprovada como cache durável entre execuções. `--offline-scan` evita consultas adicionais para identificar dependências, mas não comprova disponibilidade offline dos bancos de advisories.

Esta verificação foi estática, limitada à sintaxe e às fronteiras de cópia/montagem/cleanup. Não executou scanner nem atribui resultado ao scan de vulnerabilidades; a execução é responsabilidade do agente de segurança.

## Guard de cobertura Java do scanner

O comando de scan foi corrigido posteriormente para `rootfs` por microsserviço e `fs` separado para npm. A verificação anterior de staging não comprovava reconhecimento dos JARs por `fs`; essa limitação foi identificada na auditoria de segurança. `bash -n scripts/ci-seguranca.sh` e análise AST de `scripts/verificar-scan-java.py` aprovaram após a correção.

O guard foi executado com oito fixtures de contrato em diretório temporário, removido ao término:

- Relatório `{}` e lista `Results` vazia: ambos recusados com saída 1 e indicação dos quatro componentes ausentes.
- Inventário Java sem Kafka: recusado com saída 1 e indicação de `org.apache.kafka:kafka-clients` ausente.
- Os mesmos nomes sob ecossistema npm e nomes presentes somente em vulnerabilidades, sem inventário `Packages`: ambos recusados com saída 1.
- Inventário Java identificado com Quarkus, Hibernate, PostgreSQL e Kafka: aprovado com saída 0, inclusive com vulnerabilidades vazias.
- Os quatro componentes distribuídos entre resultados de JARs diferentes: aprovado com saída 0.
- JSON malformado: recusado com saída diferente de zero.

Assim, relatório vazio não aprova cobertura, e inventário identificado permite o sucesso do guard. Esses testes não substituem a confirmação dos quatro componentes no JSON real produzido pelo Trivy: ela pertence à execução do agente de segurança. O implementador tornou `--list-all-pkgs` explícito no comando após a revisão; a ausência de `Packages` falha fechada no guard. Nenhum scanner foi executado por este agente no reteste.

## Diagnóstico de vulnerabilidades antes do cleanup

No ajuste final, a análise sintática Bash/Python aprovou. Três fixtures adicionais, todas com as quatro coordenadas Java exigidas, verificaram severidades: HIGH e CRITICAL recusaram com saída 1; LOW aprovou com saída 0 e cobertura identificada. Para os casos bloqueantes, stdout continha exatamente ID, pacote, versão instalada e versão corrigida, além dos rótulos fixos. Sentinelas em descrição, referências e campos extras do relatório/achado não apareceram em stdout ou stderr. LOW não emitiu diagnóstico bloqueante.

A leitura do script confirmou que o retorno do scanner é capturado em `scan_rc`, permitindo executar o guard e imprimir diagnóstico antes do trap de limpeza. Quando o JSON aprova o guard, um retorno não zero do scanner continua sendo propagado. Com JSON inválido, o guard falha fechado. Não houve execução do scanner nesse reteste.

## Alias de identidade Hibernate e correção LZ4

Uma fixture adicional confirmou que o guard aceita o inventário real normalizado do Trivy, com `org.hibernate:hibernate-core` junto a Quarkus, PostgreSQL e Kafka: saída 0 e quatro componentes identificados. Os demais casos já aprovados não foram repetidos.

Após o achado LZ4, o reteste Java dos quatro serviços, a leitura das bibliotecas empacotadas e seus metadados foram registrados em [P02-testes](P02-testes.md). Todos os quatro pacotes contêm LZ4 1.11.4 e nenhum 1.11.1; a auditoria de segurança deve confirmar o novo inventário e resultado do scan real. Este agente não executou scanner.

## Verificação final após reconstrução

O smoke real aprovou novamente os 31 checks após reconstrução/recriação do ambiente. A inspeção dentro dos quatro containers Java confirmou LZ4 1.11.4 em `/app/lib/main`, sem 1.11.1. A evidência de runtime consta em [P02-testes](P02-testes.md); o resultado de vulnerabilidades pertence ao relatório do agente de segurança.
