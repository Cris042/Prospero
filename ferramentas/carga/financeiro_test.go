package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestQAFinanceiroIdentidadeExplicita(t *testing.T) {
	s, hits := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte(`{}`)) }))
	p := qaPlan(100)
	p["cenario"] = "IDENTIDADE"
	qaCounts(t, qaTerminal(t, s, qaID(t, qaStart(t, s, p, "identidade-explicita"))))
	if hits.Load() == 0 {
		t.Fatal("identidade não fez chamada")
	}
}

func TestQAFinanceiroHashLegado(t *testing.T) {
	p := qaPlan(100)
	b, _ := json.Marshal(p)
	var plan Plan
	if err := json.Unmarshal(b, &plan); err != nil {
		t.Fatal(err)
	}
	want := sha256.Sum256(b)
	if got := PlanHash(plan); got != hex.EncodeToString(want[:]) {
		t.Fatal("hash do plano legado mudou")
	}
	marshaled, _ := json.Marshal(plan)
	if strings.Contains(string(marshaled), `"cenario"`) {
		t.Fatal("cenário ausente serializado")
	}
}

var qaCatalog = []struct{ name, service, path, aud, group string }{
	{"ATIVOS", "front-office", "/api/v1/ativos", "prospero-front-office", "mercado:leitura"},
	{"ORDENS", "front-office", "/api/v1/ordens?limite=20", "prospero-front-office", "ordens:leitura"},
	{"CARTEIRA", "middle-office", "/api/v1/risco/carteira", "prospero-middle-office", "risco:leitura"},
	{"POSICOES", "back-office", "/api/v1/posicoes", "prospero-back-office", "posicoes:leitura"},
	{"NEGOCIOS", "back-office", "/api/v1/negocios?limite=20", "prospero-back-office", "razao:leitura"},
	{"RAZAO", "back-office", "/api/v1/razao?limite=20", "prospero-back-office", "razao:leitura"},
}

func qaFinanceClaims(aud, group string) map[string]any {
	now := time.Now().Unix()
	return map[string]any{"iss": "prospero-local", "aud": aud, "groups": []string{group}, "sub": "CONTA-002", "iat": now, "nbf": now - 1, "exp": now + 120, "jti": "sentinela-financeira-qa"}
}
func qaFinanceServer(t *testing.T, tokens map[string]string, target http.Handler) *Server {
	t.Helper()
	upstream := httptest.NewServer(target)
	t.Cleanup(upstream.Close)
	s, err := NewServer(Config{TokenKey: qaControlKey, PreflightKey: qaPreflightKey, Hosts: []string{"example.com"}, Targets: map[string]string{"front-office": upstream.URL, "middle-office": upstream.URL, "back-office": upstream.URL, "matching-engine": upstream.URL}, FinancialTokens: tokens, Cooldown: -1})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.Close)
	return s
}
func qaFinancialPlan(name, service string) map[string]any {
	p := qaPlan(100)
	p["cenario"] = name
	p["servico"] = service
	return p
}
func TestQAFinanceiroCatalogoBearerESigilo(t *testing.T) {
	for _, tc := range qaCatalog {
		t.Run(tc.name, func(t *testing.T) {
			token := qaJWT(t, []byte("chave-externa-financeira-sentinela"), "HS256", qaFinanceClaims(tc.aud, tc.group))
			var hits atomic.Int64
			s := qaFinanceServer(t, map[string]string{tc.name: token}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				hits.Add(1)
				if r.Method != "GET" || r.URL.RequestURI() != tc.path {
					t.Errorf("rota incorreta: %s %s", r.Method, r.URL.RequestURI())
				}
				if r.Header.Get("Authorization") != "Bearer "+token {
					t.Error("bearer financeiro incorreto ou ausente")
				}
				w.Write([]byte(`{}`))
			}))
			report := qaTerminal(t, s, qaID(t, qaStart(t, s, qaFinancialPlan(tc.name, tc.service), "catalogo")))
			counts := qaCounts(t, report)
			if counts["respostasHTTP"] < 1 || hits.Load() != counts["iniciadas"] {
				t.Fatal("uma oferta deve gerar uma chamada HTTP")
			}
			metrics := httptest.NewRecorder()
			s.MetricsHandler().ServeHTTP(metrics, httptest.NewRequest("GET", "http://example.com/metrics", nil))
			b, _ := json.Marshal(report)
			for _, secret := range []string{token, "CONTA-002", "sentinela-financeira-qa", "chave-externa-financeira-sentinela"} {
				if strings.Contains(string(b)+metrics.Body.String(), secret) {
					t.Fatal("segredo/claim vazou no relatório ou métrica")
				}
			}
			if !strings.Contains(metrics.Body.String(), `cenario="`+tc.name+`"`) {
				t.Fatal("métrica não distingue cenário")
			}
		})
	}
}
func TestQAFinanceiroCredencialRecusadaSemTrafego(t *testing.T) {
	cases := []struct {
		name    string
		change  func(map[string]any)
		alg     string
		missing bool
	}{
		{name: "ausente", missing: true},
		{name: "iat-ausente", change: func(c map[string]any) { delete(c, "iat") }},
		{name: "iat-futuro", change: func(c map[string]any) { c["iat"] = time.Now().Unix() + 30 }},
		{name: "duracao-excessiva", change: func(c map[string]any) { c["exp"] = time.Now().Unix() + 3601 }},
		{name: "audiencia", change: func(c map[string]any) { c["aud"] = "prospero-carga" }},
		{name: "emissor", change: func(c map[string]any) { c["iss"] = "outro" }},
		{name: "scope-escrita", change: func(c map[string]any) { c["groups"] = []string{"ordens:leitura", "ordens:enviar"} }},
		{name: "sub-desconhecido", change: func(c map[string]any) { c["sub"] = "CONTA-999" }},
		{name: "sub-ausente", change: func(c map[string]any) { delete(c, "sub") }},
		{name: "expirado", change: func(c map[string]any) { c["exp"] = time.Now().Unix() - 1 }},
		{name: "curto", change: func(c map[string]any) { c["exp"] = time.Now().Unix() + 3 }},
		{name: "nbf-futuro", change: func(c map[string]any) { c["nbf"] = time.Now().Unix() + 60 }},
		{name: "algoritmo", alg: "none"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			claims := qaFinanceClaims("prospero-front-office", "ordens:leitura")
			if tc.change != nil {
				tc.change(claims)
			}
			alg := tc.alg
			if alg == "" {
				alg = "HS256"
			}
			token := qaJWT(t, []byte("chave-financeira-externa"), alg, claims)
			tokens := map[string]string{}
			if !tc.missing {
				tokens["ORDENS"] = token
			}
			var hits atomic.Int64
			s := qaFinanceServer(t, tokens, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { hits.Add(1) }))
			p := qaFinancialPlan("ORDENS", "front-office")
			response := qaStart(t, s, p, "credencial")
			if response.Code != 422 || !strings.Contains(response.Body.String(), "CREDENCIAL_FINANCEIRA_INDISPONIVEL") {
				t.Fatalf("credencial inválida: status=%d", response.Code)
			}
			if hits.Load() != 0 {
				t.Fatal("credencial inválida gerou tráfego")
			}
			if strings.Contains(response.Body.String(), token) || strings.Contains(response.Body.String(), "CONTA-") {
				t.Fatal("erro expôs credencial")
			}
			s.mu.Lock()
			active := s.active
			s.mu.Unlock()
			if active != nil {
				t.Fatal("credencial inválida criou execução")
			}
		})
	}
}
func TestQAFinanceiroCombinacoesInvalidas(t *testing.T) {
	for _, name := range []string{"ativos", "https://example.com", "DESCONHECIDO", "ATIVOS"} {
		t.Run(name, func(t *testing.T) {
			var hits atomic.Int64
			s := qaFinanceServer(t, nil, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { hits.Add(1) }))
			w := qaStart(t, s, qaFinancialPlan(name, "matching-engine"), "combo")
			if w.Code != 422 {
				t.Fatalf("plano inválido status=%d", w.Code)
			}
			if hits.Load() != 0 {
				t.Fatal("combinação inválida gerou tráfego")
			}
		})
	}
}

func TestQAFinanceiroMetricasSeparadas(t *testing.T) {
	tokens := map[string]string{}
	for _, tc := range qaCatalog {
		tokens[tc.name] = qaJWT(t, []byte("externa"), "HS256", qaFinanceClaims(tc.aud, tc.group))
	}
	s := qaFinanceServer(t, tokens, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/sistema" && r.Header.Get("Authorization") != "" {
			t.Error("IDENTIDADE enviou bearer")
		}
		w.Write([]byte(`{}`))
	}))
	for i, name := range []string{"IDENTIDADE", "ATIVOS", "ORDENS"} {
		p := qaFinancialPlan(name, "front-office")
		p["maxChamadas"] = 1
		c := qaCounts(t, qaTerminal(t, s, qaID(t, qaStart(t, s, p, fmt.Sprintf("metricas-%d", i)))))
		if c["iniciadas"] != 1 {
			t.Fatal("teto de chamada mudou")
		}
	}
	metrics := httptest.NewRecorder()
	s.MetricsHandler().ServeHTTP(metrics, httptest.NewRequest("GET", "http://example.com/metrics", nil))
	for _, name := range []string{"IDENTIDADE", "ATIVOS", "ORDENS"} {
		var found bool
		for _, line := range strings.Split(metrics.Body.String(), "\n") {
			if strings.HasPrefix(line, "carga_iniciadas_total{") && strings.Contains(line, `cenario="`+name+`"`) {
				found = true
				if !strings.HasSuffix(line, " 1") {
					t.Errorf("contador misturado: %s", line)
				}
			}
		}
		if !found {
			t.Errorf("contador ausente cenário %s", name)
		}
	}
}
func TestQAFinanceiroLimiteRedirectECancelamento(t *testing.T) {
	for _, kind := range []string{"limite", "redirect", "cancelamento"} {
		t.Run(kind, func(t *testing.T) {
			token := qaJWT(t, []byte("externa"), "HS256", qaFinanceClaims("prospero-front-office", "mercado:leitura"))
			started := make(chan struct{}, 1)
			var forbidden atomic.Int64
			dest := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { forbidden.Add(1) }))
			defer dest.Close()
			s := qaFinanceServer(t, map[string]string{"ATIVOS": token}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				select {
				case started <- struct{}{}:
				default:
				}
				switch kind {
				case "limite":
					w.Write([]byte(strings.Repeat("x", 2048)))
				case "redirect":
					http.Redirect(w, r, dest.URL, 302)
				case "cancelamento":
					<-r.Context().Done()
				}
			}))
			p := qaFinancialPlan("ATIVOS", "front-office")
			p["maxChamadas"] = 1
			id := qaID(t, qaStart(t, s, p, "protecao"))
			if kind == "cancelamento" {
				select {
				case <-started:
				case <-time.After(time.Second):
					t.Fatal("alvo não iniciou")
				}
				qaRequest(t, s, "POST", "/api/v1/execucoes-carga/"+id+"/cancelamentos", qaJWT(t, qaControlKey, "HS256", qaClaims()), nil)
			}
			report := qaTerminal(t, s, id)
			c := qaCounts(t, report)
			if kind == "limite" && c["limitesResposta"] != 1 {
				t.Fatal("limite de corpo não preservado")
			}
			if kind == "cancelamento" && (c["canceladasEmVoo"] != 1 || report["estado"] != "CANCELADA") {
				t.Fatal("cancelamento não drenou")
			}
			if forbidden.Load() != 0 {
				t.Fatal("redirect foi seguido")
			}
		})
	}
}

func TestQAFinanceiroCamposLivresRecusados(t *testing.T) {
	for _, field := range []string{"url", "rota", "headers", "conta", "cursor", "corpo"} {
		t.Run(field, func(t *testing.T) {
			s := qaFinanceServer(t, nil, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { t.Error("campo livre gerou tráfego") }))
			p := qaFinancialPlan("ATIVOS", "front-office")
			p[field] = "sentinela-nao-refletir"
			response := qaStart(t, s, p, "campos")
			if response.Code != 400 {
				t.Errorf("campo livre status=%d", response.Code)
			}
		})
	}
}
func TestQAFinanceiroATIVOSSemContaEAutoridadeDoAlvo(t *testing.T) {
	claims := qaFinanceClaims("prospero-front-office", "mercado:leitura")
	delete(claims, "sub")
	token := qaJWT(t, []byte("chave-nunca-configurada-no-Go"), "HS256", claims)
	s := qaFinanceServer(t, map[string]string{"ATIVOS": token}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(401) }))
	p := qaFinancialPlan("ATIVOS", "front-office")
	p["maxChamadas"] = 1
	report := qaTerminal(t, s, qaID(t, qaStart(t, s, p, "autoridade")))
	c := qaCounts(t, report)
	if c["respostasHTTP"] != 1 || c["errosHTTP"] != 1 {
		t.Fatal("alvo deve decidir assinatura/autorização, sem retry oculto")
	}
}
func TestQAFinanceiroTokensMalformados(t *testing.T) {
	for _, token := range []string{"nao-jwt", "a.b.c", strings.Repeat("s", 100000)} {
		s := qaFinanceServer(t, map[string]string{"ORDENS": token}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { t.Error("token inválido gerou tráfego") }))
		response := qaStart(t, s, qaFinancialPlan("ORDENS", "front-office"), "malformado")
		if response.Code != 422 {
			t.Fatalf("token malformado: %d", response.Code)
		}
		if strings.Contains(response.Body.String(), token) {
			t.Fatal("token refletido")
		}
	}
}
func TestQAFinanceiroHashVinculaCenario(t *testing.T) {
	p := qaFinancialPlan("ATIVOS", "front-office")
	b, _ := json.Marshal(p)
	var a Plan
	json.Unmarshal(b, &a)
	p["cenario"] = "ORDENS"
	b, _ = json.Marshal(p)
	var o Plan
	json.Unmarshal(b, &o)
	if PlanHash(a) == PlanHash(o) {
		t.Fatal("preflight não vincula cenário")
	}
}

func TestQAFinanceiroCenarioVazioPreservaHashEAdmissao(t *testing.T) {
	p := qaPlan(100)
	b, _ := json.Marshal(p)
	var omitted Plan
	json.Unmarshal(b, &omitted)
	p["cenario"] = ""
	b, _ = json.Marshal(p)
	var empty Plan
	json.Unmarshal(b, &empty)
	if PlanHash(empty) != PlanHash(omitted) {
		t.Fatal("hash cenario vazio diverge do omitido")
	}
	s, _ := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte(`{}`)) }))
	qaCounts(t, qaTerminal(t, s, qaID(t, qaStart(t, s, p, "cenario-vazio"))))
	if value, exists := p["cenario"]; !exists || value != "" {
		t.Fatal("admissão alterou plano original")
	}
}
