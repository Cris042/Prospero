package main

import (
	"context"
	"crypto/hmac"
	"encoding/json"
	"io"
	"mime"
	"net/http"
	"net/url"
	"runtime"
	"strings"
	"sync"
	"time"
)

type Config struct {
	FinancialTokens map[string]string
	TokenKey        []byte
	PreflightKey    []byte
	Hosts           []string
	Targets         map[string]string
	Cooldown        time.Duration
}
type run struct {
	Report
	credencial string
	cancel     context.CancelFunc
	done       chan struct{}
	samples    []int64
}
type Server struct {
	Instance    string
	cfg         Config
	mu          sync.Mutex
	active      *run
	runs        map[string]*run
	order       []string
	nonces      map[string]int64
	closed      bool
	lastStart   time.Time
	adminWindow time.Time
	adminCalls  int
	anonWindow  time.Time
	anonCalls   int
	totals      map[string]*Counts
	hist        map[string][]int64
	maxDelay    int64
}

const (
	adminLimit    = 100
	maxControlTTL = 900
)

func NewServer(cfg Config) (*Server, error) {
	if len(cfg.TokenKey) < 32 || len(cfg.PreflightKey) < 32 || hmac.Equal(cfg.TokenKey, cfg.PreflightKey) || len(cfg.Hosts) == 0 {
		return nil, errConfig
	}
	for _, h := range cfg.Hosts {
		if strings.TrimSpace(h) == "" {
			return nil, errConfig
		}
	}
	if cfg.Cooldown == 0 {
		cfg.Cooldown = time.Second
	}
	for name, raw := range cfg.Targets {
		u, e := url.Parse(raw)
		if e != nil || u.Scheme != "http" || u.Host == "" || u.User != nil || (u.Path != "" && u.Path != "/") || u.RawQuery != "" || u.Fragment != "" {
			return nil, errConfig
		}
		switch name {
		case "front-office", "middle-office", "matching-engine", "back-office":
		default:
			return nil, errConfig
		}
	}
	tokens := map[string]string{}
	for nome, token := range cfg.FinancialTokens {
		conhecido := false
		for _, c := range leiturasFinanceiras {
			if c.nome == nome {
				conhecido = true
			}
		}
		if !conhecido {
			return nil, errConfig
		}
		tokens[nome] = token
	}
	cfg.FinancialTokens = tokens
	return &Server{Instance: identifier(), cfg: cfg, runs: map[string]*run{}, nonces: map[string]int64{}, totals: map[string]*Counts{}, hist: map[string][]int64{}}, nil
}
func (s *Server) Close() {
	s.mu.Lock()
	s.closed = true
	r := s.active
	if r != nil {
		r.Estado = "ENCERRANDO"
		r.cancel()
	}
	s.mu.Unlock()
	if r != nil {
		<-r.done
	}
}
func (s *Server) Handler() http.Handler { return http.HandlerFunc(s.handle) }

// limited aplica janela fixa de um segundo com teto de chamadas administrativas.
func (s *Server) limited(window *time.Time, calls *int, now time.Time) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if now.Sub(*window) >= time.Second {
		*window = now
		*calls = 0
	}
	*calls++
	return *calls > adminLimit
}
func (s *Server) handle(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	allowed := false
	for _, h := range s.cfg.Hosts {
		if r.Host == h {
			allowed = true
		}
	}
	if !allowed || r.Header.Get("Origin") != "" {
		problem(w, 403, "ORIGEM_RECUSADA")
		return
	}
	now := time.Now()
	// Autenticação precede o limite administrativo: chamadas anônimas usam outro orçamento
	// e não bloqueiam o operador legítimo.
	auth := r.Header.Get("Authorization")
	c, e := parseJWT(strings.TrimPrefix(auth, "Bearer "), s.cfg.TokenKey, "prospero-local", "prospero-carga", now)
	if e == nil && (c.Iat == 0 || c.Exp-c.Iat > maxControlTTL) {
		e = errConfig
	}
	if !strings.HasPrefix(auth, "Bearer ") || e != nil {
		if s.limited(&s.anonWindow, &s.anonCalls, now) {
			w.Header().Set("Retry-After", "1")
			problem(w, 429, "LIMITE_ADMINISTRATIVO")
			return
		}
		w.Header().Set("WWW-Authenticate", "Bearer")
		problem(w, 401, "AUTENTICACAO_INVALIDA")
		return
	}
	if s.limited(&s.adminWindow, &s.adminCalls, now) {
		w.Header().Set("Retry-After", "1")
		problem(w, 429, "LIMITE_ADMINISTRATIVO")
		return
	}
	scope := false
	for _, sc := range strings.Fields(c.Scope) {
		if sc == "carga:controle" {
			scope = true
		}
	}
	if !scope {
		problem(w, 403, "PERMISSAO_INSUFICIENTE")
		return
	}
	if r.URL.RawQuery != "" {
		problem(w, 400, "QUERY_INVALIDA")
		return
	}
	path := r.URL.Path
	if path == "/api/v1/sistema" && r.Method == "GET" {
		respond(w, 200, map[string]any{"instancia": s.Instance, "servico": "api-carga", "etapa": "TECNICA"})
		return
	}
	if path == "/openapi.json" && r.Method == "GET" {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(200)
		_, _ = w.Write(openAPI)
		return
	}
	if path == "/api/v1/execucoes-carga" {
		if r.Method != "POST" {
			w.Header().Set("Allow", "POST")
			problem(w, 405, "METODO_INVALIDO")
			return
		}
		s.start(w, r)
		return
	}
	const prefix = "/api/v1/execucoes-carga/"
	if strings.HasPrefix(path, prefix) {
		rest := strings.TrimPrefix(path, prefix)
		parts := strings.Split(rest, "/")
		if len(parts) > 2 || len(parts[0]) != 32 {
			problem(w, 404, "EXECUCAO_AUSENTE")
			return
		}
		cancel := len(parts) == 2 && parts[1] == "cancelamentos"
		if len(parts) == 2 && !cancel {
			problem(w, 404, "ROTA_AUSENTE")
			return
		}
		if (!cancel && r.Method != "GET") || (cancel && r.Method != "POST") {
			if cancel {
				w.Header().Set("Allow", "POST")
			} else {
				w.Header().Set("Allow", "GET")
			}
			problem(w, 405, "METODO_INVALIDO")
			return
		}
		s.mu.Lock()
		s.pruneLocked(time.Now())
		v, ok := s.runs[parts[0]]
		if !ok {
			s.mu.Unlock()
			problem(w, 404, "EXECUCAO_AUSENTE")
			return
		}
		code := 200
		if cancel && !terminal(v.Estado) {
			v.Estado = "ENCERRANDO"
			v.cancel()
			code = 202
		}
		report := s.snapshotLocked(v)
		s.mu.Unlock()
		respond(w, code, report)
		return
	}
	problem(w, 404, "ROTA_AUSENTE")
}
func (s *Server) start(w http.ResponseWriter, r *http.Request) {
	content, _, e := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if e != nil || content != "application/json" {
		problem(w, 415, "CONTEUDO_INVALIDO")
		return
	}
	b, e := io.ReadAll(http.MaxBytesReader(w, r.Body, 16384))
	if e != nil {
		problem(w, 413, "CORPO_EXCESSIVO")
		return
	}
	var req struct {
		Plano       Plan    `json:"plano"`
		Autorizacao *string `json:"autorizacao,omitempty"`
	}
	if strictJSON(b, &req) != nil {
		problem(w, 400, "JSON_INVALIDO")
		return
	}
	if req.Plano.Validate() != nil {
		problem(w, 422, "PLANO_INVALIDO")
		return
	}
	credencial, err := s.credencialLeitura(req.Plano, time.Now())
	if err != nil {
		problem(w, 422, "CREDENCIAL_FINANCEIRA_INDISPONIVEL")
		return
	}
	target, ok := s.cfg.Targets[req.Plano.Servico]
	if !ok {
		problem(w, 422, "ALVO_INDISPONIVEL")
		return
	}
	authorization := ""
	if req.Autorizacao != nil {
		authorization = *req.Autorizacao
	}
	preflightHeader := r.Header.Get("X-Prospero-Preflight")
	if preflightHeader != "" {
		if req.Autorizacao != nil || len(preflightHeader) > 4096 {
			problem(w, 400, "PREFLIGHT_DUPLICADO")
			return
		}
		authorization = preflightHeader
	}
	now := time.Now()
	a, e := parseJWT(authorization, s.cfg.PreflightKey, "prospero-preflight", s.Instance, now)
	if e != nil || a.Iat == 0 || a.Exp-a.Iat > 30 || a.Exp <= a.Iat || len(a.Nonce) < 16 || len(a.Nonce) > 64 || a.Plano != PlanHash(req.Plano) || a.MemoriaDisponivelMiB < 1024 || a.DiscoLivreMiB < 1024 || a.CPUs < 1 || !a.Telemetria {
		problem(w, 503, "PREFLIGHT_INVALIDO")
		return
	}
	s.mu.Lock()
	s.pruneLocked(now)
	if s.closed {
		s.mu.Unlock()
		problem(w, 503, "ENCERRANDO")
		return
	}
	if s.active != nil {
		s.mu.Unlock()
		problem(w, 409, "EXECUCAO_ATIVA")
		return
	}
	if _, used := s.nonces[a.Nonce]; used {
		s.mu.Unlock()
		problem(w, 503, "PREFLIGHT_REUTILIZADO")
		return
	}
	if s.cfg.Cooldown > 0 && now.Sub(s.lastStart) < s.cfg.Cooldown {
		s.mu.Unlock()
		w.Header().Set("Retry-After", "1")
		problem(w, 429, "INTERVALO_EXECUCOES")
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	v := &run{Report: Report{ID: identifier(), Instancia: s.Instance, Estado: "AGUARDANDO", Plano: req.Plano, Inicio: now.UTC(), Ambiente: map[string]any{"go": runtime.Version(), "memoriaDisponivelMiB": a.MemoriaDisponivelMiB, "discoLivreMiB": a.DiscoLivreMiB, "cpusHost": a.CPUs, "preflightEm": time.Unix(a.Iat, 0).UTC(), "fonte": "operador-host-assinado", "recursosProcesso": "controle+gerador"}, Populacao: "nearest-rank; chamadas finalizadas de ofertas programadas apos aquecimento, incluindo erros; duracao HTTP sem atraso de agendamento"}, cancel: cancel, done: make(chan struct{}), credencial: credencial}
	if req.Plano.Modo == "ABERTO" {
		total := 0
		for _, st := range req.Plano.Estagios {
			total += (st.DuracaoMs*st.RPS + 999) / 1000
		}
		v.OfertasPrevistas = &total
	}
	s.runs[v.ID] = v
	s.order = append(s.order, v.ID)
	s.active = v
	s.nonces[a.Nonce] = a.Exp
	s.lastStart = now
	report := s.snapshotLocked(v)
	s.mu.Unlock()
	go s.execute(ctx, v, target)
	w.Header().Set("Location", "/api/v1/execucoes-carga/"+v.ID)
	respond(w, 202, report)
}
func (s *Server) snapshotLocked(r *run) Report {
	p := r.Report
	p.Quantis = quantiles(r.samples)
	p.Janelas = append([]Window(nil), r.Janelas...)
	for i := range p.Janelas {
		p.Janelas[i].Quantis = quantiles(r.Janelas[i].samples)
		end := time.Now()
		if r.Fim != nil {
			end = *r.Fim
		}
		plannedEnd := p.Janelas[i].Inicio.Add(time.Duration(p.Janelas[i].DuracaoMs) * time.Millisecond)
		if end.After(plannedEnd) {
			end = plannedEnd
		}
		observed := end.Sub(p.Janelas[i].Inicio).Milliseconds()
		if observed < 0 {
			observed = 0
		}
		p.Janelas[i].DuracaoObservadaMs = int(observed)
	}
	return p
}
func (s *Server) pruneLocked(now time.Time) {
	for k, exp := range s.nonces {
		if exp <= now.Unix() {
			delete(s.nonces, k)
		}
	}
	total := 0
	for _, id := range s.order {
		b, _ := json.Marshal(s.runs[id].Report)
		total += len(b)
	}
	kept := s.order[:0]
	for _, id := range s.order {
		r := s.runs[id]
		expired := r.Fim != nil && now.Sub(*r.Fim) > 20*time.Minute
		if terminal(r.Estado) && (expired || len(s.runs) > 20 || total > 4*1024*1024) {
			b, _ := json.Marshal(r.Report)
			total -= len(b)
			delete(s.runs, id)
		} else {
			kept = append(kept, id)
		}
	}
	s.order = kept
}
func respond(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
func problem(w http.ResponseWriter, status int, code string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"type": "urn:prospero:carga:" + code, "title": http.StatusText(status), "status": status, "detail": "Operacao de carga recusada.", "instance": "urn:ocorrencia:" + identifier(), "codigo": code})
}
