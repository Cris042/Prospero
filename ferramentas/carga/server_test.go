package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

var qaControlKey = []byte("controle-sintetico-32-bytes-exclusivo-qa")
var qaPreflightKey = []byte("preflight-sintetico-32-bytes-exclusivo-qa")

func qaJWT(t *testing.T, key []byte, algorithm string, claims map[string]any) string {
	t.Helper()
	header, _ := json.Marshal(map[string]string{"alg": algorithm, "typ": "JWT"})
	payload, err := json.Marshal(claims)
	if err != nil {
		t.Fatal(err)
	}
	unsigned := base64.RawURLEncoding.EncodeToString(header) + "." + base64.RawURLEncoding.EncodeToString(payload)
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(unsigned))
	return unsigned + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

func qaClaims() map[string]any {
	now := time.Now().Unix()
	return map[string]any{"iss": "prospero-local", "aud": "prospero-carga", "scope": "carga:controle", "iat": now, "nbf": now - 1, "exp": now + 60}
}

func qaPlan(duration int) map[string]any {
	return map[string]any{"servico": "front-office", "modo": "ABERTO", "estagios": []any{map[string]any{"duracaoMs": duration, "rps": 20, "vus": 0}}, "aquecimentoMs": 0, "concorrencia": 2, "maxChamadas": 100, "timeoutMs": 1000, "maxRespostaBytes": 1024}
}

func qaServer(t *testing.T, target http.Handler) (*Server, *atomic.Int64) {
	t.Helper()
	count := &atomic.Int64{}
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		count.Add(1)
		if r.Method != "GET" || r.URL.Path != "/api/v1/sistema" {
			t.Errorf("alvo inesperado: %s %s", r.Method, r.URL.Path)
		}
		if r.Header.Get("Authorization") != "" {
			t.Error("credencial administrativa encaminhada ao alvo")
		}
		target.ServeHTTP(w, r)
	}))
	t.Cleanup(upstream.Close)
	server, err := NewServer(Config{TokenKey: qaControlKey, PreflightKey: qaPreflightKey, Hosts: []string{"example.com"}, Targets: map[string]string{"front-office": upstream.URL}, Cooldown: -1})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(server.Close)
	return server, count
}

func qaRequest(t *testing.T, server *Server, method, path, token string, body any) *httptest.ResponseRecorder {
	t.Helper()
	var data []byte
	if body != nil {
		var err error
		data, err = json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
	}
	req := httptest.NewRequest(method, "http://example.com"+path, bytes.NewReader(data))
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	w := httptest.NewRecorder()
	server.Handler().ServeHTTP(w, req)
	if w.Header().Get("Cache-Control") != "no-store" {
		t.Errorf("%s %s: Cache-Control=%q", method, path, w.Header().Get("Cache-Control"))
	}
	return w
}

func qaAuthorization(t *testing.T, server *Server, plan map[string]any, nonce string) string {
	t.Helper()
	data, _ := json.Marshal(plan)
	var normalized Plan
	if err := json.Unmarshal(data, &normalized); err != nil {
		t.Fatal(err)
	}
	now := time.Now().Unix()
	return qaJWT(t, qaPreflightKey, "HS256", map[string]any{"iss": "prospero-preflight", "aud": server.Instance, "iat": now, "exp": now + 25, "nonce": "qa-preflight-" + nonce, "plano": PlanHash(normalized), "memoriaDisponivelMiB": 2048, "discoLivreMiB": 2048, "cpus": 2, "telemetria": true})
}

func qaStart(t *testing.T, server *Server, plan map[string]any, nonce string) *httptest.ResponseRecorder {
	t.Helper()
	return qaRequest(t, server, "POST", "/api/v1/execucoes-carga", qaJWT(t, qaControlKey, "HS256", qaClaims()), map[string]any{"plano": plan, "autorizacao": qaAuthorization(t, server, plan, nonce)})
}

func qaID(t *testing.T, response *httptest.ResponseRecorder) string {
	t.Helper()
	if response.Code != 202 {
		t.Fatalf("admissão=%d: %s", response.Code, response.Body)
	}
	var data struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &data); err != nil {
		t.Fatal(err)
	}
	if data.ID == "" || response.Header().Get("Location") != "/api/v1/execucoes-carga/"+data.ID {
		t.Fatalf("ID/Location inválidos: %s %v", response.Body, response.Header())
	}
	return data.ID
}

func qaTerminal(t *testing.T, server *Server, id string) map[string]any {
	t.Helper()
	deadline := time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		w := qaRequest(t, server, "GET", "/api/v1/execucoes-carga/"+id, qaJWT(t, qaControlKey, "HS256", qaClaims()), nil)
		if w.Code != 200 {
			t.Fatalf("consulta=%d: %s", w.Code, w.Body)
		}
		var report map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &report); err != nil {
			t.Fatal(err)
		}
		state, _ := report["estado"].(string)
		if state == "CONCLUIDA" || state == "CANCELADA" || state == "FALHA" {
			return report
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("execução não atingiu estado terminal no prazo")
	return nil
}

func qaCounts(t *testing.T, report map[string]any) map[string]int64 {
	t.Helper()
	raw, ok := report["contagens"].(map[string]any)
	if !ok {
		t.Fatalf("contagens ausentes: %v", report)
	}
	counts := map[string]int64{}
	for _, name := range strings.Fields("oferecidas iniciadas naoIniciadas descartadas interrompidas finalizadas emVoo respostasHTTP errosHTTP timeouts falhasTransporte canceladasEmVoo limitesResposta") {
		value, ok := raw[name].(float64)
		if !ok || value < 0 || value != float64(int64(value)) {
			t.Fatalf("contador inválido %s=%v", name, raw[name])
		}
		counts[name] = int64(value)
	}
	if counts["oferecidas"] != counts["iniciadas"]+counts["naoIniciadas"] || counts["naoIniciadas"] != counts["descartadas"]+counts["interrompidas"] || counts["iniciadas"] != counts["finalizadas"]+counts["emVoo"] {
		t.Fatalf("conservação inválida: %v", counts)
	}
	if counts["finalizadas"] != counts["respostasHTTP"]+counts["timeouts"]+counts["falhasTransporte"]+counts["canceladasEmVoo"]+counts["limitesResposta"] {
		t.Fatalf("categorias terminais sobrepostas/incompletas: %v", counts)
	}
	if counts["emVoo"] != 0 || counts["errosHTTP"] > counts["respostasHTTP"] {
		t.Fatalf("relatório terminal inconsistente: %v", counts)
	}
	return counts
}

func TestQAContratoIdentidadeEAutenticacao(t *testing.T) {
	s, count := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte(`{}`)) }))
	for _, variant := range []string{"ausente", "none", "HS512", "assinatura", "expirado", "audiencia", "emissor", "futuro", "permissao"} {
		t.Run(variant, func(t *testing.T) {
			claims := qaClaims()
			algorithm := "HS256"
			key := qaControlKey
			expected := 401
			switch variant {
			case "none", "HS512":
				algorithm = variant
			case "assinatura":
				key = qaPreflightKey
			case "expirado":
				claims["exp"] = time.Now().Unix() - 10
			case "audiencia":
				claims["aud"] = "outra"
			case "emissor":
				claims["iss"] = "outro"
			case "futuro":
				claims["nbf"] = time.Now().Unix() + 120
			case "permissao":
				claims["scope"] = "carga:leitura"
				expected = 403
			}
			token := qaJWT(t, key, algorithm, claims)
			if variant == "ausente" {
				token = ""
			}
			w := qaRequest(t, s, "GET", "/api/v1/sistema", token, nil)
			if w.Code != expected {
				t.Fatalf("status=%d esperado=%d: %s", w.Code, expected, w.Body)
			}
			if strings.Contains(w.Body.String(), token) && token != "" {
				t.Fatal("token refletido na resposta")
			}
		})
	}
	w := qaRequest(t, s, "GET", "/api/v1/sistema", qaJWT(t, qaControlKey, "HS256", qaClaims()), nil)
	if w.Code != 200 || !strings.Contains(w.Body.String(), s.Instance) || s.Instance == "" {
		t.Fatalf("identidade inválida: %d %s", w.Code, w.Body)
	}
	if count.Load() != 0 {
		t.Fatal("controle gerou tráfego")
	}
}

func TestQAAdmissaoUnicaECancelamento(t *testing.T) {
	entered := make(chan struct{}, 1)
	s, _ := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case entered <- struct{}{}:
		default:
		}
		<-r.Context().Done()
	}))
	plan := qaPlan(2000)
	responses := make(chan *httptest.ResponseRecorder, 2)
	var wg sync.WaitGroup
	for i := range 2 {
		wg.Add(1)
		go func() { defer wg.Done(); responses <- qaStart(t, s, plan, fmt.Sprintf("concorrente-%d", i)) }()
	}
	wg.Wait()
	close(responses)
	var id string
	accepted, rejected := 0, 0
	for w := range responses {
		switch w.Code {
		case 202:
			accepted++
			id = qaID(t, w)
		case 409:
			rejected++
		default:
			t.Fatalf("status inesperado %d: %s", w.Code, w.Body)
		}
	}
	if accepted != 1 || rejected != 1 {
		t.Fatalf("admissões=%d rejeições=%d", accepted, rejected)
	}
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("POST aceito não iniciou execução independente")
	}
	token := qaJWT(t, qaControlKey, "HS256", qaClaims())
	w := qaRequest(t, s, "POST", "/api/v1/execucoes-carga/"+id+"/cancelamentos", token, nil)
	if w.Code != 202 && w.Code != 200 {
		t.Fatalf("cancelamento=%d: %s", w.Code, w.Body)
	}
	report := qaTerminal(t, s, id)
	qaCounts(t, report)
	if report["estado"] != "CANCELADA" {
		t.Fatalf("estado=%v", report["estado"])
	}
	w = qaRequest(t, s, "POST", "/api/v1/execucoes-carga/"+id+"/cancelamentos", token, nil)
	if w.Code != 200 {
		t.Fatalf("cancelamento terminal não idempotente: %d", w.Code)
	}
}

func TestQAPlanoInvalidoNaoGeraTrafego(t *testing.T) {
	s, count := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte(`{}`)) }))
	for _, field := range []string{"servico", "modo", "concorrencia", "maxChamadas", "timeoutMs", "maxRespostaBytes", "url", "estagios"} {
		t.Run(field, func(t *testing.T) {
			plan := qaPlan(100)
			switch field {
			case "servico":
				plan[field] = "http://127.0.0.1"
			case "modo":
				plan[field] = "LIVRE"
			case "concorrencia":
				plan[field] = 33
			case "maxChamadas":
				plan[field] = 6001
			case "timeoutMs":
				plan[field] = 2001
			case "maxRespostaBytes":
				plan[field] = 65537
			case "url":
				plan[field] = "http://127.0.0.1"
			case "estagios":
				plan[field] = []any{map[string]any{"duracaoMs": 99, "rps": 1, "vus": 1}}
			}
			w := qaStart(t, s, plan, "invalido-"+field)
			if w.Code != 400 && w.Code != 422 {
				t.Fatalf("plano inválido=%d: %s", w.Code, w.Body)
			}
		})
	}
	if count.Load() != 0 {
		t.Fatalf("plano inválido gerou %d chamadas", count.Load())
	}
}

func TestQAPreflightVinculadoEObrigatorio(t *testing.T) {
	s, count := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte(`{}`)) }))
	plan := qaPlan(100)
	token := qaJWT(t, qaControlKey, "HS256", qaClaims())
	for _, authorization := range []string{"", "invalida", qaJWT(t, qaControlKey, "HS256", qaClaims())} {
		w := qaRequest(t, s, "POST", "/api/v1/execucoes-carga", token, map[string]any{"plano": plan, "autorizacao": authorization})
		if w.Code != 503 {
			t.Fatalf("preflight inválido=%d: %s", w.Code, w.Body)
		}
	}
	other := qaPlan(200)
	w := qaRequest(t, s, "POST", "/api/v1/execucoes-carga", token, map[string]any{"plano": plan, "autorizacao": qaAuthorization(t, s, other, "plano-divergente")})
	if w.Code != 503 {
		t.Fatalf("preflight para outro plano=%d: %s", w.Code, w.Body)
	}
	if count.Load() != 0 {
		t.Fatal("preflight inválido gerou tráfego")
	}
}

func TestQARespostaExcessivaEConservacao(t *testing.T) {
	s, count := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.(http.Flusher).Flush()
		w.Write(bytes.Repeat([]byte("x"), 2048))
	}))
	id := qaID(t, qaStart(t, s, qaPlan(150), "resposta-excessiva"))
	counts := qaCounts(t, qaTerminal(t, s, id))
	if count.Load() == 0 || counts["limitesResposta"] == 0 || counts["respostasHTTP"] != 0 {
		t.Fatalf("limite de resposta não aplicado: %v, alvo=%d", counts, count.Load())
	}
}

func TestQAErrosHTTPNaoDuplicamContagens(t *testing.T) {
	s, count := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(503); w.Write([]byte(`{}`)) }))
	id := qaID(t, qaStart(t, s, qaPlan(150), "http-503"))
	counts := qaCounts(t, qaTerminal(t, s, id))
	if count.Load() == 0 || counts["errosHTTP"] != counts["respostasHTTP"] || counts["errosHTTP"] == 0 {
		t.Fatalf("erros HTTP inconsistentes: %v", counts)
	}
}

func TestQAHostEOriginRecusados(t *testing.T) {
	s, count := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte(`{}`)) }))
	for _, boundary := range []string{"Host", "Origin"} {
		t.Run(boundary, func(t *testing.T) {
			req := httptest.NewRequest("GET", "http://example.com/api/v1/sistema", nil)
			req.Header.Set("Authorization", "Bearer "+qaJWT(t, qaControlKey, "HS256", qaClaims()))
			if boundary == "Host" {
				req.Host = "outro.example"
			} else {
				req.Header.Set("Origin", "http://outro.example")
			}
			w := httptest.NewRecorder()
			s.Handler().ServeHTTP(w, req)
			if w.Code != 403 {
				t.Fatalf("%s recusado=%d: %s", boundary, w.Code, w.Body)
			}
		})
	}
	if count.Load() != 0 {
		t.Fatal("controle recusado gerou tráfego")
	}
}

func TestQAPreflightExpiradoInsuficienteEDeOutraInstancia(t *testing.T) {
	s, count := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte(`{}`)) }))
	plan := qaPlan(100)
	data, _ := json.Marshal(plan)
	var normalized Plan
	json.Unmarshal(data, &normalized)
	for _, field := range []string{"exp", "iat", "aud", "memoriaDisponivelMiB", "discoLivreMiB", "cpus"} {
		t.Run(field, func(t *testing.T) {
			now := time.Now().Unix()
			claims := map[string]any{"iss": "prospero-preflight", "aud": s.Instance, "iat": now, "exp": now + 25, "nonce": "rejeitado-" + field, "plano": PlanHash(normalized), "memoriaDisponivelMiB": 2048, "discoLivreMiB": 2048, "cpus": 2, "telemetria": true}
			switch field {
			case "exp":
				claims[field] = now - 1
			case "iat":
				claims[field] = now - 60
			case "aud":
				claims[field] = "outra-instancia"
			default:
				claims[field] = 0
			}
			w := qaRequest(t, s, "POST", "/api/v1/execucoes-carga", qaJWT(t, qaControlKey, "HS256", qaClaims()), map[string]any{"plano": plan, "autorizacao": qaJWT(t, qaPreflightKey, "HS256", claims)})
			if w.Code != 503 {
				t.Fatalf("preflight %s=%d: %s", field, w.Code, w.Body)
			}
		})
	}
	if count.Load() != 0 {
		t.Fatal("preflight recusado gerou tráfego")
	}
}

func TestQAPreflightNaoPodeSerReutilizado(t *testing.T) {
	s, _ := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte(`{}`)) }))
	plan := qaPlan(100)
	body := map[string]any{"plano": plan, "autorizacao": qaAuthorization(t, s, plan, "nonce-unico")}
	token := qaJWT(t, qaControlKey, "HS256", qaClaims())
	id := qaID(t, qaRequest(t, s, "POST", "/api/v1/execucoes-carga", token, body))
	qaCounts(t, qaTerminal(t, s, id))
	w := qaRequest(t, s, "POST", "/api/v1/execucoes-carga", token, body)
	if w.Code != 503 {
		t.Fatalf("preflight reutilizado=%d: %s", w.Code, w.Body)
	}
}

func TestQAModoFechadoRespeitaMaximoDeChamadas(t *testing.T) {
	s, count := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte(`{}`)) }))
	plan := qaPlan(200)
	plan["modo"] = "FECHADO"
	plan["estagios"] = []any{map[string]any{"duracaoMs": 200, "rps": 20, "vus": 1}}
	plan["maxChamadas"] = 2
	counts := qaCounts(t, qaTerminal(t, s, qaID(t, qaStart(t, s, plan, "fechado-limitado"))))
	if count.Load() == 0 || count.Load() > 2 || counts["iniciadas"] > 2 {
		t.Fatalf("teto fechado excedido: alvo=%d contagens=%v", count.Load(), counts)
	}
}

func TestQAQuantisNearestRankEPopulacaoVazia(t *testing.T) {
	if quantiles(nil) != nil {
		t.Fatal("população vazia deve permanecer ausente")
	}
	samples := []int64{100, 1, 50, 10}
	q := quantiles(samples)
	if q.Amostras != 4 || q.P50Us != 10 || q.P95Us != 100 || q.P99Us != 100 || q.MaximoUs != 100 {
		t.Fatalf("quantis divergentes: %+v", q)
	}
	if samples[0] != 100 {
		t.Fatal("cálculo alterou amostras originais")
	}
}

func TestQAAquecimentoForaDaPopulacaoMedida(t *testing.T) {
	s, _ := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte(`{}`)) }))
	plan := qaPlan(300)
	plan["aquecimentoMs"] = 100
	report := qaTerminal(t, s, qaID(t, qaStart(t, s, plan, "aquecimento-populacao")))
	counts := qaCounts(t, report)
	measurement := report["medicao"].(map[string]any)
	quantiles := report["quantis"].(map[string]any)
	if measurement["finalizadas"].(float64) >= float64(counts["finalizadas"]) || measurement["finalizadas"] != quantiles["amostras"] {
		t.Fatalf("aquecimento misturado à medição: %v", report)
	}
	if report["populacao"] == "" {
		t.Fatal("população não documentada")
	}
}

func TestQARedirectNaoEExecutado(t *testing.T) {
	var redirected atomic.Int64
	destination := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { redirected.Add(1); w.Write([]byte(`{}`)) }))
	defer destination.Close()
	s, _ := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, destination.URL, 302) }))
	report := qaTerminal(t, s, qaID(t, qaStart(t, s, qaPlan(100), "redirect-recusa")))
	counts := qaCounts(t, report)
	if redirected.Load() != 0 || counts["falhasTransporte"] == 0 {
		t.Fatalf("redirect executado ou não contabilizado: %v alvo=%d", counts, redirected.Load())
	}
}

func TestQAProxyAmbienteNaoEUsado(t *testing.T) {
	var proxied atomic.Int64
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { proxied.Add(1); w.WriteHeader(502) }))
	defer proxy.Close()
	t.Setenv("HTTP_PROXY", proxy.URL)
	t.Setenv("HTTPS_PROXY", proxy.URL)
	t.Setenv("ALL_PROXY", proxy.URL)
	s, count := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte(`{}`)) }))
	counts := qaCounts(t, qaTerminal(t, s, qaID(t, qaStart(t, s, qaPlan(100), "proxy-ambiente"))))
	if proxied.Load() != 0 || count.Load() == 0 || counts["respostasHTTP"] == 0 {
		t.Fatalf("proxy usado ou alvo não acessado: %v proxy=%d alvo=%d", counts, proxied.Load(), count.Load())
	}
}

func TestQAConteudoComprimidoRecusado(t *testing.T) {
	s, _ := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Encoding", "gzip")
		w.Write([]byte("conteudo-nao-deve-ser-expandido"))
	}))
	counts := qaCounts(t, qaTerminal(t, s, qaID(t, qaStart(t, s, qaPlan(100), "gzip-recusa"))))
	if counts["limitesResposta"] == 0 || counts["respostasHTTP"] != 0 {
		t.Fatalf("compressão aceita: %v", counts)
	}
}

func TestQAJSONDuplicadoRecusadoAntesDeAdmissao(t *testing.T) {
	s, count := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte(`{}`)) }))
	body := `{"plano":{},"plano":{},"autorizacao":""}`
	req := httptest.NewRequest("POST", "http://example.com/api/v1/execucoes-carga", strings.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+qaJWT(t, qaControlKey, "HS256", qaClaims()))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, req)
	if w.Code != 400 || count.Load() != 0 {
		t.Fatalf("JSON duplicado admitido: %d alvo=%d", w.Code, count.Load())
	}
}

func TestQACloseAguardaCancelamentoDasChamadas(t *testing.T) {
	entered := make(chan struct{}, 1)
	finished := make(chan struct{}, 1)
	s, _ := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case entered <- struct{}{}:
		default:
		}
		<-r.Context().Done()
		select {
		case finished <- struct{}{}:
		default:
		}
	}))
	id := qaID(t, qaStart(t, s, qaPlan(2000), "shutdown-ativo"))
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("carga não iniciou")
	}
	done := make(chan struct{})
	go func() { s.Close(); close(done) }()
	select {
	case <-done:
	case <-time.After(2500 * time.Millisecond):
		t.Fatal("shutdown não encerrou em prazo limitado")
	}
	select {
	case <-finished:
	case <-time.After(time.Second):
		t.Fatal("requisição no alvo não foi cancelada")
	}
	qaCounts(t, qaTerminal(t, s, id))
	w := qaStart(t, s, qaPlan(100), "apos-shutdown")
	if w.Code != 503 {
		t.Fatalf("servidor encerrado aceitou carga: %d", w.Code)
	}
}

func TestQACampoVUsObrigatorioMesmoQuandoAberto(t *testing.T) {
	s, count := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte(`{}`)) }))
	plan := qaPlan(100)
	plan["estagios"] = []any{map[string]any{"duracaoMs": 100, "rps": 5}}
	w := qaStart(t, s, plan, "campo-vus-ausente")
	if w.Code != 400 && w.Code != 422 {
		t.Fatalf("vus estrutural obrigatório ausente=%d: %s", w.Code, w.Body)
	}
	if count.Load() != 0 {
		t.Fatal("plano incompleto gerou tráfego")
	}
}

func TestQAHeaderPreflightExternoEDuplicidade(t *testing.T) {
	s, _ := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte(`{}`)) }))
	plan := qaPlan(100)
	authorization := qaAuthorization(t, s, plan, "header-preflight-externo")
	token := qaJWT(t, qaControlKey, "HS256", qaClaims())
	request := func(bodyAuthorization bool) *httptest.ResponseRecorder {
		body := map[string]any{"plano": plan}
		if bodyAuthorization {
			body["autorizacao"] = authorization
		}
		data, _ := json.Marshal(body)
		req := httptest.NewRequest("POST", "http://example.com/api/v1/execucoes-carga", bytes.NewReader(data))
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Prospero-Preflight", authorization)
		w := httptest.NewRecorder()
		s.Handler().ServeHTTP(w, req)
		return w
	}
	w := request(true)
	if w.Code != 400 {
		t.Fatalf("preflight em body+header não recusado: %d %s", w.Code, w.Body)
	}
	qaCounts(t, qaTerminal(t, s, qaID(t, request(false))))
}

func TestQACalendarioTresRPSEJanelaEntreEstagios(t *testing.T) {
	s, _ := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte(`{}`)) }))
	plan := qaPlan(1000)
	plan["aquecimentoMs"] = 500
	plan["estagios"] = []any{map[string]any{"duracaoMs": 1000, "rps": 3, "vus": 0}, map[string]any{"duracaoMs": 400, "rps": 5, "vus": 0}}
	report := qaTerminal(t, s, qaID(t, qaStart(t, s, plan, "tres-rps-estagios")))
	counts := qaCounts(t, report)
	if counts["oferecidas"] != 5 || report["ofertasPrevistas"] != float64(5) {
		t.Fatalf("calendário 3RPS/1s + 5RPS/0,4s deve oferecer5: %v", report)
	}
	windows := report["janelas"].([]any)
	if len(windows) != 3 {
		t.Fatalf("esperadas3 interseções fase/estágio: %v", windows)
	}
	wantDurations := []float64{500, 500, 400}
	var totalOffers float64
	for i, raw := range windows {
		window := raw.(map[string]any)
		if window["duracaoMs"] != wantDurations[i] || window["duracaoObservadaMs"].(float64) < 0 || window["duracaoObservadaMs"].(float64) > wantDurations[i] {
			t.Fatalf("janela incoerente: %v", window)
		}
		totalOffers += window["contagens"].(map[string]any)["oferecidas"].(float64)
	}
	if totalOffers != float64(counts["oferecidas"]) {
		t.Fatalf("janelas não reconciliam: %v", windows)
	}
}

func TestQAOfertaFuturaPermaneceSeparadaAposCancelamento(t *testing.T) {
	entered := make(chan struct{}, 1)
	s, _ := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case entered <- struct{}{}:
		default:
		}
		<-r.Context().Done()
	}))
	plan := qaPlan(1000)
	plan["estagios"] = []any{map[string]any{"duracaoMs": 1000, "rps": 1, "vus": 0}, map[string]any{"duracaoMs": 1000, "rps": 10, "vus": 0}}
	id := qaID(t, qaStart(t, s, plan, "cancelar-oferta-futura"))
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("carga não iniciou")
	}
	qaRequest(t, s, "POST", "/api/v1/execucoes-carga/"+id+"/cancelamentos", qaJWT(t, qaControlKey, "HS256", qaClaims()), nil)
	report := qaTerminal(t, s, id)
	counts := qaCounts(t, report)
	if report["ofertasPrevistas"] != float64(11) || counts["oferecidas"] != 1 {
		t.Fatalf("oferta futura confundida com oferecida: %v", report)
	}
	if len(report["janelas"].([]any)) != 1 {
		t.Fatalf("estágio futuro anunciado como observado: %v", report["janelas"])
	}
}

func TestQATelemetriaObrigatoriaNoPreflight(t *testing.T) {
	s, count := qaServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte(`{}`)) }))
	plan := qaPlan(100)
	data, _ := json.Marshal(plan)
	var normalized Plan
	json.Unmarshal(data, &normalized)
	now := time.Now().Unix()
	claims := map[string]any{"iss": "prospero-preflight", "aud": s.Instance, "iat": now, "exp": now + 25, "nonce": "telemetria-negativa-qa", "plano": PlanHash(normalized), "memoriaDisponivelMiB": 2048, "discoLivreMiB": 2048, "cpus": 2, "telemetria": false}
	w := qaRequest(t, s, "POST", "/api/v1/execucoes-carga", qaJWT(t, qaControlKey, "HS256", qaClaims()), map[string]any{"plano": plan, "autorizacao": qaJWT(t, qaPreflightKey, "HS256", claims)})
	if w.Code != 503 || count.Load() != 0 {
		t.Fatalf("telemetria indisponível admitida: %d alvo=%d", w.Code, count.Load())
	}
}
