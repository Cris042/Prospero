package br.prospero.mo.sistema;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class SistemaResourceTest {
    @Test
    void deveInformarIdentidadeSemAnunciarNegociacaoImplementada() {
        given().when().get("/api/v1/sistema").then()
                .statusCode(200)
                .header("Cache-Control", "no-store")
                .body("servico", equalTo("middle-office"))
                .body("etapa", equalTo("FUNDAÇÃO"))
                .body("versao", equalTo("0.1.0-SNAPSHOT"));
    }

    @Test
    void deveExporContratoOpenApiNoRuntime() {
        given().when().get("/q/openapi?format=json").then()
                .statusCode(200)
                .body("paths.'/api/v1/sistema'.get.responses.'200'.description",
                        equalTo("Identidade e etapa de implementação do serviço."));
    }

    @Test
    void deveManterSwaggerDisponivelForaDoDevMode() {
        given().when().get("/q/swagger-ui/").then()
                .statusCode(200).body(containsString("swagger-ui-bundle.js"));
    }

    @Test
    void deveResponderLivenessSemDependerDeOutroMicrosservico() {
        given().when().get("/q/health/live").then()
                .statusCode(200).body("status", equalTo("UP"));
    }
}
