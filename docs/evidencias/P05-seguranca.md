# P05 — segurança independente

Data: 2026-10-17. Agente exclusivo de segurança, esforço medium, separado do implementador, revisão e QA. Aplicados o adaptador local `security-audit`, seu upstream e checklist. Parecer: **seguir**, sem achados de segurança substanciados no escopo examinado. Este parecer não autoriza merge.

## Escopo e ameaça

Base Git `dd057b7bbd4283ba9908e6f379f2d420ee7dd642`, branch `task/p05-reuso-decimal`; candidato ainda sem commit, com novos arquivos decimais/documentais e alterações nos roadmaps. Foram lidos o refinamento P05/P07, os três arquivos de produção decimal, `DecimalExatoTest`, a pesquisa de reaproveitamento, o plano P06 e os roadmaps afetados. Não foram editados arquivos de produção ou testes por este agente.

Ativos: precisão das conversões, recursos locais, integridade das regras financeiras e sigilo de dados recebidos. Entrada: `BigDecimal`, escala e política de sinal fornecidos por código chamador. Não existe novo endpoint, parser textual, persistência, rede, subprocesso, autenticação ou fronteira entre serviços nesta entrega. A futura API de carga é somente planejamento.

## Fronteiras examinadas

- **Exatidão:** `setScale(..., UNNECESSARY)` recusa frações não representáveis; `longValueExact()` recusa overflow. Não há `double`, truncamento ou arredondamento implícito. O construtor de `ValorEscalado` valida a escala inclusive quando chamado sem passar pelo helper.
- **Exaustão:** o helper valida escala original entre −18 e 18 antes de expandir o número; limita `unscaledValue().bitLength()` a 128 antes de calcular precisão, que também fica limitada a 38. A diferença entre escalas chega no máximo a 36, mantendo intermediários limitados. Zero com expoente extremo também é recusado. A construção prévia de um `BigDecimal` enorme fica fora desta fronteira: futuros parsers HTTP devem limitar corpo e representação antes de alocar o número.
- **Diagnóstico/sigilo:** mensagens são constantes por categoria, sem interpolar o valor recebido, e exceções de aritmética não são anexadas como causa. Os arquivos examinados não introduzem logs, credenciais, tokens ou leitura de configuração sensível. Revisão manual de conteúdo não substitui scan completo de segredos.
- **Domínio:** política de sinal é explícita; o record técnico continua assinado e imutável. Nenhuma regra de moeda, tick, reserva ou posição é apresentada como implementada. Não existe chamador externo novo em `src/main`.
- **Proveniência:** a pesquisa fixa a referência MinervaFinancas `34fc98a6ac47f2ffc2bf23f055a3fd9ac15c7530`, declara implementação autoral e ausência de licença explícita, sem tratar atribuição como permissão para redistribuição. Não foram introduzidas dependências ou arquivos executáveis da origem. Não foi feita perícia de similaridade ou análise jurídica da licença.
- **Planejamento P06:** prevê limites globais, cancelamento, alvos e rotas permitidos, proibição de URL/comando/header arbitrários, proteção contra chamadas indevidas pelo navegador, retenção e limpeza por API. São critérios futuros, sem afirmar controles ativos. Não foi executado stress ou alterado ambiente compartilhado.

## Evidência executada

`git diff --check`: exit 0. Busca dos símbolos novos em `middle-office/src/main` encontrou apenas suas definições e uso interno do helper. Inspeção de status/diff não identificou mudança de manifest, lockfile, imagem ou dependência; scanners de dependências não foram repetidos.

Verificação adversarial pura independente, executada em diretório temporário por Python/subprocess: compilou os três fontes com `javac` e executou um probe com `java -Xmx64m -XX:ActiveProcessorCount=1`, prazo externo de 10 segundos. **113 verificações passaram**, exit 0: escalas extremas de `int` para zero/positivo/negativo; limites de precisão de 39 dígitos e 129 bits nos dois sinais; round-trip dos cinco valores `long` (extremos, −1, zero e 1) em todas as 19 escalas; overflow após expansão e fração mínima não representável; categorias de erro e ausência de causa anexada. O diretório temporário foi removido automaticamente. O probe não instalou dependências nem utilizou rede, Docker ou serviços.

Runtime desse probe: Corretto OpenJDK 25.0.3. Ele verifica o algoritmo puro compatível com essa linguagem, **não substitui** o build Quarkus/Java 26 nem a execução independente de QA. O relatório de QA consultado ainda registrava GREEN/verify pendentes; nenhum sucesso desses passos foi presumido aqui.

Identidade dos fontes examinados (SHA-256):

| Arquivo | SHA-256 |
| --- | --- |
| `RegraDeSinal.java` | `dbb903627d06b85fa5f071b80006dc28a929d96ce34a768e0ce3507dbc0fc74a` |
| `ValorEscalado.java` | `63c4470211c0fbe05ae8869927175ee1c5d58cf5ab446b3fce12e8a98385c9c0` |
| `ConversaoDecimalExata.java` | `928767795d5b13c8b279e5cede586adf403c9c49c6f83c5e8163c07e9a6fda24` |

## Limites e conclusão

O candidato de expansão decimal irrestrita foi refutado pela ordem das validações e pelos testes de fronteira; não constitui achado. Não há pendência de validação de vulnerabilidade específica neste escopo. Sem scanners de histórico/segredos, consulta de alertas GitHub, testes ofensivos ou auditoria dos serviços preexistentes nesta rodada. Não se afirma segurança global, validação remota da CI ou cumprimento dos RNFs finais. Mudanças posteriores nos fontes exigem reavaliar o impacto deste parecer.
