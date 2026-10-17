package main

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"sync"
	"time"
)

var latencyBuckets = []int64{1000, 5000, 10000, 50000, 100000, 500000, 1000000, 2000000, 4000000}

func (s *Server) execute(ctx context.Context, r *run, target string) {
	p := r.Plano
	transport := &http.Transport{Proxy: nil, DialContext: (&net.Dialer{Timeout: time.Duration(p.TimeoutMs) * time.Millisecond, KeepAlive: 30 * time.Second}).DialContext, MaxConnsPerHost: p.Concorrencia, MaxIdleConns: p.Concorrencia, MaxIdleConnsPerHost: p.Concorrencia, IdleConnTimeout: 5 * time.Second, ResponseHeaderTimeout: time.Duration(p.TimeoutMs) * time.Millisecond, MaxResponseHeaderBytes: 8192, DisableCompression: true}
	client := &http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return errors.New("redirecionamento recusado") }}
	defer transport.CloseIdleConnections()
	start := time.Now()
	s.mu.Lock()
	r.Inicio = start.UTC()
	if r.Estado != "ENCERRANDO" {
		r.Estado = "EXECUTANDO"
	}
	s.mu.Unlock()
	var wg sync.WaitGroup
	slots := make(chan struct{}, p.Concorrencia)
	offset := time.Duration(0)
	offers := 0
	running := true
	for stageIndex, stage := range p.Estagios {
		if !running {
			break
		}
		duration := time.Duration(stage.DuracaoMs) * time.Millisecond
		interval := time.Second / time.Duration(stage.RPS)
		recovering := false
		for index := int64(0); index*int64(time.Second) < int64(duration)*int64(stage.RPS); index++ {
			tick := time.Duration(index * int64(time.Second) / int64(stage.RPS))
			if offers >= p.MaxChamadas {
				running = false
				break
			}
			due := start.Add(offset + tick)
			delay := time.Until(due)
			if delay > 0 {
				recovering = false
				timer := time.NewTimer(delay)
				select {
				case <-ctx.Done():
					timer.Stop()
					running = false
				case <-timer.C:
				}
				if !running {
					break
				}
			}
			if ctx.Err() != nil {
				running = false
				break
			}
			// Closed VUs offer a new iteration only when an execution slot is free.
			if p.Modo == "FECHADO" && len(slots) >= stage.VUs {
				continue
			}
			measured := offset+tick >= time.Duration(p.AquecimentoMs)*time.Millisecond
			phase := "MEDICAO"
			if !measured {
				phase = "AQUECIMENTO"
			}
			s.mu.Lock()
			wi := s.windowLocked(r, stageIndex, phase, start.Add(offset), stage.DuracaoMs)
			late := time.Since(due)
			if late.Microseconds() > r.AtrasoMaximoUs {
				r.AtrasoMaximoUs = late.Microseconds()
			}
			if late.Microseconds() > s.maxDelay {
				s.maxDelay = late.Microseconds()
			}
			s.countLocked(r, wi, measured, func(c *Counts) { c.Oferecidas++ })
			offers++
			if ctx.Err() != nil {
				s.countLocked(r, wi, measured, func(c *Counts) { c.NaoIniciadas++; c.Interrompidas++ })
				s.mu.Unlock()
				running = false
				break
			}
			if late >= interval {
				recovering = true
			}
			if recovering || len(slots) >= p.Concorrencia {
				s.countLocked(r, wi, measured, func(c *Counts) { c.NaoIniciadas++; c.Descartadas++ })
				s.mu.Unlock()
				continue
			}
			slots <- struct{}{}
			s.countLocked(r, wi, measured, func(c *Counts) { c.Iniciadas++; c.EmVoo++ })
			s.mu.Unlock()
			wg.Add(1)
			go func(windowIndex int, measure bool) {
				defer wg.Done()
				defer func() { <-slots }()
				s.request(ctx, r, client, target, windowIndex, measure)
			}(wi, measured)
		}
		if running && ctx.Err() == nil {
			remaining := time.Until(start.Add(offset + duration))
			if remaining > 0 {
				timer := time.NewTimer(remaining)
				select {
				case <-ctx.Done():
					timer.Stop()
					running = false
				case <-timer.C:
				}
			}
		}
		offset += duration
	}
	// End of offering and bounded drain are separate. Request contexts have <=2s deadline.
	s.mu.Lock()
	if !terminal(r.Estado) {
		r.Estado = "ENCERRANDO"
	}
	s.mu.Unlock()
	wg.Wait()
	now := time.Now().UTC()
	s.mu.Lock()
	if ctx.Err() != nil {
		r.Estado = "CANCELADA"
	} else {
		r.Estado = "CONCLUIDA"
	}
	r.Fim = &now
	if offers >= p.MaxChamadas {
		r.Motivo = "TETO_CHAMADAS"
	} else if ctx.Err() != nil {
		r.Motivo = "CANCELAMENTO"
	} else {
		r.Motivo = "CALENDARIO_ENCERRADO"
	}
	r.cancel()
	s.active = nil
	s.pruneLocked(now)
	close(r.done)
	s.mu.Unlock()
}
func (s *Server) windowLocked(r *run, stage int, phase string, start time.Time, duration int) int {
	for i := range r.Janelas {
		if r.Janelas[i].Estagio == stage+1 && r.Janelas[i].Fase == phase {
			return i
		}
	}
	end := start.Add(time.Duration(duration) * time.Millisecond)
	warmEnd := r.Inicio.Add(time.Duration(r.Plano.AquecimentoMs) * time.Millisecond)
	if phase == "AQUECIMENTO" && warmEnd.Before(end) {
		end = warmEnd
	}
	if phase == "MEDICAO" && warmEnd.After(start) {
		start = warmEnd
	}
	r.Janelas = append(r.Janelas, Window{Estagio: stage + 1, Fase: phase, Inicio: start.UTC(), DuracaoMs: int(end.Sub(start).Milliseconds())})
	return len(r.Janelas) - 1
}
func (s *Server) countLocked(r *run, wi int, measured bool, apply func(*Counts)) {
	apply(&r.Contagens)
	apply(&r.Janelas[wi].Contagens)
	if measured {
		apply(&r.Medicao)
	}
	c := s.totals[r.Plano.Servico]
	if c == nil {
		c = &Counts{}
		s.totals[r.Plano.Servico] = c
	}
	apply(c)
}
func (s *Server) request(parent context.Context, r *run, client *http.Client, target string, wi int, measured bool) {
	ctx, cancel := context.WithTimeout(parent, time.Duration(r.Plano.TimeoutMs)*time.Millisecond)
	defer cancel()
	start := time.Now()
	category := "transporte"
	httpError := false
	var bytesRead int64
	req, e := http.NewRequestWithContext(ctx, http.MethodGet, target+"/api/v1/sistema", nil)
	if e == nil {
		req.Header.Set("Accept", "application/json")
		// A non-idempotent marker is deliberately absent: Transport may retry a GET on a reused
		// broken connection before response. These are logical HTTP calls, not wire attempt counts.
		resp, err := client.Do(req)
		if err == nil {
			if resp.Header.Get("Content-Encoding") != "" && resp.Header.Get("Content-Encoding") != "identity" {
				category = "limite"
			} else {
				bytesRead, err = io.Copy(io.Discard, io.LimitReader(resp.Body, int64(r.Plano.MaxRespostaBytes)+1))
				if bytesRead > int64(r.Plano.MaxRespostaBytes) {
					category = "limite"
				} else if err == nil {
					category = "http"
					httpError = resp.StatusCode >= 400
				}
			}
			resp.Body.Close()
		} else if resp != nil {
			resp.Body.Close()
		}
	}
	if category == "transporte" {
		if parent.Err() != nil {
			category = "cancelada"
		} else if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			category = "timeout"
		}
	}
	elapsed := time.Since(start).Microseconds()
	s.mu.Lock()
	defer s.mu.Unlock()
	s.countLocked(r, wi, measured, func(c *Counts) {
		c.Finalizadas++
		c.EmVoo--
		c.BytesLidos += bytesRead
		switch category {
		case "http":
			c.RespostasHTTP++
			if httpError {
				c.ErrosHTTP++
			}
		case "limite":
			c.LimitesResposta++
		case "cancelada":
			c.CanceladasEmVoo++
		case "timeout":
			c.Timeouts++
		default:
			c.FalhasTransporte++
		}
	})
	if measured {
		r.samples = append(r.samples, elapsed)
		r.Janelas[wi].samples = append(r.Janelas[wi].samples, elapsed)
	}
	bucket := s.hist[r.Plano.Servico]
	if bucket == nil {
		bucket = make([]int64, len(latencyBuckets)+3)
	}
	for i, b := range latencyBuckets {
		if elapsed <= b {
			bucket[i]++
		}
	}
	bucket[len(latencyBuckets)]++
	bucket[len(latencyBuckets)+1] += elapsed
	s.hist[r.Plano.Servico] = bucket
}
