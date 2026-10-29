package br.prospero.fo.cotacao;

import br.prospero.fo.cotacao.dominio.LadoCotado;
import br.prospero.fo.cotacao.dominio.LivroCotado;
import br.prospero.fo.cotacao.dominio.NivelCotado;
import br.prospero.fo.cotacao.dominio.SessaoCotacao;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** ADR-022: Redis habilitado mas fora do ar; ativos e livro respondem do PostgreSQL e dizem a origem. */
@QuarkusTest
@TestProfile(CotacaoRedisForaTest.RedisFora.class)
class CotacaoRedisForaTest {
    public static class RedisFora implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // Porta 1: conexão recusada imediatamente, como um Redis parado.
            return Map.of("prospero.cotacao.redis.habilitado", "true", "quarkus.redis.hosts", "redis://127.0.0.1:1");
        }
    }

    @Inject RepositorioCotacaoMemoria cotacoes;

    @AfterEach
    void limpar() { cotacoes.limpar(); }

    @Test
    void quedaDoRedisCaiParaOPostgresComOrigemEIdade() {
        var hoje = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
        var sessao = new SessaoCotacao("ALFA3", hoje, new BigDecimal("10.01"), 3, new BigDecimal("30.03"), 5,
                Instant.now().minusSeconds(2), false);
        cotacoes.preparar(new LivroCotado(sessao, new LadoCotado(List.of(new NivelCotado(new BigDecimal("9.99"), 7, 1, 7)), 1, 7),
                new LadoCotado(List.of(), 0, 0)));
        String token = Jwt.issuer("prospero-local").audience("prospero-front-office").groups("mercado:leitura")
                .expiresIn(300).signWithSecret("chave-sintetica-de-teste-com-mais-de-32-bytes");
        for (int i = 0; i < 3; i++) {
            given().auth().oauth2(token).get("/api/v1/ativos/ALFA3/livro").then().statusCode(200)
                    .header("X-Origem-Dados", "POSTGRES")
                    .header("X-Idade-Dados-Ms", notNullValue())
                    .body("revisao", equalTo(5)).body("compra.niveis[0].preco", equalTo("9.99"));
        }
        given().auth().oauth2(token).get("/api/v1/ativos").then().statusCode(200)
                .header("X-Origem-Dados", "POSTGRES").body("find { it.ticker == 'ALFA3' }.ultimoPreco", equalTo("10.01"))
                .time(lessThan(2000L));
    }
}
