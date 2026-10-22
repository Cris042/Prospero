package main

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"
	_ "time/tzdata"
)

var saoPaulo = carregarFuso()

func carregarFuso() *time.Location {
	l, e := time.LoadLocation("America/Sao_Paulo")
	if e != nil {
		panic("fuso America/Sao_Paulo indisponivel")
	}
	return l
}

func configurar() (Config, *Cliente, time.Duration, error) {
	ativos, e := lerAtivos(valorOu(os.Getenv("ROBO_ATIVOS"), "ALFA3:10.00:0.01:100"))
	if e != nil {
		return Config{}, nil, 0, e
	}
	niveis, e1 := inteiroEnv(os.Getenv("ROBO_NIVEIS"), 3)
	distancia, e2 := inteiroEnv(os.Getenv("ROBO_DISTANCIA_TICKS"), 5)
	passo, e3 := inteiroEnv(os.Getenv("ROBO_PASSO_TICKS"), 1)
	intervalo, e4 := inteiroEnv(os.Getenv("ROBO_INTERVALO_MS"), 2000)
	if errors.Join(e1, e2, e3, e4) != nil || intervalo < 500 || intervalo > 60000 || niveis > 10 {
		return Config{}, nil, 0, errors.New("ROBO_NIVEIS, ROBO_DISTANCIA_TICKS, ROBO_PASSO_TICKS ou ROBO_INTERVALO_MS (500-60000) invalidos")
	}
	cfg := Config{Niveis: int(niveis), DistanciaTicks: distancia, PassoTicks: passo, Ativos: ativos}
	if e := cfg.validar(); e != nil {
		return Config{}, nil, 0, e
	}
	token, e := os.ReadFile(valorOu(os.Getenv("ROBO_TOKEN_FILE"), "/run/secrets/robo_token"))
	if e != nil {
		return Config{}, nil, 0, errors.New("token do robo indisponivel")
	}
	cliente, e := NovoCliente(valorOu(os.Getenv("ROBO_FO_URL"), "http://front-office:8080"), strings.TrimSpace(string(token)))
	return cfg, cliente, time.Duration(intervalo) * time.Millisecond, e
}

func valorOu(v, padrao string) string {
	if v == "" {
		return padrao
	}
	return v
}

func (r *Robo) Handler() http.Handler {
	m := http.NewServeMux()
	m.HandleFunc("GET /estado", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		_ = json.NewEncoder(w).Encode(r.Situacao())
	})
	m.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	return m
}

func main() {
	if len(os.Args) == 2 && os.Args[1] == "--healthcheck" {
		c := http.Client{Timeout: time.Second}
		resposta, e := c.Get("http://127.0.0.1:8080/healthz")
		if e != nil || resposta.StatusCode != http.StatusOK {
			os.Exit(1)
		}
		return
	}
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	cfg, cliente, intervalo, e := configurar()
	if e != nil {
		log.Error("configuracao invalida", "erro", e.Error())
		os.Exit(1)
	}
	robo := &Robo{cfg: cfg, cliente: cliente, agora: time.Now, sit: Situacao{Estado: EstadoIniciando, Conta: "CONTA-004"}}
	servidor := &http.Server{Addr: ":8080", Handler: robo.Handler(), ReadHeaderTimeout: 2 * time.Second, ReadTimeout: 3 * time.Second,
		WriteTimeout: 3 * time.Second, IdleTimeout: 5 * time.Second, MaxHeaderBytes: 8192}
	go func() {
		if e := servidor.ListenAndServe(); e != nil && !errors.Is(e, http.ErrServerClosed) {
			log.Error("porta de estado indisponivel")
			os.Exit(1)
		}
	}()
	ctx, parar := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer parar()
	relogio := time.NewTicker(intervalo)
	defer relogio.Stop()
	anterior := ""
	for {
		robo.Ciclo(ctx)
		if s := robo.Situacao(); s.Estado != anterior {
			log.Info("estado do robo", "estado", s.Estado, "sessao", s.Sessao, "enviadas", s.Enviadas, "recusadas", s.Recusadas, "erro", s.UltimoErro)
			anterior = s.Estado
		}
		select {
		case <-ctx.Done():
			desligar, cancelar := context.WithTimeout(context.Background(), 3*time.Second)
			_ = servidor.Shutdown(desligar)
			cancelar()
			return
		case <-relogio.C:
		}
	}
}
