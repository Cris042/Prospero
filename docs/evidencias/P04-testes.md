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
