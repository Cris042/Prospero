package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

const tokenTeste = "token-de-teste-com-mais-de-vinte"

// foFalso guarda ordens como o FO: POST idempotente por chave, listagem mais recentes primeiro, teto por sessão.
type foFalso struct {
	mu      sync.Mutex
	ordens  []Ordem
	chaves  map[string]bool
	posts   int
	teto    int
	status  int
	cabecas []http.Header
}

func (f *foFalso) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.cabecas = append(f.cabecas, r.Header.Clone())
	if r.Header.Get("Authorization") != "Bearer "+tokenTeste {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	if f.status != 0 {
		w.WriteHeader(f.status)
		return
	}
	switch r.Method {
	case http.MethodGet:
		lista := make([]Ordem, 0, len(f.ordens))
		for i := len(f.ordens) - 1; i >= 0; i-- {
			lista = append(lista, f.ordens[i])
		}
		inicio := 0
		if c := r.URL.Query().Get("cursor"); c != "" {
			inicio = len(c)
		}
		fim := min(inicio+100, len(lista))
		var cursor *string
		if fim < len(lista) {
			c := strings.Repeat("x", fim)
			cursor = &c
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"ordens": lista[inicio:fim], "proximoCursor": cursor})
	case http.MethodPost:
		f.posts++
		var corpo map[string]any
		dados, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(dados, &corpo)
		chave := r.Header.Get("X-Idempotency-Key")
		if !f.chaves[chave] {
			if f.teto > 0 && len(f.ordens) >= f.teto {
				w.WriteHeader(http.StatusTooManyRequests)
				return
			}
			f.chaves[chave] = true
			f.ordens = append(f.ordens, Ordem{Ticker: corpo["ticker"].(string), Lado: corpo["lado"].(string), Tipo: "LIMIT",
				Validade: "DIA", PrecoLimite: corpo["precoLimite"].(string), Estado: "ABERTA", DataSessao: "2026-10-22"})
		}
		w.WriteHeader(http.StatusAccepted)
	}
}

func roboContra(t *testing.T, f *foFalso) *Robo {
	t.Helper()
	if f.chaves == nil {
		f.chaves = map[string]bool{}
	}
	s := httptest.NewServer(f)
	t.Cleanup(s.Close)
	c, e := NovoCliente(s.URL, tokenTeste)
	if e != nil {
		t.Fatal(e)
	}
	// 2026-10-22 12:00 em São Paulo.
	agora := func() time.Time { return time.Date(2026, 10, 22, 15, 0, 0, 0, time.UTC) }
	return &Robo{cfg: cfgTeste, cliente: c, agora: agora, sit: Situacao{Estado: EstadoIniciando, Conta: "CONTA-004"}}
}

func TestCicloCotaUmaVezEReinicioNaoDuplica(t *testing.T) {
	f := &foFalso{}
	r := roboContra(t, f)
	r.Ciclo(context.Background())
	r.Ciclo(context.Background())
	if len(f.ordens) != 4 || f.posts != 4 || r.Situacao().Estado != EstadoAtivo || r.Situacao().Enviadas != 4 {
		t.Fatalf("segundo ciclo não reenvia níveis vivos: ordens=%d posts=%d %+v", len(f.ordens), f.posts, r.Situacao())
	}
	// Execução de um nível: o próximo ciclo repõe só ele; um robô novo (reinício) chega ao mesmo estado sem duplicar.
	f.ordens[2].Estado = "EXECUTADA"
	novo := roboContra(t, f)
	novo.cliente = r.cliente
	novo.Ciclo(context.Background())
	r.Ciclo(context.Background())
	if len(f.ordens) != 5 || f.posts != 5 {
		t.Fatalf("reposição única; o outro robô vê o nível vivo: ordens=%d posts=%d", len(f.ordens), f.posts)
	}
	// Reenvio após falha entre o POST e a leitura usa a mesma chave: o FO devolve a ordem existente.
	plano := Planejar(cfgTeste, "2026-10-22", append([]Ordem(nil), f.ordens[:4]...))
	if len(plano.Envios) != 1 || plano.Envios[0].Chave != "robo-20261022-ALFA3-V0-1" {
		t.Fatalf("%+v", plano.Envios)
	}
	if _, e := r.cliente.Enviar(context.Background(), plano.Envios[0]); e != nil || len(f.ordens) != 5 {
		t.Fatalf("chave repetida não cria ordem nova: %d", len(f.ordens))
	}
	for _, h := range f.cabecas {
		if h.Get("Authorization") != "Bearer "+tokenTeste {
			t.Fatal("toda chamada leva o token próprio")
		}
	}
}

func TestLimiteDaSessaoNoFoEsgotaEParaDeEnviar(t *testing.T) {
	f := &foFalso{teto: 2}
	r := roboContra(t, f)
	r.Ciclo(context.Background())
	if r.Situacao().Estado != EstadoEsgotado || f.posts != 3 {
		t.Fatalf("429 esgota e interrompe o ciclo: %+v posts=%d", r.Situacao(), f.posts)
	}
	r.Ciclo(context.Background())
	if f.posts != 3 {
		t.Fatal("ESGOTADO é terminal: nenhum envio novo")
	}
}

func TestFoIndisponivelOuCredencialRecusadaFicaObservavel(t *testing.T) {
	f := &foFalso{status: http.StatusServiceUnavailable}
	r := roboContra(t, f)
	r.Ciclo(context.Background())
	if s := r.Situacao(); s.Estado != EstadoIndisponivel || s.Enviadas != 0 {
		t.Fatalf("%+v", s)
	}
	f.status = 0
	r.Ciclo(context.Background())
	if r.Situacao().Estado != EstadoAtivo {
		t.Fatal("recupera quando o FO volta")
	}
	c, _ := NovoCliente(strings.TrimSuffix(r.cliente.(*Cliente).base, "/"), "outro-token-com-mais-de-vinte-chars")
	if _, e := c.Enviar(context.Background(), Envio{Chave: "robo-x-0000", Ticker: "ALFA3", Lado: "COMPRA", Preco: "9.95", Quantidade: 1}); e == nil {
		t.Fatal("401 não é tratado como recusa silenciosa")
	}
}

func TestClienteNaoSegueRedirecionamentoNemAceitaOrigemInvalida(t *testing.T) {
	alvo := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Error("redirecionamento não pode ser seguido")
	}))
	defer alvo.Close()
	desvio := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, alvo.URL+"/api/v1/ordens", http.StatusTemporaryRedirect)
	}))
	defer desvio.Close()
	c, _ := NovoCliente(desvio.URL, tokenTeste)
	if _, e := c.OrdensDaSessao(context.Background(), "2026-10-22"); e == nil {
		t.Fatal("redirecionamento deve ser tratado como indisponível")
	}
	for _, origem := range []string{"file:///etc/passwd", "ftp://h", "http://", "http://h/caminho", "http://u:s@h"} {
		if _, e := NovoCliente(origem, tokenTeste); e == nil {
			t.Fatalf("aceitou %q", origem)
		}
	}
	if _, e := NovoCliente("http://h:1", "curto"); e == nil {
		t.Fatal("token curto recusado")
	}
}

func TestEstadoExpostoSemToken(t *testing.T) {
	r := roboContra(t, &foFalso{})
	r.Ciclo(context.Background())
	w := httptest.NewRecorder()
	r.Handler().ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/estado", nil))
	if w.Code != 200 || strings.Contains(w.Body.String(), tokenTeste) || !strings.Contains(w.Body.String(), `"estado":"ATIVO"`) {
		t.Fatalf("%d %s", w.Code, w.Body.String())
	}
	w = httptest.NewRecorder()
	r.Handler().ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/estado", nil))
	if w.Code != http.StatusMethodNotAllowed {
		t.Fatalf("somente GET: %d", w.Code)
	}
}
