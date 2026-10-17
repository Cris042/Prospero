# P05 — evidências independentes de testes

Agente exclusivo QA, esforço medium, separado do implementador, revisão e segurança. Contrato: [refinamento P05/P07](../refinamentos/P05-P07-reuso-c4.md). Nenhum arquivo de produção foi alterado pelo QA.

## RED — 2026-10-17, 03:03:45 UTC

[Testes](../../middle-office/src/test/java/br/prospero/mo/decimal/DecimalExatoTest.java) escritos contra as assinaturas compiláveis fornecidas pelo implementador, antes da implementação de conversão e `emDecimal()`.

Comando executado na raiz:

```bash
docker run --rm --name prospero-p05-qa-red --memory=1g --cpus=2 \
  -e MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2' \
  -v /mnt/c/Users/mclov/IdeaProjects/Prospero/middle-office:/workspace \
  -v /home/mclovin/.m2:/root/.m2 -w /workspace \
  eclipse-temurin:26.0.2.1_1-jdk-noble \
  bash ./mvnw -B -ntp -Dtest=DecimalExatoTest \
  -DargLine='-Xmx384m -XX:ActiveProcessorCount=2' test
```

Resultado final: **16 testes, 8 falhas de asserção, 6 erros, 2 sucessos, nenhum ignorado; exit 1; Maven 7,680 s**. A compilação passou. As falhas vieram do comportamento ausente: os stubs lançavam `UnsupportedOperationException("P05: comportamento pendente.")` onde os testes esperavam resultado exato ou `IllegalArgumentException`. Isso constitui RED de comportamento, não falha de importação, JDK ou infraestrutura. Construtor/igualdade representacional já passaram. Duas execuções preliminares de 15 testes também falharam; a execução final corresponde aos 16 testes atuais.

Log local transitório: `/tmp/prospero-p05-red.log`. Relatórios Surefire locais: `middle-office/target/surefire-reports/`. Esses caminhos não são artefatos publicados; este relatório registra a evidência resumida e não depende de sua persistência.

## Cobertura do contrato

- Unidades/escala e `emDecimal()`: zero, sinal negativo, extremos `long`, escalas 0, 1 e 18; igualdade inclui escala e hash é consistente.
- Conversão exata: ampliação de escala, remoção de zeros finais, notação científica e extremos `long` após escalonamento.
- Rejeições: fração não representável positiva/negativa/próxima de zero, overflow nos dois sentidos, entrada/política ausente e escala de destino inválida.
- Políticas `QUALQUER`, `NAO_NEGATIVO`, `POSITIVO`: positivo, zero e negativo, incluindo zero textual negativo.
- Limites de entrada: escala original −19/19 e extremos `int`, inclusive zero; precisão acima de 38 com escala original válida e inteiro de 10.001 dígitos.
- Proteção contra expansão de expoentes extremos: prazo máximo de dois segundos para operações puras com entradas previamente construídas. Não há sleep ou execução de carga.
- Diagnóstico: mensagem não vazia, sem reprodução da entrada sensível e categorias distintas para fração, overflow, sinal e escala original.

## GREEN e suíte afetada — 2026-10-17, 03:04:49 UTC

Após o implementador entregar a produção, QA executou o mesmo container/mounts/limites do RED, com nome `prospero-p05-qa-green` e comando Maven:

```bash
bash ./mvnw -B -ntp -DargLine='-Xmx384m -XX:ActiveProcessorCount=2' verify
```

Resultado: **BUILD SUCCESS, exit 0, 35,857 s; 25 testes, nenhuma falha, erro ou ignorado**. A execução inclui os **16 testes decimais**, quatro testes API preexistentes e cinco regras ArchUnit. O build/empacotamento Quarkus também terminou com sucesso. A suíte focal foi abrangida pelo `verify`, sem execução redundante adicional. Log local transitório: `/tmp/prospero-p05-green.log`.

Verificações adicionais reais, exit 0:

- `python3 scripts/verificar-documentacao.py`: 51 documentos, 197 links locais, 14 skills e hashes conferidos na repetição após atualizar este relatório (execução inicial: 50 documentos/196 links).
- `python3 scripts/verificar-estrutura.py`: serviços sem dependências de implementação entre si, REST isolado de JPA e domínio/Helpers puros.

O RED foi comunicado ao implementador antes do início do GREEN. Não houve refatoração posterior a essa execução pelo QA. Revisão e segurança permanecem a cargo dos agentes independentes correspondentes; este resultado não constitui aprovação de merge.

## Escopo e limites

Somente JUnit puro no middle office. Container Java 26 isolado, sem portas, rede Compose ou dependência de outros serviços; não iniciou/parou a stack nem Horus. Cache Maven existente reutilizado. Nenhuma dependência nova. A suíte completa MO foi executada no GREEN descrito acima. Não foram executados builds dos demais serviços, Angular ou scanners neste ciclo.

Dispensa de nova planilha: ADR-004, linha de invariante interna sem novo comportamento observável por API. Esta entrega não cria endpoint nem política financeira de dinheiro/preço/quantidade; escalas financeiras permanecem pendentes da RF03.
