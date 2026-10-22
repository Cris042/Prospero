package main

import (
	"reflect"
	"regexp"
	"testing"
)

var cfgTeste = Config{Niveis: 2, DistanciaTicks: 5, PassoTicks: 1, Ativos: []Ativo{{Ticker: "ALFA3", Referencia: 1000, Tick: 1, Quantidade: 100}}}

func ordem(lado, preco, estado string, sessao string) Ordem {
	return Ordem{Ticker: "ALFA3", Lado: lado, Tipo: "LIMIT", Validade: "DIA", PrecoLimite: preco, Estado: estado, DataSessao: sessao}
}

func TestPlanoInicialCotaTodosOsNiveisEmOrdemDeterministica(t *testing.T) {
	p := Planejar(cfgTeste, "2026-10-22", nil)
	esperado := []Envio{
		{Chave: "robo-20261022-ALFA3-C0-0", Ticker: "ALFA3", Lado: "COMPRA", Preco: "9.95", Quantidade: 100},
		{Chave: "robo-20261022-ALFA3-C1-0", Ticker: "ALFA3", Lado: "COMPRA", Preco: "9.94", Quantidade: 100},
		{Chave: "robo-20261022-ALFA3-V0-0", Ticker: "ALFA3", Lado: "VENDA", Preco: "10.05", Quantidade: 100},
		{Chave: "robo-20261022-ALFA3-V1-0", Ticker: "ALFA3", Lado: "VENDA", Preco: "10.06", Quantidade: 100},
	}
	if !reflect.DeepEqual(p.Envios, esperado) || p.Estado != EstadoAtivo {
		t.Fatalf("plano inesperado: %+v", p)
	}
	chave := regexp.MustCompile(`^[A-Za-z0-9_-]{8,64}$`)
	for _, e := range p.Envios {
		if !chave.MatchString(e.Chave) {
			t.Fatalf("chave fora do contrato do FO: %s", e.Chave)
		}
	}
	if !reflect.DeepEqual(Planejar(cfgTeste, "2026-10-22", nil), p) {
		t.Fatal("mesmo estado deve gerar o mesmo plano")
	}
}

func TestNivelVivoNaoERepostoEConsumidoRepoeComNovaGeracao(t *testing.T) {
	ordens := []Ordem{
		ordem("COMPRA", "9.95", "ABERTA", "2026-10-22"),
		ordem("COMPRA", "9.94", "PARCIALMENTE_EXECUTADA", "2026-10-22"),
		ordem("VENDA", "10.05", "EXECUTADA", "2026-10-22"),
		ordem("VENDA", "10.05", "EXECUTADA", "2026-10-22"),
		ordem("VENDA", "10.06", "RECEBIDA", "2026-10-22"),
	}
	p := Planejar(cfgTeste, "2026-10-22", ordens)
	esperado := []Envio{{Chave: "robo-20261022-ALFA3-V0-2", Ticker: "ALFA3", Lado: "VENDA", Preco: "10.05", Quantidade: 100}}
	if !reflect.DeepEqual(p.Envios, esperado) {
		t.Fatalf("somente o nível consumido volta, com geração 2: %+v", p.Envios)
	}
}

func TestCanceladaOuRejeitadaRetiraONivelSemReposicao(t *testing.T) {
	ordens := []Ordem{
		ordem("COMPRA", "9.95", "CANCELADA", "2026-10-22"),
		ordem("COMPRA", "9.94", "REJEITADA", "2026-10-22"),
		ordem("VENDA", "10.05", "ABERTA", "2026-10-22"),
		ordem("VENDA", "10.06", "ABERTA", "2026-10-22"),
	}
	p := Planejar(cfgTeste, "2026-10-22", ordens)
	if len(p.Envios) != 0 || p.Retirados != 2 || p.Estado != EstadoAtivo {
		t.Fatalf("níveis expirados/rejeitados não voltam: %+v", p)
	}
}

func TestRejeicaoPorRecursoOuLimiteEsgotaORobo(t *testing.T) {
	for _, motivo := range []string{"SALDO_INSUFICIENTE", "CUSTODIA_INSUFICIENTE", "LIMITE_ORDENS_ATIVAS"} {
		o := ordem("COMPRA", "9.95", "REJEITADA", "2026-10-22")
		o.MotivoRejeicao = motivo
		p := Planejar(cfgTeste, "2026-10-22", []Ordem{o})
		if p.Estado != EstadoEsgotado || len(p.Envios) != 0 {
			t.Fatalf("%s deve esgotar sem novos envios: %+v", motivo, p)
		}
	}
}

func TestOrcamentoDeOrdensDaSessaoLimitaEnviosEEsgota(t *testing.T) {
	ordens := make([]Ordem, 0, LimiteOrdensSessao)
	for len(ordens) < LimiteOrdensSessao-1 {
		ordens = append(ordens, ordem("COMPRA", "1.00", "CANCELADA", "2026-10-22"))
	}
	p := Planejar(cfgTeste, "2026-10-22", ordens)
	if len(p.Envios) != 1 || p.Envios[0].Chave != "robo-20261022-ALFA3-C0-0" || p.Estado != EstadoEsgotado {
		t.Fatalf("restava uma ordem no orçamento: %+v", p)
	}
}

func TestOrdensDeOutraSessaoSaoIgnoradas(t *testing.T) {
	ordens := []Ordem{ordem("COMPRA", "9.95", "ABERTA", "2026-10-21"), ordem("COMPRA", "9.95", "REJEITADA", "2026-10-21")}
	p := Planejar(cfgTeste, "2026-10-22", ordens)
	if len(p.Envios) != 4 || p.Estado != EstadoAtivo {
		t.Fatalf("sessão anterior não conta: %+v", p)
	}
}

func TestPrecosEmCentavosSemPontoFlutuante(t *testing.T) {
	for texto, centavos := range map[string]int64{"10.00": 1000, "0.05": 5, "123456.78": 12345678} {
		v, ok := centavosDe(texto)
		if !ok || v != centavos || formatar(v) != texto {
			t.Fatalf("%s -> %d %v", texto, v, ok)
		}
	}
	for _, invalido := range []string{"10", "10.0", "-1.00", "1e3", "", "10.001", "99999999999999999999.00"} {
		if _, ok := centavosDe(invalido); ok {
			t.Fatalf("aceitou %q", invalido)
		}
	}
}

func TestConfiguracaoDeAtivosValidaEDentroDosLimites(t *testing.T) {
	a, e := lerAtivos("ALFA3:10.00:0.01:100,OMEGA3:50.00:0.05:100")
	if e != nil || len(a) != 2 || a[1] != (Ativo{Ticker: "OMEGA3", Referencia: 5000, Tick: 5, Quantidade: 100}) {
		t.Fatalf("%+v %v", a, e)
	}
	for _, invalida := range []string{"", "alfa3:10.00:0.01:100", "ALFA3:10.00:0.01:0", "ALFA3:10.00:0.00:1", "ALFA3:10.00:0.01",
		"ALFA3:10.00:0.01:100,ALFA3:10.00:0.01:100", "ALFA3:10.00:0.01:100001"} {
		if _, e := lerAtivos(invalida); e == nil {
			t.Fatalf("aceitou %q", invalida)
		}
	}
	if e := (Config{Niveis: 2, DistanciaTicks: 900, PassoTicks: 1, Ativos: a}).validar(); e == nil {
		t.Fatal("preço da compra precisa ficar positivo")
	}
}
