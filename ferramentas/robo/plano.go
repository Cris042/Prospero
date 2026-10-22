package main

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
)

// Núcleo puro do robô formador (RF05d, ADR-013): mesmo estado observado no FO gera o mesmo plano, sem relógio nem I/O.

// LimiteOrdensSessao espelha o teto do FO por conta e sessão (429 LIMITE_ORDENS).
const LimiteOrdensSessao = 1000

const (
	EstadoIniciando    = "INICIANDO"
	EstadoAtivo        = "ATIVO"
	EstadoEsgotado     = "ESGOTADO"
	EstadoIndisponivel = "FO_INDISPONIVEL"
	// Ofertas DIA de sessão anterior ainda vivas: não dobra exposição/reservas antes do encerramento manual.
	EstadoAguardandoEncerramento = "AGUARDANDO_ENCERRAMENTO"
	// 401/403: token expirado, renovado ou revogado; terminal até reiniciar com a credencial nova.
	EstadoCredencialRecusada = "CREDENCIAL_RECUSADA"
)

// Rejeições que indicam recurso ou limite da CONTA-004 esgotado: sem recarga até a RF08, o robô para de forma limpa.
var motivosEsgotamento = map[string]bool{"SALDO_INSUFICIENTE": true, "CUSTODIA_INSUFICIENTE": true, "LIMITE_ORDENS_ATIVAS": true}

var vivos = map[string]bool{"RECEBIDA": true, "RESERVADA": true, "ABERTA": true, "PARCIALMENTE_EXECUTADA": true}

type Ativo struct {
	Ticker     string
	Referencia int64 // centavos
	Tick       int64 // centavos
	Quantidade int64
}

type Config struct {
	Niveis         int
	DistanciaTicks int64
	PassoTicks     int64
	Ativos         []Ativo
}

// Ordem é o subconjunto do OrdemResponseDTO do FO que o plano usa.
type Ordem struct {
	Ticker         string `json:"ticker"`
	Lado           string `json:"lado"`
	Tipo           string `json:"tipo"`
	Validade       string `json:"validade"`
	PrecoLimite    string `json:"precoLimite"`
	Estado         string `json:"estado"`
	DataSessao     string `json:"dataSessao"`
	MotivoRejeicao string `json:"motivoRejeicao"`
}

type Envio struct {
	Chave      string
	Ticker     string
	Lado       string
	Preco      string
	Quantidade int64
}

type Plano struct {
	Envios    []Envio
	Estado    string
	Retirados int
}

type nivel struct {
	ticker, lado, rotulo, preco string
	quantidade                  int64
}

// Nivel identifica um nível pela combinação ativo/lado/preço, a mesma usada pelas recusas síncronas.
func Nivel(ticker, lado, preco string) string { return ticker + "|" + lado + "|" + preco }

func (c Config) niveis() []nivel {
	var todos []nivel
	for _, a := range c.Ativos {
		for _, lado := range []string{"COMPRA", "VENDA"} {
			for k := 0; k < c.Niveis; k++ {
				desvio := (c.DistanciaTicks + int64(k)*c.PassoTicks) * a.Tick
				preco, prefixo := a.Referencia-desvio, "C"
				if lado == "VENDA" {
					preco, prefixo = a.Referencia+desvio, "V"
				}
				// O preço entra na chave: mudar a grade na mesma sessão não reutiliza chave com corpo diferente (409).
				todos = append(todos, nivel{a.Ticker, lado, prefixo + strconv.FormatInt(preco, 10), formatar(preco), a.Quantidade})
			}
		}
	}
	return todos
}

// Planejar repõe apenas níveis sem ordem viva cuja última ordem foi executada (ou que nunca tiveram ordem).
// A geração da chave é o número de execuções do nível na sessão: reinício reproduz as mesmas chaves e o FO
// devolve a ordem já registrada, sem duplicar. `recusados` são níveis que o FO recusou de forma síncrona (4xx sem
// ordem gravada), retirados na sessão como os rejeitados.
func Planejar(c Config, sessao string, ordens []Ordem, recusados map[string]bool) Plano {
	plano := Plano{Estado: EstadoAtivo}
	usadas := 0
	type historico struct {
		vivo, retirado bool
		execucoes      int
	}
	porNivel := map[string]*historico{}
	for _, o := range ordens {
		if o.DataSessao != sessao {
			continue
		}
		usadas++
		if o.Estado == "REJEITADA" && motivosEsgotamento[o.MotivoRejeicao] {
			plano.Estado = EstadoEsgotado
		}
		if o.Tipo != "LIMIT" || o.Validade != "DIA" {
			continue
		}
		chave := Nivel(o.Ticker, o.Lado, o.PrecoLimite)
		h := porNivel[chave]
		if h == nil {
			h = &historico{}
			porNivel[chave] = h
		}
		switch {
		case vivos[o.Estado]:
			h.vivo = true
		case o.Estado == "EXECUTADA":
			h.execucoes++
		default:
			h.retirado = true
		}
	}
	if plano.Estado == EstadoEsgotado {
		return plano
	}
	compacta := strings.ReplaceAll(sessao, "-", "")
	for _, n := range c.niveis() {
		h := porNivel[Nivel(n.ticker, n.lado, n.preco)]
		if (h != nil && h.retirado && !h.vivo) || (h == nil && recusados[Nivel(n.ticker, n.lado, n.preco)]) {
			plano.Retirados++
			continue
		}
		if h != nil && h.vivo {
			continue
		}
		if usadas >= LimiteOrdensSessao {
			plano.Estado = EstadoEsgotado
			break
		}
		geracao := 0
		if h != nil {
			geracao = h.execucoes
		}
		plano.Envios = append(plano.Envios, Envio{Chave: fmt.Sprintf("robo-%s-%s-%s-%d", compacta, n.ticker, n.rotulo, geracao),
			Ticker: n.ticker, Lado: n.lado, Preco: n.preco, Quantidade: n.quantidade})
		usadas++
	}
	if usadas >= LimiteOrdensSessao {
		plano.Estado = EstadoEsgotado
	}
	return plano
}

func (c Config) validar() error {
	if c.Niveis < 1 || c.Niveis > 10 || c.DistanciaTicks < 1 || c.DistanciaTicks > 100 || c.PassoTicks < 1 || c.PassoTicks > 100 || len(c.Ativos) == 0 {
		return errors.New("niveis 1-10, distancia e passo 1-100 e ao menos um ativo")
	}
	for _, a := range c.Ativos {
		if a.Referencia-(c.DistanciaTicks+int64(c.Niveis-1)*c.PassoTicks)*a.Tick <= 0 {
			return fmt.Errorf("compra de %s ficaria com preço não positivo", a.Ticker)
		}
	}
	return nil
}

// lerAtivos interpreta "TICKER:REFERENCIA:TICK:QUANTIDADE[,...]"; o FO continua validando lote, tick e túnel.
func lerAtivos(texto string) ([]Ativo, error) {
	var ativos []Ativo
	vistos := map[string]bool{}
	for _, item := range strings.Split(texto, ",") {
		partes := strings.Split(item, ":")
		if len(partes) != 4 || !tickerValido(partes[0]) || vistos[partes[0]] {
			return nil, fmt.Errorf("ativo inválido: %q", item)
		}
		referencia, ok1 := centavosDe(partes[1])
		tick, ok2 := centavosDe(partes[2])
		quantidade, e := strconv.ParseInt(partes[3], 10, 64)
		if !ok1 || !ok2 || e != nil || referencia <= 0 || tick <= 0 || quantidade < 1 || quantidade > 100000 {
			return nil, fmt.Errorf("ativo inválido: %q", item)
		}
		vistos[partes[0]] = true
		ativos = append(ativos, Ativo{Ticker: partes[0], Referencia: referencia, Tick: tick, Quantidade: quantidade})
	}
	return ativos, nil
}

func tickerValido(t string) bool {
	if len(t) < 4 || len(t) > 8 {
		return false
	}
	for _, r := range t {
		if (r < 'A' || r > 'Z') && (r < '0' || r > '9') {
			return false
		}
	}
	return true
}

// centavosDe aceita somente o formato canônico do FO com duas casas ("10.05"), sem ponto flutuante.
func centavosDe(texto string) (int64, bool) {
	inteiro, fracao, ok := strings.Cut(texto, ".")
	if !ok || len(fracao) != 2 || inteiro == "" || len(inteiro) > 12 {
		return 0, false
	}
	for _, r := range inteiro + fracao {
		if r < '0' || r > '9' {
			return 0, false
		}
	}
	i, _ := strconv.ParseInt(inteiro, 10, 64)
	f, _ := strconv.ParseInt(fracao, 10, 64)
	return i*100 + f, true
}

func formatar(centavos int64) string {
	return fmt.Sprintf("%d.%02d", centavos/100, centavos%100)
}
