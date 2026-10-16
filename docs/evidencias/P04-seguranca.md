# P04 — Auditoria independente de segurança

Data: 2026-10-16. Agente exclusivo de segurança, esforço medium, distinto do implementador, revisão e testes. Escopo: arquivos locais da P04 e correções da fundação relacionadas a credenciais, dependências e containers. A árvore ainda não tinha commits ao iniciar os scans; posteriormente o histórico local foi escaneado com HEAD observado `d886be1`. Este relatório não atesta uma execução no GitHub nem estende automaticamente os resultados a commits posteriores.

## Resultado

**Seguir com ressalvas: o achado da dependência LZ4 foi corrigido e a reanálise Java passou.** O Trivy identificou inicialmente `CVE-2026-106451`, classificada HIGH pelo scanner, em `at.yawk.lz4:lz4-java:1.11.1` nos quatro serviços. Após a atualização para `1.11.4` pelo implementador e novo empacotamento pelo agente de testes, a reanálise às 20:51:28 UTC terminou exit 0, sem HIGH/CRITICAL, e o guard de cobertura passou nos quatro serviços. Não foram encontrados segredos nos scans realizados; o npm audit de produção e o scan Trivy do lock npm retornaram zero vulnerabilidades no escopo configurado. Não permanecem achados altos substanciados no escopo auditado. Esses resultados não constituem garantia de segurança nem cobrem todas as imagens de infraestrutura.

## Dependência afetada confirmada e corrigida

O scan `rootfs` concluído às 20:44:56 UTC apresentou um resultado `Type: jar`, com 1.004 registros de pacotes e quatro ocorrências HIGH da mesma CVE, uma em cada `lib/main/at.yawk.lz4.lz4-java-1.11.1.jar`. Não houve CRITICAL. O relatório JSON ficou no cache privado temporário, fora do Git.

O [advisory do mantenedor](https://github.com/yawkat/lz4-java/security/advisories/GHSA-mcr4-qmvw-px4g) confirma as versões afetadas e a correção. O ataque depende de acesso local ao mesmo diretório temporário usado pela JVM, carga da implementação nativa e uma corrida de substituição do arquivo. Não foi demonstrada exploração HTTP ou execução remota no Prospero. O `/tmp` isolado por container reduz a exposição descrita; o problema permanece na dependência e bloqueia o gate HIGH configurado. A classificação do scanner não é uma afirmação de explorabilidade remota da aplicação.

A remediação foi atualizar a dependência transitiva para `1.11.4` nos quatro serviços, sem allowlist do advisory. O agente de testes produziu os novos empacotamentos; este agente reescaneou somente as quatro bibliotecas alteradas, usando bases já baixadas, sem build ou download adicional. O JSON corrigido identifica somente LZ4 `1.11.4` em cada serviço. Não foi executado exploit. Os testes e a compatibilidade de build pertencem ao relatório do agente de testes; este documento registra a evidência independente do scanner.

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
| Gitleaks `git /repo --config /repo/.gitleaks.toml --redact --no-banner` | Exit 0: seis commits e aproximadamente 870,17 KB, `no leaks found`; HEAD local observado `d886be1`. |
| Sintaxe dos comentários | YAML interpretado e cada script envolvido em função assíncrona para `node --check`: três resultados válidos, sem executar JavaScript nem postar comentários. |
| `.env` no índice Git | Não versionado na checagem realizada. |
| Trivy `fs` da raiz | Exit 1 por HTTP 429 durante resolução de POM; tentativa descartada como evidência de ausência de vulnerabilidades. |
| Trivy `fs` de staging sem fontes/POMs | Exit 0: somente `frontend/package-lock.json`, zero vulnerabilidades. A ausência de JARs no relatório revelou erro de cobertura do modo `fs`. |
| Trivy `rootfs` dos quatro diretórios `target/quarkus-app/lib` | Primeira tentativa expirou após baixar Java DB. Reexecução sem downloads, com `--timeout 20m`, terminou exit 1, com 1.004 registros Java e quatro ocorrências HIGH da CVE acima. Nenhum build foi executado por este agente. |
| Trivy `rootfs` após correção LZ4 | Exit 0 às 20:51:28 UTC: sem HIGH/CRITICAL nas quatro bibliotecas empacotadas. Bases existentes, atualizações desabilitadas, sem novos builds. JSON separado preserva o resultado anterior. |
| Guard Java sobre quatro recortes do JSON real | Os artefatos anteriores reprovaram por HIGH; após correção LZ4 e normalização exata do alias Hibernate, o guard real passou nos quatro recortes, com 250 componentes distintos por serviço, incluindo os quatro identificadores obrigatórios. |

Imagens de scanners usadas, fixadas por digest:

- `zricethezav/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f`.
- `aquasec/trivy:0.76.0@sha256:2416d1f74045b57274df1a340eee6b510cacd73c66a6ccb8da092b117eb7d222`.

Na primeira tentativa, o Trivy recebeu a raiz em mount somente leitura e falhou ao consultar um parent POM no Maven Central, com HTTP 429 e `Retry-After: 1800`. O escopo foi então limitado, sem novo build: cópias dos quatro `target/quarkus-app/lib`, cada um com 244 JARs regulares e aproximadamente 80,3 MB; `package.json` e lock npm foram analisados separadamente. O scanner executou como UID/GID do operador, com limite de 1 GiB e staging somente leitura. As bases ficaram em `/tmp/prospero-trivy-cache.b8wgyc4q`, fora do repositório. A reexecução válida usou `--skip-db-update --skip-java-db-update --offline-scan --pkg-types library --list-all-pkgs --format json`, sem Maven Central nem novos downloads.

A correção do modo é sustentada pela [tabela oficial de cobertura](https://trivy.dev/docs/latest/coverage/language/) e pelo [código do Trivy 0.76.0](https://github.com/aquasecurity/trivy/blob/v0.76.0/pkg/commands/artifact/run.go): `fs` desabilita pacotes individuais; JARs precisam de `rootfs` ou imagem. O gate foi adaptado pelo implementador para separar `rootfs` Java de `fs` npm e verificar presença de componentes no JSON.

Os recortes por serviço do JSON real contêm 250 nomes de pacotes distintos. O scanner identifica Hibernate como `org.hibernate:hibernate-core:7.4.9.Final`, embora o caminho do artefato seja `org.hibernate.orm.hibernate-core-7.4.9.Final.jar`. O guard inicialmente exigia apenas `org.hibernate.orm:hibernate-core`; o implementador adicionou normalização exclusiva desse alias, sem retirar a exigência da biblioteca. Quarkus core, Hibernate core, PostgreSQL e Kafka clients foram reconhecidos pelo guard nos quatro recortes corrigidos. O relatório final temporário é `/tmp/prospero-trivy-cache.b8wgyc4q/java-report-corrigido.json`; os recortes correspondentes usam nomes `<serviço>-corrigido.json`. São evidências locais temporárias, não artefatos publicados pela CI.

## Pendências e limites

1. LZ4 e o alias Hibernate foram corrigidos e verificados; não resta essa pendência. O gate continua obrigado a reprovar achados HIGH/CRITICAL e ausência de cobertura. Bases futuras podem introduzir novos advisories.
2. O scan histórico Git desta sessão cobriu seis commits; os resultados não incluem automaticamente commits posteriores. A CI ainda precisa executar e vincular seus resultados ao SHA final antes de aceitar uma entrega como validada por ela.
3. O runner dedicado ainda não foi ativado nem auditado em execução. Jobs internos e dependências executam código com os poderes desse runner; isolamento por projeto Compose não contém código malicioso no host. Não compartilhar seu daemon com desenvolvimento ou outros projetos.
4. Autenticação real do ai-memory, hooks e persistência foram descritos pelo implementador, mas não foram testados por este agente. Esta auditoria leu apenas a configuração versionada e não acessou dados privados ou token de autenticação.
5. Não houve pentest, build adicional, scan de imagens base/infraestrutura, consulta de alertas remotos nem aprovação de PR. A auditoria inicial de fontes técnicas não identificou achado alto confirmado; ausência de achado não prova ausência de falhas.

Fontes locais: [workflows](../../.github/workflows/seguranca.yml), [gate de segurança](../../scripts/ci-seguranca.sh), [Compose de CI](../../docker-compose.ci.yml), [guia de CI](../ci.md), [origens das skills](../skills/origens.json) e [guia de memória](../memoria.md).
