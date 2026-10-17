# P05 — revisão independente

Data: 2026-10-17. Agente exclusivo de revisão, esforço medium, separado do implementador, QA e segurança. Skill aplicada: `pr-audit`, adaptação local e upstream preservado. Revisão do checkout local da branch `task/p05-reuso-decimal`, base `dd057b7bbd4283ba9908e6f379f2d420ee7dd642`; ainda sem PR remoto nesta análise. Nenhum arquivo de produção foi alterado pelo revisor.

## Resultado

**Sem achados bloqueantes na revisão estática.** A implementação atende ao contrato técnico do [refinamento](../refinamentos/P05-P07-reuso-c4.md). Recomenda-se encaminhar à decisão do usuário após concluir e confrontar os relatórios independentes de testes e segurança. Esta revisão não autoriza merge nem comprova execução de testes.

## Escopo e evidências

Foram lidos regras, roadmap, inventário, ADRs 001–005, catálogo/skill de revisão, código e testes novos do pacote `br.prospero.mo.decimal`, teste ArchUnit existente, roadmaps global/MO, pesquisa de reuso e planejamento P06. A memória foi recuperada por `bash scripts/memoria.sh` no escopo `prospero/Prospero`: páginas completas `working/fundacao-e-governanca.md` e `working/reuso-minerva-financas.md`, confrontadas com os arquivos atuais. As lembranças são contexto, não autorização nem prova da implementação.

Comandos de inspeção: `git status --short`, `git branch --show-current`, `git rev-parse HEAD`, `git remote -v`, `git diff -- docs/roadmap.md middle-office/ROADMAP.md`, `git diff --check`, leitura dos arquivos e `sha256sum`. `git diff --check` terminou com exit 0, sem saída; arquivos novos não rastreados também foram lidos integralmente. Não foram executados builds ou testes pelo revisor; essa execução pertence ao QA independente.

| Afirmação | Evidência inspecionada | Resultado |
| --- | --- | --- |
| Representação imutável, assinada, com igualdade por unidades e escala | `ValorEscalado` é record, valida escala 0–18 no construtor e usa `BigDecimal.valueOf(unidades, escala)` | Confirmada por inspeção; inclui zero e extremos long |
| Conversão exata, sem arredondamento | `setScale(..., UNNECESSARY)` seguido de `unscaledValue().longValueExact()` | Fração não representável e overflow têm diagnósticos separados |
| Limites antes da expansão | Escala original −18–18; tamanho do inteiro por `bitLength` antes de `precision`; máximo 38 dígitos antes de `setScale` | Expoentes extremos e inteiros excessivos são recusados antes da expansão |
| Política de sinal explícita | Enum técnico e validação anterior ao ajuste de escala | Não cria dinheiro/preço/quantidade nem proíbe negativos no VO genérico |
| Domínio e Helper puros | Imports limitados aos tipos locais e `java.math`; nenhum I/O, estado mutável, framework ou outro microsserviço | Compatível com ADR-001/002; sem double |
| Escopo e proveniência proporcionais | Pesquisa fixa snapshot do doador e distingue inspiração autoral de cópia; limitações financeiras permanecem no roadmap | Sem stack ou escalas financeiras importadas |
| P06 permanece planejamento | Documento identifica decisões futuras, limites, fontes ausentes e replicação N/A | Não anuncia gerador, métricas ou capacidade implementados |

Não foram alterados manifests, dependências, migrações, APIs, CI ou políticas de execução. A superfície pública nova limita-se aos tipos técnicos no MO. Os acessores do record estão justificados como exceção de representação imutável no refinamento e na pesquisa; o Helper permanece sem estado. Não há abstração compartilhada antecipada.

Os testes inspecionados cobrem positivos e rejeições, extremos long, zeros finais, notação científica, sinal, escalas originais/destino extremas, precisão excessiva, igualdade e diagnósticos sem eco da entrada. A suficiência comportamental depende de sua execução; o relatório QA disponível durante esta revisão registrava RED e aguardava GREEN/verify. Não se presume aprovação posterior.

## Identidade dos arquivos revisados

Hashes SHA-256 para detectar mudanças posteriores que exijam revisão complementar:

| Arquivo no MO | SHA-256 |
| --- | --- |
| `decimal/dominio/RegraDeSinal.java` | `dbb903627d06b85fa5f071b80006dc28a929d96ce34a768e0ce3507dbc0fc74a` |
| `decimal/dominio/ValorEscalado.java` | `63c4470211c0fbe05ae8869927175ee1c5d58cf5ab446b3fce12e8a98385c9c0` |
| `decimal/helper/ConversaoDecimalExata.java` | `928767795d5b13c8b279e5cede586adf403c9c49c6f83c5e8163c07e9a6fda24` |
| Teste `decimal/DecimalExatoTest.java` | `0e7214edba9d741b56f737120ce46a7d8f80844ae8aca5e8e99821d72df05986` |

Os três primeiros caminhos são relativos a `middle-office/src/main/java/br/prospero/mo/`; o teste é relativo a `middle-office/src/test/java/br/prospero/mo/`.

Dispensa de XLSX: ADR-004, linha de invariante interna sem comportamento novo por API. Não houve avaliação de runtime financeiro, replay, reservas, latência, segurança operacional da futura P06 ou execução de código do doador. A P07 e a futura P08 não são aprovadas por este relatório.
