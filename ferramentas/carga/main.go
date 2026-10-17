package main

import (
	"context"
	_ "embed"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"
)

//go:embed openapi.json
var openAPI []byte
var errConfig = errors.New("configuracao invalida")

func main() {
	if len(os.Args) == 2 && os.Args[1] == "--healthcheck" {
		c := http.Client{Timeout: time.Second}
		r, e := c.Get("http://127.0.0.1:9091/healthz")
		if e != nil {
			os.Exit(1)
		}
		r.Body.Close()
		if r.StatusCode != 200 {
			os.Exit(1)
		}
		return
	}
	token, e := os.ReadFile(os.Getenv("CARGA_TOKEN_KEY_FILE"))
	if e != nil {
		slog.Error("chave de controle indisponivel")
		os.Exit(1)
	}
	preflight, e := os.ReadFile(os.Getenv("CARGA_PREFLIGHT_KEY_FILE"))
	if e != nil {
		slog.Error("chave de preflight indisponivel")
		os.Exit(1)
	}
	cfg := Config{TokenKey: []byte(strings.TrimSpace(string(token))), PreflightKey: []byte(strings.TrimSpace(string(preflight))), Hosts: strings.Split(os.Getenv("CARGA_HOSTS"), ","), Targets: map[string]string{}}
	for _, name := range []string{"front-office", "middle-office", "matching-engine", "back-office"} {
		cfg.Targets[name] = "http://" + name + ":8080"
	}
	s, e := NewServer(cfg)
	if e != nil {
		slog.Error("configuracao invalida")
		os.Exit(1)
	}
	control := &http.Server{Addr: ":8080", Handler: s.Handler(), ReadHeaderTimeout: 2 * time.Second, ReadTimeout: 3 * time.Second, WriteTimeout: 5 * time.Second, IdleTimeout: 5 * time.Second, MaxHeaderBytes: 8192, ErrorLog: slog.NewLogLogger(slog.NewTextHandler(io.Discard, nil), slog.LevelError)}
	metrics := &http.Server{Addr: ":9091", Handler: s.MetricsHandler(), ReadHeaderTimeout: 2 * time.Second, ReadTimeout: 3 * time.Second, WriteTimeout: 3 * time.Second, IdleTimeout: 5 * time.Second, MaxHeaderBytes: 8192}
	// Limit accepted connections independently of HTTP request limiting.
	lc, e := net.Listen("tcp", control.Addr)
	if e != nil {
		slog.Error("porta de controle indisponivel")
		os.Exit(1)
	}
	lm, e := net.Listen("tcp", metrics.Addr)
	if e != nil {
		lc.Close()
		slog.Error("porta de metricas indisponivel")
		os.Exit(1)
	}
	stopped := make(chan os.Signal, 1)
	signal.Notify(stopped, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		if e := control.Serve(&boundedListener{Listener: lc, slots: make(chan struct{}, 64)}); e != nil && !errors.Is(e, http.ErrServerClosed) {
			stopped <- syscall.SIGTERM
		}
	}()
	go func() {
		if e := metrics.Serve(&boundedListener{Listener: lm, slots: make(chan struct{}, 8)}); e != nil && !errors.Is(e, http.ErrServerClosed) {
			stopped <- syscall.SIGTERM
		}
	}()
	slog.Info("api-carga iniciada", "instancia", s.Instance)
	<-stopped
	s.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = control.Shutdown(ctx)
	_ = metrics.Shutdown(ctx)
	slog.Info("api-carga encerrada")
}

type boundedListener struct {
	net.Listener
	slots chan struct{}
}

func (l *boundedListener) Accept() (net.Conn, error) {
	for {
		c, e := l.Listener.Accept()
		if e != nil {
			return nil, e
		}
		select {
		case l.slots <- struct{}{}:
			return &boundedConn{Conn: c, release: func() { <-l.slots }}, nil
		default:
			c.Close()
		}
	}
}

type boundedConn struct {
	net.Conn
	release func()
	once    sync.Once
}

func (c *boundedConn) Close() error { e := c.Conn.Close(); c.once.Do(c.release); return e }
