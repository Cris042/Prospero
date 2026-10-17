package main

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"math"
	"sort"
	"time"
)

type Stage struct {
	DuracaoMs int `json:"duracaoMs"`
	RPS       int `json:"rps"`
	VUs       int `json:"vus"`
}
type Plan struct {
	Servico          string  `json:"servico"`
	Modo             string  `json:"modo"`
	Estagios         []Stage `json:"estagios"`
	AquecimentoMs    int     `json:"aquecimentoMs"`
	Concorrencia     int     `json:"concorrencia"`
	MaxChamadas      int     `json:"maxChamadas"`
	TimeoutMs        int     `json:"timeoutMs"`
	MaxRespostaBytes int     `json:"maxRespostaBytes"`
}

func (p Plan) Validate() error {
	if p.Modo != "ABERTO" && p.Modo != "FECHADO" {
		return errors.New("modo")
	}
	switch p.Servico {
	case "front-office", "middle-office", "matching-engine", "back-office":
	default:
		return errors.New("servico")
	}
	if len(p.Estagios) < 1 || len(p.Estagios) > 5 || p.Concorrencia < 1 || p.Concorrencia > 32 || p.MaxChamadas < 1 || p.MaxChamadas > 6000 || p.TimeoutMs < 1 || p.TimeoutMs > 2000 || p.MaxRespostaBytes < 1 || p.MaxRespostaBytes > 65536 {
		return errors.New("limites")
	}
	total := 0
	for _, st := range p.Estagios {
		if st.DuracaoMs < 100 || st.DuracaoMs > 60000 || st.RPS < 1 || st.RPS > 100 || st.VUs < 0 || st.VUs > 16 || (p.Modo == "ABERTO" && st.VUs != 0) || (p.Modo == "FECHADO" && (st.VUs < 1 || st.VUs > p.Concorrencia)) {
			return errors.New("estagios")
		}
		total += st.DuracaoMs
	}
	if total > 60000 || p.AquecimentoMs < 0 || p.AquecimentoMs >= total {
		return errors.New("duracao")
	}
	return nil
}
func PlanHash(p Plan) string {
	b, _ := json.Marshal(p)
	var v map[string]any
	_ = json.Unmarshal(b, &v)
	b, _ = json.Marshal(v)
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}
func identifier() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic("fonte aleatoria indisponivel")
	}
	return hex.EncodeToString(b)
}

type Counts struct {
	Oferecidas       int   `json:"oferecidas"`
	Iniciadas        int   `json:"iniciadas"`
	NaoIniciadas     int   `json:"naoIniciadas"`
	Descartadas      int   `json:"descartadas"`
	Interrompidas    int   `json:"interrompidas"`
	Finalizadas      int   `json:"finalizadas"`
	EmVoo            int   `json:"emVoo"`
	RespostasHTTP    int   `json:"respostasHTTP"`
	ErrosHTTP        int   `json:"errosHTTP"`
	Timeouts         int   `json:"timeouts"`
	FalhasTransporte int   `json:"falhasTransporte"`
	CanceladasEmVoo  int   `json:"canceladasEmVoo"`
	LimitesResposta  int   `json:"limitesResposta"`
	BytesLidos       int64 `json:"bytesLidos"`
}
type Quantiles struct {
	Amostras int   `json:"amostras"`
	P50Us    int64 `json:"p50Us"`
	P95Us    int64 `json:"p95Us"`
	P99Us    int64 `json:"p99Us"`
	MaximoUs int64 `json:"maximoUs"`
}

func quantiles(samples []int64) *Quantiles {
	if len(samples) == 0 {
		return nil
	}
	v := append([]int64(nil), samples...)
	sort.Slice(v, func(i, j int) bool { return v[i] < v[j] })
	at := func(q float64) int64 { return v[int(math.Ceil(q*float64(len(v))))-1] }
	return &Quantiles{len(v), at(.5), at(.95), at(.99), v[len(v)-1]}
}

type Window struct {
	DuracaoObservadaMs int        `json:"duracaoObservadaMs"`
	Estagio            int        `json:"estagio"`
	Fase               string     `json:"fase"`
	Inicio             time.Time  `json:"inicio"`
	DuracaoMs          int        `json:"duracaoMs"`
	Contagens          Counts     `json:"contagens"`
	Quantis            *Quantiles `json:"quantis"`
	samples            []int64
}
type Report struct {
	OfertasPrevistas *int           `json:"ofertasPrevistas"`
	ID               string         `json:"id"`
	Instancia        string         `json:"instancia"`
	Estado           string         `json:"estado"`
	Plano            Plan           `json:"plano"`
	Inicio           time.Time      `json:"inicio"`
	Fim              *time.Time     `json:"fim"`
	Contagens        Counts         `json:"contagens"`
	Medicao          Counts         `json:"medicao"`
	Quantis          *Quantiles     `json:"quantis"`
	AtrasoMaximoUs   int64          `json:"atrasoMaximoUs"`
	Janelas          []Window       `json:"janelas"`
	Motivo           string         `json:"motivo"`
	Ambiente         map[string]any `json:"ambiente"`
	Populacao        string         `json:"populacao"`
}

func terminal(s string) bool { return s == "CONCLUIDA" || s == "CANCELADA" || s == "FALHA" }
