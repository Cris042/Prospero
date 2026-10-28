package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// P06B2a: cenário de escrita ORDEM_AGRESSOR. O corpo é do catálogo; o plano não o controla.

const corpoEsperado = `{"ticker":"ALFA3","lado":"COMPRA","tipo":"LIMIT","validade":"IOC","quantidade":1,"precoLimite":"10.00"}`

var chaveValida = regexp.MustCompile(`^[A-Za-z0-9_-]{8,64}$`)

func escritaClaims() map[string]any {
	return qaFinanceClaims("prospero-front-office", "ordens:enviar")
}

func escritaPlan(maxChamadas int) map[string]any {
	p := qaPlan(1000)
	p["estagios"] = []any{map[string]any{"duracaoMs": 1000, "rps": 5, "vus": 0}}
	p["cenario"] = "ORDEM_AGRESSOR"
	p["servico"] = "front-office"
	p["concorrencia"] = 1
	p["maxChamadas"] = maxChamadas
	return p
}

func escritaServer(t *testing.T, token string, h http.Handler) *Server {
	t.Helper()
	return qaFinanceServer(t, map[string]string{"ORDEM_AGRESSOR": token}, h)
}

func TestEscritaRequisicaoExataChavesDistintasESigilo(t *testing.T) {
	token := qaJWT(t, []byte("chave-externa-escrita"), "HS256", escritaClaims())
	var mu sync.Mutex
	chaves := map[string]bool{}
	var hits atomic.Int64
	s := escritaServer(t, token, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		corpo, _ := io.ReadAll(r.Body)
		if r.Method != "POST" || r.URL.RequestURI() != "/api/v1/ordens" {
			t.Errorf("rota incorreta: %s %s", r.Method, r.URL.RequestURI())
		}
		if string(corpo) != corpoEsperado {
			t.Errorf("corpo divergente do catálogo: %s", corpo)
		}
		if r.Header.Get("Content-Type") != "application/json" || r.Header.Get("Authorization") != "Bearer "+token {
			t.Error("cabeçalhos de conteúdo/autorização incorretos")
		}
		chave := r.Header.Get("X-Idempotency-Key")
		if !chaveValida.MatchString(chave) {
			t.Errorf("chave de idempotência inválida: %q", chave)
		}
		mu.Lock()
		if chaves[chave] {
			t.Errorf("chave repetida entre ofertas: %q", chave)
		}
		chaves[chave] = true
		mu.Unlock()
		w.WriteHeader(201)
		w.Write([]byte(`{"id":"00000000-0000-0000-0000-000000000001"}`))
	}))
	p := escritaPlan(5)
	report := qaTerminal(t, s, qaID(t, qaStart(t, s, p, "escrita-exata")))
	c := qaCounts(t, report)
	if c["iniciadas"] < 2 || hits.Load() != c["iniciadas"] || c["errosHTTP"] != 0 || c["respostasHTTP"] != c["iniciadas"] {
		t.Fatalf("uma oferta deve gerar uma chamada aceita: %v hits=%d", c, hits.Load())
	}
	metrics := httptest.NewRecorder()
	s.MetricsHandler().ServeHTTP(metrics, httptest.NewRequest("GET", "http://example.com/metrics", nil))
	b, _ := json.Marshal(report)
	saida := string(b) + metrics.Body.String()
	segredos := []string{token, "CONTA-002", "sentinela-financeira-qa", "chave-externa-escrita", "ALFA3", "precoLimite"}
	for chave := range chaves {
		segredos = append(segredos, chave)
	}
	for _, segredo := range segredos {
		if strings.Contains(saida, segredo) {
			t.Fatalf("segredo/corpo/chave vazou no relatório ou métrica: %q", segredo)
		}
	}
	if !strings.Contains(metrics.Body.String(), `cenario="ORDEM_AGRESSOR"`) {
		t.Fatal("métrica não distingue o cenário de escrita")
	}
}

func TestEscritaChavesNaoSeRepetemEntreExecucoes(t *testing.T) {
	token := qaJWT(t, []byte("chave-externa-escrita"), "HS256", escritaClaims())
	var mu sync.Mutex
	vistas := map[string]int{}
	s := escritaServer(t, token, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		vistas[r.Header.Get("X-Idempotency-Key")]++
		mu.Unlock()
		w.WriteHeader(201)
		w.Write([]byte(`{}`))
	}))
	for i, nonce := range []string{"exec-a", "exec-b"} {
		p := escritaPlan(3)
		_ = i
		qaCounts(t, qaTerminal(t, s, qaID(t, qaStart(t, s, p, nonce))))
	}
	for chave, n := range vistas {
		if n != 1 {
			t.Fatalf("chave %q usada %d vezes entre execuções", chave, n)
		}
	}
	if len(vistas) < 2 {
		t.Fatal("esperava ofertas nas duas execuções")
	}
}

func TestEscritaPlanoForaDoLimiteRecusadoSemTrafego(t *testing.T) {
	token := qaJWT(t, []byte("chave-externa-escrita"), "HS256", escritaClaims())
	mudancas := map[string]func(map[string]any){
		"max-chamadas": func(p map[string]any) { p["maxChamadas"] = 21 },
		"concorrencia": func(p map[string]any) { p["concorrencia"] = 2 },
		"fechado": func(p map[string]any) {
			p["modo"] = "FECHADO"
			p["estagios"] = []any{map[string]any{"duracaoMs": 400, "rps": 5, "vus": 1}}
		},
		"rps":     func(p map[string]any) { p["estagios"] = []any{map[string]any{"duracaoMs": 400, "rps": 6, "vus": 0}} },
		"servico": func(p map[string]any) { p["servico"] = "middle-office" },
	}
	for nome, muda := range mudancas {
		t.Run(nome, func(t *testing.T) {
			var hits atomic.Int64
			s := escritaServer(t, token, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { hits.Add(1) }))
			p := escritaPlan(5)
			muda(p)
			if w := qaStart(t, s, p, "limite-"+nome); w.Code != 422 {
				t.Fatalf("plano fora do limite aceito: %d", w.Code)
			}
			if hits.Load() != 0 {
				t.Fatal("plano recusado gerou tráfego")
			}
		})
	}
	for _, campo := range []string{"corpo", "headers", "conta", "url", "chave", "idempotencia"} {
		t.Run("livre-"+campo, func(t *testing.T) {
			s := escritaServer(t, token, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { t.Error("campo livre gerou tráfego") }))
			p := escritaPlan(5)
			p[campo] = "sentinela-nao-refletir"
			if w := qaStart(t, s, p, "livre-"+campo); w.Code != 400 {
				t.Fatalf("campo livre aceito: %d", w.Code)
			}
		})
	}
}

func TestEscritaCredencialInadequadaSemTrafego(t *testing.T) {
	casos := map[string]func(map[string]any){
		"sem-enviar":    func(c map[string]any) { c["groups"] = []string{"ordens:leitura"} },
		"grupos-extras": func(c map[string]any) { c["groups"] = []string{"ordens:enviar", "ordens:leitura"} },
		"conta-errada":  func(c map[string]any) { c["sub"] = "CONTA-003" },
		"sem-conta":     func(c map[string]any) { delete(c, "sub") },
		"curto":         func(c map[string]any) { c["exp"] = time.Now().Unix() + 3 },
		"audiencia":     func(c map[string]any) { c["aud"] = "prospero-middle-office" },
	}
	for nome, muda := range casos {
		t.Run(nome, func(t *testing.T) {
			claims := escritaClaims()
			muda(claims)
			token := qaJWT(t, []byte("chave-externa-escrita"), "HS256", claims)
			var hits atomic.Int64
			s := escritaServer(t, token, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { hits.Add(1) }))
			w := qaStart(t, s, escritaPlan(5), "cred-"+nome)
			if w.Code != 422 || !strings.Contains(w.Body.String(), "CREDENCIAL_FINANCEIRA_INDISPONIVEL") {
				t.Fatalf("credencial inadequada aceita: %d", w.Code)
			}
			if strings.Contains(w.Body.String(), token) || strings.Contains(w.Body.String(), "CONTA-") {
				t.Fatal("erro refletiu credencial ou claim")
			}
			if hits.Load() != 0 {
				t.Fatal("credencial inadequada gerou tráfego")
			}
		})
	}
	t.Run("ausente", func(t *testing.T) {
		s := qaFinanceServer(t, nil, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { t.Error("sem token gerou tráfego") }))
		if w := qaStart(t, s, escritaPlan(5), "cred-ausente"); w.Code != 422 {
			t.Fatalf("sem credencial aceito: %d", w.Code)
		}
	})
}

func TestEscritaConservaContagensComAceitaRejeitadaETimeout(t *testing.T) {
	token := qaJWT(t, []byte("chave-externa-escrita"), "HS256", escritaClaims())
	var n, aceitas, rejeitadas atomic.Int64
	s := escritaServer(t, token, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.Copy(io.Discard, r.Body) // sem ler o corpo o servidor não percebe o fechamento da conexão
		i := n.Add(1) - 1
		switch {
		case i == 3:
			<-r.Context().Done()
		case i%3 == 1:
			rejeitadas.Add(1)
			w.WriteHeader(409)
		case i%3 == 2:
			rejeitadas.Add(1)
			w.WriteHeader(422)
		default:
			aceitas.Add(1)
			w.WriteHeader(201)
			w.Write([]byte(`{}`))
		}
	}))
	p := escritaPlan(20)
	p["timeoutMs"] = 100
	p["estagios"] = []any{map[string]any{"duracaoMs": 2000, "rps": 5, "vus": 0}}
	c := qaCounts(t, qaTerminal(t, s, qaID(t, qaStart(t, s, p, "conserva"))))
	if c["iniciadas"] < 5 || c["timeouts"] != 1 {
		t.Fatalf("esperava timeout isolado em ao menos cinco ofertas: %v", c)
	}
	if c["errosHTTP"] != rejeitadas.Load() || c["respostasHTTP"]-c["errosHTTP"] != aceitas.Load() {
		t.Fatalf("aceitas+rejeitadas+timeouts divergem do alvo: %v aceitas=%d rejeitadas=%d", c, aceitas.Load(), rejeitadas.Load())
	}
}

func TestEscritaRespostaMaiorQueOLimiteEInterrompida(t *testing.T) {
	token := qaJWT(t, []byte("chave-externa-escrita"), "HS256", escritaClaims())
	s := escritaServer(t, token, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(201)
		w.Write([]byte(strings.Repeat("x", 4096)))
	}))
	c := qaCounts(t, qaTerminal(t, s, qaID(t, qaStart(t, s, escritaPlan(2), "limite-resp"))))
	if c["limitesResposta"] < 1 {
		t.Fatalf("limite de resposta não aplicado à escrita: %v", c)
	}
}

func TestEscritaCancelamentoNoMeioNaoGeraTrafegoPosterior(t *testing.T) {
	token := qaJWT(t, []byte("chave-externa-escrita"), "HS256", escritaClaims())
	iniciou := make(chan struct{}, 1)
	var hits atomic.Int64
	s := escritaServer(t, token, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.Copy(io.Discard, r.Body)
		hits.Add(1)
		select {
		case iniciou <- struct{}{}:
		default:
		}
		<-r.Context().Done()
	}))
	p := escritaPlan(20)
	p["estagios"] = []any{map[string]any{"duracaoMs": 5000, "rps": 5, "vus": 0}}
	p["timeoutMs"] = 2000
	id := qaID(t, qaStart(t, s, p, "cancela-escrita"))
	select {
	case <-iniciou:
	case <-time.After(time.Second):
		t.Fatal("alvo não recebeu a primeira escrita")
	}
	qaRequest(t, s, "POST", "/api/v1/execucoes-carga/"+id+"/cancelamentos", qaJWT(t, qaControlKey, "HS256", qaClaims()), nil)
	report := qaTerminal(t, s, id)
	c := qaCounts(t, report)
	if report["estado"] != "CANCELADA" || c["emVoo"] != 0 || c["canceladasEmVoo"] < 1 {
		t.Fatalf("cancelamento não drenou: %v %v", report["estado"], c)
	}
	apos := hits.Load()
	time.Sleep(300 * time.Millisecond)
	if hits.Load() != apos {
		t.Fatal("houve requisição depois do estado terminal")
	}
}
