package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"sync"
	"time"
)

const (
	tetoResposta  = 1 << 20
	paginasMaximo = LimiteOrdensSessao/100 + 1
)

var (
	errIndisponivel = errors.New("front office indisponivel")
	errCredencial   = errors.New("FO recusou a credencial do robo")
)

// Cliente fala só com o FO, com o token da CONTA-004; não segue redirecionamentos nem lê respostas ilimitadas.
type Cliente struct {
	base  string
	token string
	http  *http.Client
}

func NovoCliente(base, token string) (*Cliente, error) {
	u, e := url.Parse(base)
	if e != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.Path != "" || u.RawQuery != "" || u.User != nil {
		return nil, errors.New("ROBO_FO_URL precisa ser http(s)://host[:porta]")
	}
	if len(token) < 20 || bytes.ContainsAny([]byte(token), " \r\n\t") {
		return nil, errors.New("token do robo ausente ou malformado")
	}
	return &Cliente{base: base, token: token, http: &http.Client{Timeout: 5 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}, nil
}

func (c *Cliente) pedir(ctx context.Context, metodo, caminho string, corpo []byte, chave string) (int, []byte, error) {
	r, e := http.NewRequestWithContext(ctx, metodo, c.base+caminho, bytes.NewReader(corpo))
	if e != nil {
		return 0, nil, e
	}
	r.Header.Set("Authorization", "Bearer "+c.token)
	r.Header.Set("Accept", "application/json")
	if corpo != nil {
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("X-Idempotency-Key", chave)
	}
	resposta, e := c.http.Do(r)
	if e != nil {
		return 0, nil, errIndisponivel
	}
	defer resposta.Body.Close()
	dados, e := io.ReadAll(io.LimitReader(resposta.Body, tetoResposta+1))
	if e != nil || len(dados) > tetoResposta {
		return 0, nil, errIndisponivel
	}
	return resposta.StatusCode, dados, nil
}

// Observacao é o que o FO mostra da CONTA-004: ordens da sessão e se há oferta viva de sessão anterior.
type Observacao struct {
	Ordens          []Ordem
	AnterioresVivas bool
}

// OrdensDaSessao percorre as páginas (mais recentes primeiro) dentro do orçamento de páginas; ordens de sessão
// anterior só são inspecionadas para detectar ofertas ainda vivas.
func (c *Cliente) OrdensDaSessao(ctx context.Context, sessao string) (Observacao, error) {
	var obs Observacao
	cursor := ""
	for pagina := 0; pagina < paginasMaximo; pagina++ {
		caminho := "/api/v1/ordens?limite=100"
		if cursor != "" {
			caminho += "&cursor=" + url.QueryEscape(cursor)
		}
		status, dados, e := c.pedir(ctx, http.MethodGet, caminho, nil, "")
		if status == http.StatusUnauthorized || status == http.StatusForbidden {
			return obs, errCredencial
		}
		if e != nil || status != http.StatusOK {
			return obs, errIndisponivel
		}
		var p struct {
			Ordens        []Ordem `json:"ordens"`
			ProximoCursor *string `json:"proximoCursor"`
		}
		if json.Unmarshal(dados, &p) != nil {
			return obs, errIndisponivel
		}
		for _, o := range p.Ordens {
			switch {
			case o.DataSessao == sessao:
				obs.Ordens = append(obs.Ordens, o)
			case o.DataSessao < sessao && vivos[o.Estado]:
				obs.AnterioresVivas = true
			}
		}
		if p.ProximoCursor == nil || obs.AnterioresVivas {
			return obs, nil
		}
		cursor = *p.ProximoCursor
	}
	return obs, nil
}

// Resultado de um envio: aceito (202), recusado pelo contrato (4xx sem ordem gravada) ou limite da sessão (429).
type Resultado int

const (
	Aceito Resultado = iota
	Recusado
	LimiteSessao
)

func (c *Cliente) Enviar(ctx context.Context, e Envio) (Resultado, error) {
	corpo, _ := json.Marshal(map[string]any{"ticker": e.Ticker, "lado": e.Lado, "tipo": "LIMIT", "validade": "DIA",
		"quantidade": e.Quantidade, "precoLimite": e.Preco})
	status, _, erro := c.pedir(ctx, http.MethodPost, "/api/v1/ordens", corpo, e.Chave)
	switch {
	case erro != nil || status >= 500:
		return Recusado, errIndisponivel
	case status == http.StatusAccepted:
		return Aceito, nil
	case status == http.StatusTooManyRequests:
		return LimiteSessao, nil
	case status == http.StatusUnauthorized || status == http.StatusForbidden:
		return Recusado, errCredencial
	default:
		return Recusado, nil
	}
}

// Situacao é o estado observável do robô (GET /estado), sem dados de conta além do identificador.
type Situacao struct {
	Estado          string `json:"estado"`
	Conta           string `json:"conta"`
	Sessao          string `json:"sessao,omitempty"`
	Ciclos          int64  `json:"ciclos"`
	Enviadas        int64  `json:"enviadas"`
	Recusadas       int64  `json:"recusadas"`
	NiveisRetirados int    `json:"niveisRetirados"`
	UltimoCiclo     string `json:"ultimoCiclo,omitempty"`
	UltimoErro      string `json:"ultimoErro,omitempty"`
}

type Robo struct {
	cfg     Config
	cliente interface {
		OrdensDaSessao(context.Context, string) (Observacao, error)
		Enviar(context.Context, Envio) (Resultado, error)
	}
	agora func() time.Time
	// Níveis recusados de forma síncrona na sessão corrente; zerados na virada da sessão.
	recusados map[string]bool
	mu        sync.Mutex
	sit       Situacao
}

func (r *Robo) Situacao() Situacao {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.sit
}

func (r *Robo) atualizar(f func(*Situacao)) {
	r.mu.Lock()
	defer r.mu.Unlock()
	f(&r.sit)
}

// Ciclo observa o FO, planeja e envia. ESGOTADO vale até a virada da sessão; CREDENCIAL_RECUSADA até reiniciar.
func (r *Robo) Ciclo(ctx context.Context) {
	momento := r.agora()
	sessao := momento.In(saoPaulo).Format(time.DateOnly)
	anterior := r.Situacao()
	if anterior.Estado == EstadoCredencialRecusada || (anterior.Estado == EstadoEsgotado && anterior.Sessao == sessao) {
		return
	}
	if anterior.Sessao != sessao {
		r.recusados = map[string]bool{}
	}
	obs, e := r.cliente.OrdensDaSessao(ctx, sessao)
	if e != nil {
		estado := EstadoIndisponivel
		if errors.Is(e, errCredencial) {
			estado = EstadoCredencialRecusada
		}
		r.atualizar(func(s *Situacao) { s.Estado, s.UltimoErro = estado, e.Error() })
		return
	}
	plano := Plano{Estado: EstadoAguardandoEncerramento}
	if !obs.AnterioresVivas {
		plano = Planejar(r.cfg, sessao, obs.Ordens, r.recusados)
	}
	estado, erro := plano.Estado, ""
	var enviadas, recusadas int64
envios:
	for _, envio := range plano.Envios {
		resultado, e := r.cliente.Enviar(ctx, envio)
		switch {
		case errors.Is(e, errCredencial):
			estado, erro = EstadoCredencialRecusada, e.Error()
			break envios
		case e != nil:
			estado, erro = EstadoIndisponivel, e.Error()
			break envios
		case resultado == LimiteSessao:
			estado = EstadoEsgotado
			break envios
		case resultado == Aceito:
			enviadas++
		default:
			recusadas++
			r.recusados[Nivel(envio.Ticker, envio.Lado, envio.Preco)] = true
		}
	}
	r.atualizar(func(s *Situacao) {
		s.Estado, s.Sessao, s.UltimoErro, s.NiveisRetirados = estado, sessao, erro, plano.Retirados
		s.Ciclos++
		s.Enviadas += enviadas
		s.Recusadas += recusadas
		s.UltimoCiclo = momento.UTC().Format(time.RFC3339)
	})
}

func inteiroEnv(valor string, padrao int64) (int64, error) {
	if valor == "" {
		return padrao, nil
	}
	return strconv.ParseInt(valor, 10, 64)
}
