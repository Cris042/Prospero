package main

import (
	"fmt"
	"net/http"
	"os"
	"runtime"
	"strconv"
	"strings"
	"syscall"
)

func (s *Server) MetricsHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		s.mu.Lock()
		closed := s.closed
		s.mu.Unlock()
		if closed {
			w.WriteHeader(503)
			return
		}
		w.WriteHeader(200)
	})
	mux.HandleFunc("GET /metrics", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.RawQuery != "" {
			w.WriteHeader(400)
			return
		}
		w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
		s.mu.Lock()
		active := 0
		if s.active != nil {
			active = 1
		}
		totals := map[string]Counts{}
		hist := map[string][]int64{}
		delay := s.maxDelay
		for k, v := range s.totals {
			totals[k] = *v
		}
		for k, v := range s.hist {
			hist[k] = append([]int64(nil), v...)
		}
		s.mu.Unlock()
		fmt.Fprintf(w, "# TYPE carga_execucao_ativa gauge\ncarga_execucao_ativa %d\n", active)
		fmt.Fprintf(w, "# TYPE carga_scheduler_delay_seconds_max gauge\ncarga_scheduler_delay_seconds_max %.6f\n", float64(delay)/1e6)
		names := []string{"oferecidas", "iniciadas", "finalizadas", "descartadas", "erros_http", "timeouts", "bytes_lidos", "falhas_transporte", "canceladas_em_voo", "limites_resposta"}
		for _, name := range names {
			fmt.Fprintf(w, "# TYPE carga_%s_total counter\n", name)
		}
		fmt.Fprintln(w, "# TYPE carga_duracao_seconds histogram")
		for _, service := range []string{"front-office", "middle-office", "matching-engine", "back-office"} {
			c, exists := totals[service]
			if !exists {
				continue
			}
			values := []int64{int64(c.Oferecidas), int64(c.Iniciadas), int64(c.Finalizadas), int64(c.Descartadas), int64(c.ErrosHTTP), int64(c.Timeouts), c.BytesLidos, int64(c.FalhasTransporte), int64(c.CanceladasEmVoo), int64(c.LimitesResposta)}
			for i, n := range names {
				fmt.Fprintf(w, "carga_%s_total{servico=%q} %d\n", n, service, values[i])
			}
			h := hist[service]
			if h == nil {
				continue
			}
			for i, b := range latencyBuckets {
				fmt.Fprintf(w, "carga_duracao_seconds_bucket{servico=%q,le=%q} %d\n", service, strconv.FormatFloat(float64(b)/1e6, 'f', -1, 64), h[i])
			}
			fmt.Fprintf(w, "carga_duracao_seconds_bucket{servico=%q,le=\"+Inf\"} %d\ncarga_duracao_seconds_count{servico=%q} %d\ncarga_duracao_seconds_sum{servico=%q} %.6f\n", service, h[len(latencyBuckets)], service, h[len(latencyBuckets)], service, float64(h[len(latencyBuckets)+1])/1e6)
		}
		var mem runtime.MemStats
		runtime.ReadMemStats(&mem)
		fmt.Fprintf(w, "# TYPE carga_go_heap_bytes gauge\ncarga_go_heap_bytes %d\n# TYPE carga_go_goroutines gauge\ncarga_go_goroutines %d\n# TYPE carga_go_gc_cycles_total counter\ncarga_go_gc_cycles_total %d\n", mem.HeapAlloc, runtime.NumGoroutine(), mem.NumGC)
		var usage syscall.Rusage
		if syscall.Getrusage(syscall.RUSAGE_SELF, &usage) == nil {
			cpu := float64(usage.Utime.Sec+usage.Stime.Sec) + float64(usage.Utime.Usec+usage.Stime.Usec)/1e6
			fmt.Fprintf(w, "# TYPE carga_process_cpu_seconds_total counter\ncarga_process_cpu_seconds_total %.6f\n", cpu)
		}
		if b, e := os.ReadFile("/proc/self/statm"); e == nil {
			fields := strings.Fields(string(b))
			if len(fields) > 1 {
				pages, e := strconv.ParseInt(fields[1], 10, 64)
				if e == nil {
					fmt.Fprintf(w, "# TYPE carga_process_resident_memory_bytes gauge\ncarga_process_resident_memory_bytes %d\n", pages*int64(os.Getpagesize()))
				}
			}
		}
		if entries, e := os.ReadDir("/proc/self/fd"); e == nil {
			fmt.Fprintf(w, "# TYPE carga_process_open_fds gauge\ncarga_process_open_fds %d\n", len(entries))
		}
	})
	return mux
}
