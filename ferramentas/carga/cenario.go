package main

import (
	"encoding/base64"
	"errors"
	"strings"
	"time"
)

type cenarioLeitura struct {
	nome, servico, caminho, audiencia, grupo string
	conta                                    bool
}

var leiturasFinanceiras = []cenarioLeitura{
	{"ATIVOS", "front-office", "/api/v1/ativos", "prospero-front-office", "mercado:leitura", false},
	{"ORDENS", "front-office", "/api/v1/ordens?limite=20", "prospero-front-office", "ordens:leitura", true},
	{"CARTEIRA", "middle-office", "/api/v1/risco/carteira", "prospero-middle-office", "risco:leitura", true},
	{"POSICOES", "back-office", "/api/v1/posicoes", "prospero-back-office", "posicoes:leitura", true},
	{"NEGOCIOS", "back-office", "/api/v1/negocios?limite=20", "prospero-back-office", "razao:leitura", true},
	{"RAZAO", "back-office", "/api/v1/razao?limite=20", "prospero-back-office", "razao:leitura", true},
}

func (p Plan) leitura() (cenarioLeitura, error) {
	if p.Cenario == "" || p.Cenario == "IDENTIDADE" {
		return cenarioLeitura{nome: "IDENTIDADE", servico: p.Servico, caminho: "/api/v1/sistema"}, nil
	}
	for _, c := range leiturasFinanceiras {
		if c.nome == p.Cenario && c.servico == p.Servico {
			return c, nil
		}
	}
	return cenarioLeitura{}, errors.New("cenario")
}

func (p Plan) chaveMetrica() string {
	c, _ := p.leitura()
	return p.Servico + "|" + c.nome
}

func planosMetricas() []Plan {
	planos := []Plan{}
	for _, s := range []string{"front-office", "middle-office", "matching-engine", "back-office"} {
		planos = append(planos, Plan{Servico: s, Cenario: "IDENTIDADE"})
	}
	for _, c := range leiturasFinanceiras {
		planos = append(planos, Plan{Servico: c.servico, Cenario: c.nome})
	}
	return planos
}

type metadadosLeitura struct {
	Iss    string   `json:"iss"`
	Aud    string   `json:"aud"`
	Iat    int64    `json:"iat"`
	Exp    int64    `json:"exp"`
	Nbf    int64    `json:"nbf,omitempty"`
	Groups []string `json:"groups"`
	Sub    string   `json:"sub,omitempty"`
	Jti    string   `json:"jti,omitempty"`
}

// Filtro de configuração, sem chave financeira: somente o alvo autentica a assinatura.
func (s *Server) credencialLeitura(p Plan, agora time.Time) (string, error) {
	c, err := p.leitura()
	if err != nil {
		return "", err
	}
	if c.nome == "IDENTIDADE" {
		return "", nil
	}
	raw := s.cfg.FinancialTokens[c.nome]
	if len(raw) == 0 || len(raw) > 4096 {
		return "", errConfig
	}
	partes := strings.Split(raw, ".")
	if len(partes) != 3 {
		return "", errConfig
	}
	header, err := base64.RawURLEncoding.DecodeString(partes[0])
	if err != nil {
		return "", errConfig
	}
	var h struct {
		Alg string `json:"alg"`
		Typ string `json:"typ"`
	}
	if strictJSON(header, &h) != nil || h.Alg != "HS256" || h.Typ != "JWT" {
		return "", errConfig
	}
	assinatura, err := base64.RawURLEncoding.DecodeString(partes[2])
	if err != nil || len(assinatura) != 32 {
		return "", errConfig
	}
	payload, err := base64.RawURLEncoding.DecodeString(partes[1])
	if err != nil {
		return "", errConfig
	}
	var m metadadosLeitura
	if strictJSON(payload, &m) != nil || m.Iss != "prospero-local" || m.Aud != c.audiencia ||
		len(m.Groups) != 1 || m.Groups[0] != c.grupo || m.Iat <= 0 || m.Iat > agora.Unix() ||
		m.Exp <= m.Iat || m.Exp-m.Iat > 3600 || m.Nbf < 0 || m.Nbf > agora.Unix() {
		return "", errConfig
	}
	conhecida := m.Sub == "CONTA-001" || m.Sub == "CONTA-002" || m.Sub == "CONTA-003" || m.Sub == "CONTA-004"
	if (c.conta && !conhecida) || (m.Sub != "" && !conhecida) {
		return "", errConfig
	}
	prazo := time.Duration(p.TimeoutMs)*time.Millisecond + 5*time.Second
	for _, estagio := range p.Estagios {
		prazo += time.Duration(estagio.DuracaoMs) * time.Millisecond
	}
	if !time.Unix(m.Exp, 0).After(agora.Add(prazo)) {
		return "", errConfig
	}
	return raw, nil
}
