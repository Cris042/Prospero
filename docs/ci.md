# CI/CD local: revisão, testes e segurança

## Papéis e execução

Três workflows GitHub Actions: `revisao.yml`, `testes.yml` e `seguranca.yml`. Eles executam gates determinísticos; a revisão semântica e as auditorias da sessão usam os agentes separados medium definidos nas regras. A opção de executar agentes IA dentro da CI continua pendente; não há chamadas a APIs pagas nem uso implícito de assinaturas.

Gatilhos: PR interno, push em main e execução manual. Forks são negados na condição do job antes de reservar runner. Não se usa `pull_request_target`. Nenhum workflow faz merge, tag, release, publicação de imagens ou deploy do ambiente do usuário.

Cada workflow publica um comentário pt-BR por PR, atualizando seu marcador. O job de comentário não faz checkout, aceita somente resultados enumerados, confirma que o SHA ainda é atual e recebe permissão de escrita somente para essa ação. Código dos checks roda com token somente leitura e checkout sem credenciais. Actions estão fixadas por commit.

## Runner e custo zero

Usar **um runner Linux x64 self-hosted dedicado**, com label `prospero`, numa VM/ambiente com Docker próprio e sem compartilhar socket, HOME, memória ou credenciais pessoais do host/Horus. Uma conta diferente no grupo Docker do Horus não fornece esse isolamento. Uma instância de runner serializa os jobs; os builds e módulos também são sequenciais.

Preparar Docker Engine, Compose>=2.24.4, Python3, Git, Bash, Node24.16.0 e dependências do Chromium (`npm exec playwright install --with-deps chromium`, feito pelo operador ao preparar a VM). O JDK26 dos checks vem de container. Usar runner atualizado compatível com actions em Node24. O registro é feito nas configurações Actions/Runners do repositório; tokens de registro não são arquivos do projeto.

O teste integrado recusa iniciar se houver menos de4096MiB disponíveis. Não para Horus nem desenvolvimento automaticamente. A CI não deve duplicar o stack no host compartilhado sem orçamento e isolamento comprovados. A sessão atual validou a aplicação local; isso não comprova uma execução remota ou runner registrado.

Self-hosted não consome minutos hospedados; não há upload obrigatório de cache/artefatos pagos. Logs e step summaries registram os resultados. Em plano GitHub Free privado, branch protection pode não estar disponível: o usuário deve conferir os três checks antes do merge. Não contratar upgrade para habilitá-la.

## Isolamento do teste

`ci-testes.sh` exige run_id/attempt numéricos. O projeto Compose será `prospero-ci-<run>-<attempt>`, com imagens `ci-<run>-<attempt>` e portas loopback dinâmicas por `ports: !override`. Volumes e redes pertencem ao projeto; não há nomes externos compartilhados. O smoke descobre as portas por `docker compose port`.

A finalização usa os mesmos arquivos e o projeto da subida; remove somente seus containers, redes, volumes e tags. Não usa prune global. Logs limitados de falha ficam em `.ci-evidencias/`, sem `.env`, config expandida ou dumps de credenciais. Cancelamentos tratam sinais; após interrupção abrupta do runner, o operador deve remover apenas o projeto/tag identificados pelos IDs da execução.

## Gates

| Workflow | Verificações |
| --- | --- |
| Revisão | Links/documentação, integridade das14skills, fronteiras de código, Compose e Prettier. |
| Testes | `verify` independente por MS, Angular/Vitest, builds, Compose real, smoke API/telemetria e Playwright desktop/mobile. |
| Segurança | Proveniência/fronteiras, npm audit produção, Gitleaks com redação e Trivy para dependências reais resolvidas, incluindo JARs empacotados, em staging temporário com o lockfile frontend. |

Gitleaks exige histórico completo. Trivy usa `rootfs` para os JARs de cada MS, exige presença dos componentes Java centrais no relatório e usa `fs` separado para o lockfile npm. Analisa os artefatos resolvidos em modo `--offline-scan`, sem resolver POMs remotos novamente; a base de advisories ainda precisa ser baixada/atualizada. Gitleaks/Trivy têm versões/digests fixados. Falha de scanner, rede ou banco de advisories falha o check; não equivale a ausência de vulnerabilidades. Scans não oferecem prova absoluta de segurança. A imagem de uma dependência de infraestrutura não está automaticamente coberta pelo scan de JARs da aplicação.

## Validação e referências

O projeto inclui scripts executáveis localmente pelo runner. O status de ativação e as URLs dos checks/PRs ficam no roadmap e nas entregas. A [pesquisa de padrões](pesquisas/ci-cd.md) registra fontes oficiais, escolhas e limites. O [refinamento complementar](refinamentos/P04-skills-ci.md) reúne cinco pareceres independentes.
