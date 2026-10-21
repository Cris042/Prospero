package br.prospero.fo.ordens;

import br.prospero.fo.ordens.dominio.EstadoOrdem;
import br.prospero.fo.ordens.dominio.Ordem;
import br.prospero.fo.ordens.dominio.PapelOco;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/** RF05c.3b2 (QA): a API expõe quantidadeReduzida no par OCO e na consulta simples; 0 no TP e fora do OCO. */
@QuarkusTest
class OcoReducaoRestTest {
    private static final String CHAVE = "chave-sintetica-de-teste-com-mais-de-32-bytes";

    @Inject
    RepositorioOrdensMemoria repositorio;

    @Inject
    FonteUltimoPrecoControlada ultimos;

    @BeforeEach
    void limpar() {
        repositorio.limparTudo();
        ultimos.limpar();
    }

    private static String token() {
        return Jwt.issuer("prospero-local").audience("prospero-front-office").subject("CONTA-002")
                .groups(Set.of("ordens:enviar", "ordens:leitura")).expiresIn(300).signWithSecret(CHAVE);
    }

    @SuppressWarnings("unchecked")
    private void reduzirStop(UUID grupo, long reduzida, long executada) throws Exception {
        var campo = RepositorioOrdensMemoria.class.getDeclaredField("ordens");
        campo.setAccessible(true);
        // O bean injetado é um client proxy; o estado vive na instância real.
        var real = io.quarkus.arc.ClientProxy.unwrap(repositorio);
        var ordens = (List<Ordem>) campo.get(real);
        synchronized (real) {
            for (int i = 0; i < ordens.size(); i++) {
                Ordem o = ordens.get(i);
                if (grupo.equals(o.grupoOco()) && o.papelOco() == PapelOco.STOP_LOSS) {
                    ordens.set(i, new Ordem(o.id(), o.conta(), o.ticker(), o.lado(), o.tipo(), o.validade(), o.quantidade(),
                            o.precoLimite(), o.precoGatilho(), o.grupoOco(), o.papelOco(), o.grupoEnvio(),
                            EstadoOrdem.EXECUTADA, o.dataSessao(), o.recebidaEm(), o.baseTunel(), null, executada, 0,
                            "EXECUTADA", reduzida));
                }
            }
        }
    }

    @Test
    void parExpoeReducaoSomenteNoStopLoss() throws Exception {
        Map<String, Object> oco = Map.of("ticker", "ALFA3", "lado", "VENDA", "quantidade", 3, "validade", "DIA",
                "takeProfit", Map.of("precoLimite", "10.20"), "stopLoss", Map.of("tipo", "STOP", "precoGatilho", "9.90"));
        String grupo = given().auth().oauth2(token()).header("X-Idempotency-Key", "oco-reducao-1")
                .contentType(ContentType.JSON).body(oco).when().post("/api/v1/ordens-oco").then().statusCode(202)
                .body("ordens[0].quantidadeReduzida", equalTo(0)).body("ordens[1].quantidadeReduzida", equalTo(0))
                .extract().path("grupoOco");
        reduzirStop(UUID.fromString(grupo), 1, 2);
        // O dublê em memória ordena por ordinal invertido (SL primeiro) e o JPA por texto desc (TP primeiro): busca por papel.
        String tp = "ordens.find { it.papelOco == 'TAKE_PROFIT' }.", stop = "ordens.find { it.papelOco == 'STOP_LOSS' }.";
        var resposta = given().auth().oauth2(token()).when().get("/api/v1/ordens-oco/" + grupo).then().statusCode(200)
                .body(tp + "quantidadeReduzida", equalTo(0)).body(stop + "quantidadeReduzida", equalTo(1))
                .body(stop + "quantidadeExecutada", equalTo(2)).body(stop + "quantidade", equalTo(3))
                .extract();
        String sl = resposta.path(stop + "id");
        given().auth().oauth2(token()).when().get("/api/v1/ordens/" + sl).then().statusCode(200)
                .body("quantidadeReduzida", equalTo(1)).body("motivoEncerramento", equalTo("EXECUTADA"));
        given().when().get("/q/openapi").then().statusCode(200).body(containsString("quantidadeReduzida"));
    }
}
