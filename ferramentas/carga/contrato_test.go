package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"reflect"
	"sort"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func xPlanBody(t *testing.T, s *Server, plan map[string]any, nonce string) map[string]any {
	return map[string]any{"plano": plan, "autorizacao": qaAuthorization(t, s, plan, nonce)}
}

func ok200(w http.ResponseWriter, _ *http.Request) { w.Write([]byte(`{}`)) }

// A: limites do plano não cobertos pelo teste original.
func TestXLimitesPlano(t *testing.T) {
	s, count := qaServer(t, http.HandlerFunc(ok200))
	st := func(d, r, v int) map[string]any { return map[string]any{"duracaoMs": d, "rps": r, "vus": v} }
	cases := map[string]func(p map[string]any){
		"rps101": func(p map[string]any) { p["estagios"] = []any{st(100, 101, 0)} },
		"rps0":   func(p map[string]any) { p["estagios"] = []any{st(100, 0, 0)} },
		"vus17": func(p map[string]any) {
			p["modo"] = "FECHADO"
			p["concorrencia"] = 32
			p["estagios"] = []any{st(100, 1, 17)}
		},
		"abertoVus1":  func(p map[string]any) { p["estagios"] = []any{st(100, 1, 1)} },
		"fechadoVus0": func(p map[string]any) { p["modo"] = "FECHADO"; p["estagios"] = []any{st(100, 1, 0)} },
		"fechadoVusGtConc": func(p map[string]any) {
			p["modo"] = "FECHADO"
			p["concorrencia"] = 2
			p["estagios"] = []any{st(100, 1, 3)}
		},
		"seisEstagios": func(p map[string]any) {
			p["estagios"] = []any{st(100, 1, 0), st(100, 1, 0), st(100, 1, 0), st(100, 1, 0), st(100, 1, 0), st(100, 1, 0)}
		},
		"zeroEstagios":      func(p map[string]any) { p["estagios"] = []any{} },
		"total60001":        func(p map[string]any) { p["estagios"] = []any{st(30000, 1, 0), st(30001, 1, 0)} },
		"aquecIgualTotal":   func(p map[string]any) { p["aquecimentoMs"] = 100 },
		"aquecNegativo":     func(p map[string]any) { p["aquecimentoMs"] = -1 },
		"concorrencia0":     func(p map[string]any) { p["concorrencia"] = 0 },
		"maxChamadas0":      func(p map[string]any) { p["maxChamadas"] = 0 },
		"timeout0":          func(p map[string]any) { p["timeoutMs"] = 0 },
		"campoDesconhecido": func(p map[string]any) { p["extra"] = 1 },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			p := qaPlan(100)
			mutate(p)
			w := qaStart(t, s, p, "lim-"+name)
			if w.Code != 400 && w.Code != 422 {
				t.Fatalf("%s aceito: %d %s", name, w.Code, w.Body)
			}
		})
	}
	if count.Load() != 0 {
		t.Fatal("tráfego gerado")
	}
	// Fronteiras válidas
	max := Plan{Servico: "back-office", Modo: "FECHADO", Estagios: []Stage{{12000, 100, 16}, {12000, 100, 16}, {12000, 100, 16}, {12000, 100, 16}, {12000, 100, 16}}, AquecimentoMs: 59999, Concorrencia: 32, MaxChamadas: 6000, TimeoutMs: 2000, MaxRespostaBytes: 65536}
	if err := max.Validate(); err != nil {
		t.Fatalf("plano no limite recusado: %v", err)
	}
}

// B: JWT negativo no POST => 401/403, sem tráfego, nonce não consumido.
func TestXJWTNegativoNoPostNaoConsomeNonce(t *testing.T) {
	s, count := qaServer(t, http.HandlerFunc(ok200))
	plan := qaPlan(100)
	body := xPlanBody(t, s, plan, "jwt-post-negativo")
	noExp := qaClaims()
	delete(noExp, "exp")
	noScope := qaClaims()
	delete(noScope, "scope")
	extra := qaClaims()
	extra["admin"] = true
	for name, tc := range map[string]struct {
		tok  string
		code int
	}{
		"semExp":         {qaJWT(t, qaControlKey, "HS256", noExp), 401},
		"semScope":       {qaJWT(t, qaControlKey, "HS256", noScope), 403},
		"chavePreflight": {qaJWT(t, qaPreflightKey, "HS256", qaClaims()), 401},
		"claimExtra":     {qaJWT(t, qaControlKey, "HS256", extra), 401},
		"noneSemAssin":   {strings.Join(strings.Split(qaJWT(t, qaControlKey, "none", qaClaims()), ".")[:2], ".") + ".", 401},
	} {
		w := qaRequest(t, s, "POST", "/api/v1/execucoes-carga", tc.tok, body)
		if w.Code != tc.code {
			t.Fatalf("%s=%d esperado %d", name, w.Code, tc.code)
		}
	}
	if count.Load() != 0 {
		t.Fatal("tráfego gerado")
	}
	tok := qaJWT(t, qaControlKey, "HS256", qaClaims())
	qaCounts(t, qaTerminal(t, s, qaID(t, qaRequest(t, s, "POST", "/api/v1/execucoes-carga", tok, body))))
}

// D: perdedor de 409 e recusa por cooldown não consomem nonce.
func TestXNonceNaoConsumidoEm409E429(t *testing.T) {
	s, _ := qaServer(t, http.HandlerFunc(ok200))
	tok := qaJWT(t, qaControlKey, "HS256", qaClaims())
	plan := qaPlan(300)
	first := qaRequest(t, s, "POST", "/api/v1/execucoes-carga", tok, xPlanBody(t, s, plan, "n-primeiro"))
	id := qaID(t, first)
	loser := xPlanBody(t, s, plan, "n-perdedor")
	if w := qaRequest(t, s, "POST", "/api/v1/execucoes-carga", tok, loser); w.Code != 409 {
		t.Fatalf("esperado 409: %d", w.Code)
	}
	qaTerminal(t, s, id)
	qaTerminal(t, s, qaID(t, qaRequest(t, s, "POST", "/api/v1/execucoes-carga", tok, loser)))

	// cooldown
	s.cfg.Cooldown = 10 * time.Second
	cd := xPlanBody(t, s, plan, "n-cooldown")
	w := qaRequest(t, s, "POST", "/api/v1/execucoes-carga", tok, cd)
	if w.Code != 429 || w.Header().Get("Retry-After") == "" {
		t.Fatalf("cooldown esperado 429: %d", w.Code)
	}
	s.mu.Lock()
	_, used := s.nonces["qa-preflight-n-cooldown"]
	s.cfg.Cooldown = -1
	s.mu.Unlock()
	if used {
		t.Fatal("nonce consumido em 429")
	}
}

// E/F: nonce curto e validade > 30 s.
func TestXPreflightNonceCurtoEValidadeLonga(t *testing.T) {
	s, count := qaServer(t, http.HandlerFunc(ok200))
	plan := qaPlan(100)
	data, _ := json.Marshal(plan)
	var n Plan
	json.Unmarshal(data, &n)
	now := time.Now().Unix()
	base := func() map[string]any {
		return map[string]any{"iss": "prospero-preflight", "aud": s.Instance, "iat": now, "exp": now + 25, "nonce": "nonce-valido-123456", "plano": PlanHash(n), "memoriaDisponivelMiB": 2048, "discoLivreMiB": 2048, "cpus": 2, "telemetria": true}
	}
	curto := base()
	curto["nonce"] = "curto"
	longo := base()
	longo["exp"] = now + 31
	semIat := base()
	delete(semIat, "iat")
	mem := base()
	mem["memoriaDisponivelMiB"] = 1023
	tok := qaJWT(t, qaControlKey, "HS256", qaClaims())
	for name, c := range map[string]map[string]any{"curto": curto, "longo": longo, "semIat": semIat, "mem1023": mem} {
		w := qaRequest(t, s, "POST", "/api/v1/execucoes-carga", tok, map[string]any{"plano": plan, "autorizacao": qaJWT(t, qaPreflightKey, "HS256", c)})
		if w.Code != 503 {
			t.Fatalf("%s=%d", name, w.Code)
		}
	}
	if count.Load() != 0 {
		t.Fatal("tráfego")
	}
}

// G: modo FECHADO limita concorrência a VUs e ofertasPrevistas é null.
func TestXFechadoLimitaVUs(t *testing.T) {
	var cur, peak atomic.Int64
	s, _ := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		c := cur.Add(1)
		for {
			p := peak.Load()
			if c <= p || peak.CompareAndSwap(p, c) {
				break
			}
		}
		time.Sleep(120 * time.Millisecond)
		cur.Add(-1)
		w.Write([]byte(`{}`))
	}))
	plan := qaPlan(600)
	plan["modo"] = "FECHADO"
	plan["concorrencia"] = 8
	plan["estagios"] = []any{map[string]any{"duracaoMs": 600, "rps": 50, "vus": 2}}
	r := qaTerminal(t, s, qaID(t, qaStart(t, s, plan, "fechado-vus")))
	c := qaCounts(t, r)
	if peak.Load() > 2 || peak.Load() == 0 {
		t.Fatalf("pico=%d", peak.Load())
	}
	if v, present := r["ofertasPrevistas"]; !present || v != nil {
		t.Fatalf("ofertasPrevistas fechado=%v", v)
	}
	if c["oferecidas"] >= 30 {
		t.Fatalf("fechado contou ofertas sem VU livre: %v", c)
	}
	t.Logf("fechado: %v", c)
}

// H: ABERTO com capacidade saturada contabiliza descartes.
func TestXAbertoDescartaPorCapacidade(t *testing.T) {
	s, _ := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		time.Sleep(200 * time.Millisecond)
		w.Write([]byte(`{}`))
	}))
	plan := qaPlan(500)
	plan["concorrencia"] = 1
	r := qaTerminal(t, s, qaID(t, qaStart(t, s, plan, "aberto-capacidade")))
	c := qaCounts(t, r)
	if c["oferecidas"] != 10 || c["descartadas"] == 0 || r["ofertasPrevistas"] != float64(10) {
		t.Fatalf("aberto saturado: %v %v", c, r["ofertasPrevistas"])
	}
}

// I: timeouts categorizados.
func TestXTimeout(t *testing.T) {
	s, _ := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-time.After(400 * time.Millisecond):
		case <-r.Context().Done():
		}
	}))
	plan := qaPlan(100)
	plan["timeoutMs"] = 50
	c := qaCounts(t, qaTerminal(t, s, qaID(t, qaStart(t, s, plan, "timeout"))))
	if c["timeouts"] == 0 || c["timeouts"] != c["finalizadas"] {
		t.Fatalf("timeouts: %v", c)
	}
}

// J: sem amostras medidas => quantis ausentes/null no relatório e nas janelas.
func TestXQuantisAusentesNoRelatorio(t *testing.T) {
	entered := make(chan struct{}, 1)
	s, _ := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case entered <- struct{}{}:
		default:
		}
		<-r.Context().Done()
	}))
	plan := qaPlan(2000)
	plan["aquecimentoMs"] = 1500
	id := qaID(t, qaStart(t, s, plan, "quantis-ausentes"))
	<-entered
	qaRequest(t, s, "POST", "/api/v1/execucoes-carga/"+id+"/cancelamentos", qaJWT(t, qaControlKey, "HS256", qaClaims()), nil)
	r := qaTerminal(t, s, id)
	qaCounts(t, r)
	if q, ok := r["quantis"]; ok && q != nil {
		t.Fatalf("quantis presentes sem amostras: %v", q)
	}
	for _, w := range r["janelas"].([]any) {
		if q := w.(map[string]any)["quantis"]; q != nil {
			t.Fatalf("quantis de janela presentes: %v", q)
		}
	}
	if r["estado"] != "CANCELADA" || r["motivo"] != "CANCELAMENTO" {
		t.Fatalf("estado/motivo: %v %v", r["estado"], r["motivo"])
	}
}

// K: retenção 20 resultados e 20 minutos; ativo não expira.
func TestXRetencao(t *testing.T) {
	s, _ := qaServer(t, http.HandlerFunc(ok200))
	tok := qaJWT(t, qaControlKey, "HS256", qaClaims())
	var ids []string
	for i := range 21 {
		id := qaID(t, qaStart(t, s, qaPlan(100), fmt.Sprintf("ret-%02d", i)))
		qaTerminal(t, s, id)
		ids = append(ids, id)
	}
	if w := qaRequest(t, s, "GET", "/api/v1/execucoes-carga/"+ids[0], tok, nil); w.Code != 404 {
		t.Fatalf("21º resultado não podou o mais antigo: %d", w.Code)
	}
	s.mu.Lock()
	n := len(s.runs)
	old := time.Now().Add(-21 * time.Minute)
	s.runs[ids[1]].Fim = &old
	s.mu.Unlock()
	if n != 20 {
		t.Fatalf("retidos=%d", n)
	}
	if w := qaRequest(t, s, "GET", "/api/v1/execucoes-carga/"+ids[1], tok, nil); w.Code != 404 {
		t.Fatalf("resultado de 21 min não expirou: %d", w.Code)
	}
	// ativo nunca expira mesmo com Fim antigo simulado? (ativo não tem Fim) – verifica que ativo sobrevive à poda por contagem
	entered := make(chan struct{}, 1)
	_ = entered
	active := qaID(t, qaStart(t, s, qaPlan(1500), "ret-ativo"))
	for i := range 3 {
		s.mu.Lock()
		s.runs[fmt.Sprintf("%032d", i)] = &run{Report: Report{Estado: "CONCLUIDA"}}
		s.order = append([]string{fmt.Sprintf("%032d", i)}, s.order...)
		s.mu.Unlock()
	}
	if w := qaRequest(t, s, "GET", "/api/v1/execucoes-carga/"+active, tok, nil); w.Code != 200 {
		t.Fatalf("ativo podado: %d", w.Code)
	}
	qaRequest(t, s, "POST", "/api/v1/execucoes-carga/"+active+"/cancelamentos", tok, nil)
	qaTerminal(t, s, active)
}

// L: chamadas anônimas usam orçamento próprio e não bloqueiam o operador autenticado.
func TestXLimiteAdministrativoNaoAutenticado(t *testing.T) {
	s, _ := qaServer(t, http.HandlerFunc(ok200))
	for range 101 {
		qaRequest(t, s, "GET", "/api/v1/sistema", "", nil)
	}
	if w := qaRequest(t, s, "GET", "/api/v1/sistema", "", nil); w.Code != 429 {
		t.Fatalf("anônimo excedente deveria receber 429, obtido %d", w.Code)
	}
	if w := qaRequest(t, s, "GET", "/api/v1/sistema", qaJWT(t, qaControlKey, "HS256", qaClaims()), nil); w.Code != 200 {
		t.Fatalf("autenticado bloqueado por anônimos: %d", w.Code)
	}
}

// Controle exige iat e validade máxima de 15 minutos; hosts vazios invalidam a configuração.
func TestXControleTTLEHostsVazios(t *testing.T) {
	s, _ := qaServer(t, http.HandlerFunc(ok200))
	semIat := qaClaims()
	delete(semIat, "iat")
	longo := qaClaims()
	longo["exp"] = longo["iat"].(int64) + 901
	for name, c := range map[string]map[string]any{"sem iat": semIat, "longo": longo} {
		if w := qaRequest(t, s, "GET", "/api/v1/sistema", qaJWT(t, qaControlKey, "HS256", c), nil); w.Code != 401 {
			t.Fatalf("%s: esperado 401, obtido %d", name, w.Code)
		}
	}
	cfg := Config{TokenKey: qaControlKey, PreflightKey: qaPreflightKey, Hosts: []string{""}, Targets: map[string]string{}}
	if _, err := NewServer(cfg); err == nil {
		t.Fatal("host vazio aceito")
	}
}

// M: calendário que oferece exatamente maxChamadas reporta motivo.
func TestXMotivoCalendarioIgualTeto(t *testing.T) {
	s, _ := qaServer(t, http.HandlerFunc(ok200))
	plan := qaPlan(100) // 20 rps * 100 ms = 2 ofertas
	plan["maxChamadas"] = 2
	r := qaTerminal(t, s, qaID(t, qaStart(t, s, plan, "teto-igual")))
	t.Logf("motivo=%v", r["motivo"])
	if r["motivo"] != "CALENDARIO_ENCERRADO" {
		t.Errorf("calendário completo reportado como %v", r["motivo"])
	}
}

// N: rotas, métodos e conteúdo.
func TestXRotasMetodosConteudo(t *testing.T) {
	s, count := qaServer(t, http.HandlerFunc(ok200))
	tok := qaJWT(t, qaControlKey, "HS256", qaClaims())
	check := func(name string, w *httptest.ResponseRecorder, code int) {
		if w.Code != code {
			t.Errorf("%s=%d esperado %d", name, w.Code, code)
		}
	}
	check("GET coleção", qaRequest(t, s, "GET", "/api/v1/execucoes-carga", tok, nil), 405)
	check("id curto", qaRequest(t, s, "GET", "/api/v1/execucoes-carga/abc", tok, nil), 404)
	check("id ausente", qaRequest(t, s, "GET", "/api/v1/execucoes-carga/"+strings.Repeat("a", 32), tok, nil), 404)
	check("cancel GET", qaRequest(t, s, "GET", "/api/v1/execucoes-carga/"+strings.Repeat("a", 32)+"/cancelamentos", tok, nil), 405)
	check("query", qaRequest(t, s, "GET", "/api/v1/sistema?x=1", tok, nil), 400)
	check("openapi", qaRequest(t, s, "GET", "/openapi.json", tok, nil), 200)
	req := httptest.NewRequest("POST", "http://example.com/api/v1/execucoes-carga", strings.NewReader("{}"))
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Content-Type", "text/plain")
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, req)
	check("415", w, 415)
	req = httptest.NewRequest("POST", "http://example.com/api/v1/execucoes-carga", strings.NewReader(strings.Repeat(" ", 20000)))
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Content-Type", "application/json")
	w = httptest.NewRecorder()
	s.Handler().ServeHTTP(w, req)
	check("413", w, 413)
	req = httptest.NewRequest("GET", "http://example.com/api/v1/sistema", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("X-Forwarded-Host", "example.com")
	req.Host = "evil.example"
	w = httptest.NewRecorder()
	s.Handler().ServeHTTP(w, req)
	check("X-Forwarded-Host", w, 403)
	if count.Load() != 0 {
		t.Fatal("tráfego")
	}
}

// Métricas: exposição básica após execução.
func TestXMetricas(t *testing.T) {
	s, _ := qaServer(t, http.HandlerFunc(ok200))
	qaTerminal(t, s, qaID(t, qaStart(t, s, qaPlan(100), "metricas")))
	w := httptest.NewRecorder()
	s.MetricsHandler().ServeHTTP(w, httptest.NewRequest("GET", "/metrics", nil))
	b := w.Body.String()
	for _, k := range []string{`carga_oferecidas_total{servico="front-office"} 2`, "carga_execucao_ativa 0", `carga_duracao_seconds_count{servico="front-office"} 2`} {
		if !strings.Contains(b, k) {
			t.Errorf("métrica ausente: %s", k)
		}
	}
	w = httptest.NewRecorder()
	s.MetricsHandler().ServeHTTP(w, httptest.NewRequest("GET", "/healthz", nil))
	if w.Code != 200 {
		t.Error("healthz")
	}
}

// OpenAPI exige exatamente os campos que strictJSON exige do plano e de cada estágio.
func TestXOpenAPIAderenteAoPlano(t *testing.T) {
	var doc struct {
		Components struct {
			Schemas map[string]struct {
				Required   []string       `json:"required"`
				Properties map[string]any `json:"properties"`
			} `json:"schemas"`
		} `json:"components"`
	}
	if err := json.Unmarshal(openAPI, &doc); err != nil {
		t.Fatal(err)
	}
	for name, value := range map[string]any{"Plan": Plan{}, "Stage": Stage{}} {
		schema := doc.Components.Schemas[name]
		typ := reflect.TypeOf(value)
		var fields []string
		for i := range typ.NumField() {
			fields = append(fields, strings.Split(typ.Field(i).Tag.Get("json"), ",")[0])
		}
		sort.Strings(fields)
		required := append([]string(nil), schema.Required...)
		sort.Strings(required)
		if !reflect.DeepEqual(fields, required) || len(schema.Properties) != len(fields) {
			t.Fatalf("%s: OpenAPI %v diverge dos campos obrigatórios %v", name, required, fields)
		}
	}
}
