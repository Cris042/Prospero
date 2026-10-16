# P04 — Auditoria independente de segurança

Data: 2026-10-16. Agente exclusivo de segurança, esforço medium, distinto do implementador, revisão e testes. Escopo: arquivos locais da P04 e correções da fundação relacionadas a credenciais, dependências e containers. A árvore ainda não tinha commits ao iniciar os scans; este relatório não atesta um SHA publicado nem uma execução no GitHub.

## Resultado

**Seguir com ressalvas para revisão da entrega. A verificação de vulnerabilidades pelo Trivy ficou bloqueada por HTTP 429 do Maven Central; não há aprovação desse gate.** Não foram encontrados segredos pelo scan do conjunto de arquivos Git não ignorados; o npm audit de produção retornou zero vulnerabilidades. Isso não constitui garantia de segurança nem cobre todas as imagens de infraestrutura.

## Fronteiras e correções conferidas

- Os três workflows negam forks antes de agendar runner, fixam actions por SHA e fazem checkout sem persistir credenciais. Os jobs de checks têm `contents: read`.
- Os comentários ficam em jobs separados sem checkout, com `pull-requests: write` somente nesses jobs. O JavaScript usa resultado enumerado, confirma o SHA atual do PR, atualiza apenas comentário do bot com marcador fixo e não inclui logs arbitrários. O erro inicial de escaping foi corrigido; a sintaxe dos três scripts passou em `node --check`, sem executá-los contra a API.
- O checkout de segurança usa `fetch-depth: 0`; o script recusa repositório shallow. Os summaries referenciam o SHA efetivamente selecionado para avaliação.
- O Compose de CI usa projeto e imagens derivados de IDs numéricos, portas dinâmicas em loopback e limpeza limitada ao próprio projeto/tag. Não há prune global. O guia exige VM/daemon dedicado e explicita que compartilhar socket Docker, HOME ou credenciais com o host não oferece isolamento.
- A criação de `.env` usa `os.open` com exclusividade e modo `0600`. Cinco configurações de Maven Wrapper passaram a declarar checksum da distribuição. O conteúdo do `.env` local não foi lido nem publicado por esta auditoria.
- A configuração Gitleaks mantém regras padrão sem allowlists amplas; o gate recusa `.env` versionado. A versão anterior excluía caminhos inteiros, problema corrigido.
- O verificador de proveniência confere hashes e o conjunto de arquivos de cada skill, rejeitando extras e symlinks internos. Os adaptadores substituem explicitamente autorizações indevidas de merge, release e runtime OMO do upstream. Manifestos e hashes locais não substituem revisão de uma atualização de fornecedor.

## Evidências executadas

| Verificação | Resultado e alcance |
| --- | --- |
| `python3 scripts/verificar-documentacao.py` | Exit 0 após conclusão do relatório P02: 42 documentos, 170 links locais e 14 skills com hashes conferidos. Contagem anterior à criação deste relatório. |
| `npm --prefix frontend audit --omit=dev --audit-level=high` | Exit 0: `found 0 vulnerabilities`. Somente dependências npm de produção no estado consultado. |
| Gitleaks `stdin --redact --no-banner` | Exit 0: aproximadamente 1,49 MB, `no leaks found`. Entrada formada por `git ls-files -co --exclude-standard -z`, arquivos regulares sem symlinks; cobre arquivos presentes não ignorados, sem incluir o `.env` local ignorado. Não é scan de histórico. |
| Sintaxe dos comentários | YAML interpretado e cada script envolvido em função assíncrona para `node --check`: três resultados válidos, sem executar JavaScript nem postar comentários. |
| `.env` no índice Git | Não versionado na checagem realizada. |
| Trivy `fs --scanners vuln --severity HIGH,CRITICAL --exit-code 1` | Exit 1 por erro de rede/resolução, sem relatório final de vulnerabilidades. Não executou novo build. |

Imagens de scanners usadas, fixadas por digest:

- `zricethezav/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f`.
- `aquasec/trivy:0.76.0@sha256:2416d1f74045b57274df1a340eee6b510cacd73c66a6ccb8da092b117eb7d222`.

O Trivy recebeu a raiz em mount somente leitura, limite de memória de 1 GiB e exclusões de `.git`, `.agents` e `frontend/node_modules`. Seu banco de vulnerabilidades de aproximadamente 128 MiB foi baixado; a análise falhou ao consultar o parent POM `io.quarkus:quarkus-jdbc-postgresql-parent:3.40.1` no Maven Central, com HTTP 429 e `Retry-After: 1800`. A mensagem informa bloqueio temporário do IP e recomenda pré-popular/reutilizar cache Maven. Isso é indisponibilidade da verificação, não uma vulnerabilidade confirmada.

## Pendências e limites

1. Tornar a resolução do scanner reproduzível reutilizando o cache Maven, ou restringir explicitamente o scan aos JARs resolvidos dos quatro serviços e ao lock npm, sem resolução remota de POMs. Reexecutar o gate após corrigir essa causa; não ignorar o erro para obter aprovação.
2. O scan histórico Gitleaks da CI ainda precisa executar em commit real; o scan desta sessão foi do working tree. Resultados precisam ser vinculados ao SHA final antes de aceitar uma entrega como validada pela CI.
3. O runner dedicado ainda não foi ativado nem auditado em execução. Jobs internos e dependências executam código com os poderes desse runner; isolamento por projeto Compose não contém código malicioso no host. Não compartilhar seu daemon com desenvolvimento ou outros projetos.
4. Autenticação real do ai-memory, hooks e persistência foram descritos pelo implementador, mas não foram testados por este agente. Esta auditoria leu apenas a configuração versionada e não acessou dados privados ou token de autenticação.
5. Não houve pentest, build adicional, scan de imagens base/infraestrutura, consulta de alertas remotos nem aprovação de PR. A auditoria inicial de fontes técnicas não identificou achado alto confirmado; ausência de achado não prova ausência de falhas.

Fontes locais: [workflows](../../.github/workflows/seguranca.yml), [gate de segurança](../../scripts/ci-seguranca.sh), [Compose de CI](../../docker-compose.ci.yml), [guia de CI](../ci.md), [origens das skills](../skills/origens.json) e [guia de memória](../memoria.md).
