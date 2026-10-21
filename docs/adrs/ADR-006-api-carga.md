# ADR-006 — Linguagem e isolamento da API de carga

- Data: 2026-10-17
- Estado: **Aceita para a linguagem Go**; decisão técnica stdlib/executor restrito registrada, etapa A em validação
- Decisor: usuário; aceite explícito de Go em 2026-10-17
- Origem: P06 e pedido do usuário para avaliar Go ou Rust

## Contexto e escopo

A P06 precisa iniciar, consultar e cancelar execuções locais, limitar o tráfego e apresentar carga oferecida/atingida, erros, descartes, latência e recursos do gerador separadamente do sistema. O usuário pediu avaliar Go ou Rust para essa API e depois autorizou explicitamente adotar Go. A etapa A do controlador/gerador está em validação; os quatro serviços financeiros continuam Java/Quarkus e a P03 entrega o runner XLSX.

Propõe-se uma ferramenta técnica em container Compose opt-in, fora dos processos medidos, sem banco financeiro, acesso a Kafka/Redis, dependência de implementação dos quatro serviços ou socket Docker. Não cria um quinto contexto de negócio. A API continua sujeita ao ADR-003 e aos cenários por HTTP/XLSX do ADR-004. O diretório, contrato, toolchain e escolha do motor estão registrados na seção de implementação, após experimento anterior ao código dependente. A pesquisa e o desenho macro estão no [HLD-006](../hlds/HLD-006-api-carga.md).

A escolha da linguagem do controlador é separada da escolha do gerador. Esta análise não altera requisitos financeiros, não reduz o aceite da P06 e não comprova RNF04/RNF07.

## Critérios

Implementação e manutenção proporcionais; custo financeiro zero; isolamento de recursos; cancelamento e término verificáveis; limites de conexões, requisições, bytes e duração; fontes reais de observabilidade; build reproduzível e testes de concorrência. Nenhum benchmark comparativo foi executado: não há vencedor comprovado em CPU, memória ou latência.

## Alternativas

| Alternativa | Benefícios | Custos e riscos |
| --- | --- | --- |
| Go com `net/http` e `context` | Biblioteca padrão cobre servidor/cliente HTTP, deadlines, transporte e cancelamento; framework HTTP é dispensável para esta API pequena. | GC e alocações também consomem recursos do gerador. Cancelar contexto não encerra goroutines automaticamente; é necessário propagá-lo e aguardar término. |
| Rust com Tokio, axum e reqwest | Runtime assíncrono e tipos permitem explicitar estados e coordenação de tarefas; Rust seguro evita várias classes de erro de memória. | Mais escolhas de crates/features e gestão de tarefas; segurança de memória não resolve SSRF, tráfego ilimitado ou tarefas órfãs. Escolha defensável se aprender/manter Rust for objetivo do projeto. |
| Node, candidato inicial | Runtime já usado pelo frontend/runner; worker simplifica isolamento do parser/gerador dentro do processo. | Gerador e controle precisam orçamentos próprios; event loop/alocações podem distorcer agendamento. O pedido atual prioriza avaliar Go/Rust. |
| Manter P06 planejada | Evita nova stack enquanto faltam decisões. | Não entrega controle de carga nem evidências de desempenho. |

Go não está no PATH do host observado; Rust/Cargo 1.96.0 estão disponíveis. Docker permite builds isolados de ambas as opções; a instalação atual não decide arquitetura. Versões de adoção exigirão verificação/fixação próprias, sem `latest`.

## Decisão de linguagem e avaliação do motor

**Go adotado como linguagem da API técnica**, por aceite explícito do usuário: “pode adotar GO”. A recomendação anterior considerou simplicidade de manutenção e cobertura da biblioteca padrão, sem alegar superioridade de desempenho. A aprovação da linguagem é distinta da escolha técnica de framework/motor registrada ao final; a etapa A está em implementação/validação autorizada.

O motor permanece uma decisão separada:

- **k6 local supervisionado:** reaproveita modelos aberto/fechado, executores e métricas. Exige adaptar o contrato local, limitar tráfego/saídas, fixar binário e scripts, mapear contagens e comprovar término do processo filho. Não aceitar script, comando ou argumentos livres do cliente; sem shell. A API interna, se utilizada, permanece restrita ao supervisor. Conferir capacidades da versão fixada: notas da v2 removem `externally-controlled` e controles legados; não projetar sobre exemplos antigos de pause/resume/scale/status.
- **Gerador próprio restrito em Go:** mantém binário único e permite impor limites durante leitura/agendamento diretamente. Transfere ao projeto a responsabilidade por agendamento, categorias de descarte, contabilidade, estatística e transporte. Só escolher após confrontar o custo do adaptador k6 com os aceites; não construir ferramenta de carga genérica por antecipação.

Arquitetura e viabilidade preferem avaliar primeiro k6; operação e segurança favorecem gerador próprio limitado; QA exige validar ambos pelos mesmos critérios. Todos recomendam Go para o controlador sem concluir vantagem de desempenho. A divergência do motor permanece explícita. A pesquisa solicitada após o aceite também incluiu Vegeta como biblioteca Go e Fortio; [comparação e critérios no HLD-006](../hlds/HLD-006-api-carga.md#pesquisa-do-motor-de-carga).

## Condições antes da implementação

1. Linguagem resolvida: Go. Validar e registrar framework, motor, versões e limites da entrega; recomendações da pesquisa não equivalem a bibliotecas instaladas.
2. API autenticada por credencial externa ao Git/imagem, alvos por serviço/rota enumerados, origem/Host e entradas limitados; nenhuma URL/header/comando arbitrário. Separar credencial de controle do tráfego gerado.
3. Configurar clientes explicitamente: timeout, limite de resposta/conexões, proxy do ambiente desabilitado e redirects recusados. Go e reqwest seguem redirects por padrão; o transporte padrão Go consulta proxies do ambiente. Não depender desses defaults.
4. Fixar orçamento também no modo fechado: VUs não limitam RPS. Definir teto global, pacing declarado, limite absoluto de chamadas e ausência de rajadas para recuperar chegadas atrasadas.
5. Categorias disjuntas e equações de conservação; relógio monotônico; fim da oferta separado da drenagem limitada; percentis com população/denominadores explícitos. Não contar erro de transporte como resultado financeiro.
6. Reserva atômica de uma execução, cancelamento idempotente, término em prazo limitado e nenhum tráfego órfão após falha/reinício. Definir retenção limitada e identidade da instância caso histórico seja volátil.
7. Preflight externo de RAM/CPU/disco com validade definida, sem conceder comandos/socket Docker ao controlador. Não interromper Horus nem duplicar a stack para medir. Teto inicial de recursos será medido, não presumido suficiente.
8. Conferir fontes antes de criar painéis: atualmente Prometheus coleta somente `/q/metrics` dos quatro serviços. Host/container/DB/Kafka detalhados exigem coletores e avaliação de permissões/orçamento; sua ausência não é zero. Não declarar P06 concluída sem atender ao aceite vigente ou decisão explícita de escopo.

## Validação e consequências

Após fechar as escolhas técnicas restantes, testes derivados de contrato antes da implementação: relógio/transporte controlados, corrida de início, aberto versus fechado com alvo lento, saturação/descarte, conservação de contagens, limites, cancelamento e morte do controlador, autenticação/Origin/Host, token ausente nos relatórios e métricas reais confrontadas com resultados. API/OpenAPI/Problem Details e workbook P03 cobrem iniciar, consultar, cancelar e rejeições; falhas de infraestrutura usam harness próprio.

A implementação Go acrescentará uma stack técnica e exigirá CI, inventário e imagem específicos. Rust foi alternativa avaliada, não escolhida. k6 acrescentaria binário/processo; gerador próprio acrescentaria código crítico de medição. Em todos os casos, gerador e aplicações compartilham hardware local, portanto isolamento de container não elimina interferência. O guia GC Go esclarece que o limite de memória do runtime é flexível e não substitui o cgroup.

Revisitar após experimento equivalente revelar saturação do gerador, necessidade de protocolo novo ou custo de manutenção incompatível com o recorte. A implementação afetará HLD-001, libs, Compose, CI e P06; PRD só muda com alteração de requisito. Não substitui ADR-001 dos quatro contextos Java.

## Fontes

- [Go HTTP](https://pkg.go.dev/net/http), [context](https://pkg.go.dev/context) e [guia GC](https://go.dev/doc/gc-guide).
- [Rust reqwest ClientBuilder](https://docs.rs/reqwest/latest/reqwest/struct.ClientBuilder.html), [Tokio shutdown](https://tokio.rs/tokio/topics/shutdown) e [axum](https://docs.rs/axum/latest/axum/).
- [Modelos aberto/fechado k6](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/open-vs-closed/), [API k6](https://grafana.com/docs/k6/latest/reference/k6-rest-api/) e [notas v2](https://github.com/grafana/k6/releases/tag/v2.0.0).
- [P06](../roadmap.md#p06--api-de-carga-progressiva-e-observabilidade), [HLD-001](../hlds/HLD-001-arquitetura-prospero.md), ADR-001/003/004 e fontes de configuração `docker-compose.yml`/`infra/prometheus/prometheus.yml`.

## Histórico e aceite

- 2026-10-17: proposta após cinco perspectivas independentes medium e rodada complementar Go/Rust solicitada pelo usuário.
- 2026-10-17: usuário autoriza “pode adotar GO” e solicita pesquisa de frameworks/técnicas, HLD e atualização do README. **Go aceito**, sem reconfirmar a escolha; framework/motor e orçamento continuam em avaliação técnica.
- Pesquisa/documentação não executaram build, benchmark ou código P06; fontes, alternativas e desenho estão no HLD-006.

### Escolha técnica da etapa A — 2026-10-17

Após autorização de implementar P06, cinco pareceres medium independentes recomendaram seguir com ressalvas. Experimento temporário Go 1.27.2 comparou Huma 2.39.1/humago e `net/http`, Vegeta 12.12.0 e leitura nativa limitada contra o mesmo servidor descartável. Huma forneceu OpenAPI automático e validação, mas seus erros padrão refletiram o objeto sentinela; exige transformação; no experimento o transformer sanitizado eliminou a reflexão, sem indicar falha inerente no framework. Ambos os decoders aceitam nomes duplicados por padrão, exigindo validação explícita. Vegeta com `MaxBody=4096` reteve 4096 bytes mas o alvo continuou escrevendo até o timeout; `Stop` não provou aborto em voo. A leitura nativa limitada retornou após 4097 bytes e o contexto interrompeu a leitura ativa. Bytes aceitos por Write no servidor não são captura de tráfego de rede. Detalhes finais e limites serão registrados no PR.

Escolha do implementador: **biblioteca padrão `net/http` e executor nativo restrito**, sem módulos runtime externos. A API pequena manterá OpenAPI explícito e teste de aderência; parsing estrito rejeita duplicatas/campos desconhecidos e erros omitem entradas. Evita adaptar o ciclo de vida/contagens do motor reutilizado, preservando responsabilidade local por calendário, transporte e estatística. Não é declaração de maior desempenho. Go **1.27.2**, arquivo oficial linux-amd64 SHA-256 `ecbadb99091a3f46e31f5f934b068b1864eafa7995211b39eaddf76996045fe5`, verificado antes do uso. Imagem builder/runtime fixadas na implementação.

Contrato, limites combinados, atestado host assinado vinculado ao plano/instância, retenção e populações estão na seção de implementação do HLD-006. A entrega técnica não altera PRD nem dá por concluída a etapa financeira ou as fontes de observabilidade ainda indisponíveis. A escolha técnica integra a implementação autorizada; o aceite de Go pelo usuário permanece distinto da decisão do motor.

### Manutenção de segurança — 2026-10-21

Go 1.27.3 substitui 1.27.2 no toolchain fixado para corrigir [GO-2026-6809](https://pkg.go.dev/vuln/GO-2026-6809), encontrada pelo gate de segurança do PR #27. Imagem/digest correntes no inventário; código, contratos, isolamento e decisão por biblioteca padrão permanecem iguais. O registro original de versão/experimento acima é histórico; a correção não comprova desempenho nem conclui P06. Evidências de race/vet/build/govulncheck pertencem ao PR.
