package main

import (
	"errors"
	"net"
	"net/url"
	"testing"
)

type erroRede struct{ timeout bool }

func (e erroRede) Error() string   { return "rede" }
func (e erroRede) Timeout() bool   { return e.timeout }
func (e erroRede) Temporary() bool { return false }

// Classificação determinística: timeout de cabeçalho/discagem conta como timeout, outros erros não.
func TestExpirouClassificaTimeoutsDeRede(t *testing.T) {
	cabecalho := &url.Error{Op: "Get", URL: "http://x", Err: erroRede{timeout: true}}
	discagem := &net.OpError{Op: "dial", Net: "tcp", Err: erroRede{timeout: true}}
	recusada := &url.Error{Op: "Get", URL: "http://x", Err: erroRede{timeout: false}}
	casos := []struct {
		nome string
		err  error
		quer bool
	}{
		{"cabecalho", cabecalho, true},
		{"discagem", discagem, true},
		{"recusada", recusada, false},
		{"generico", errors.New("falha"), false},
		{"nulo", nil, false},
	}
	for _, c := range casos {
		if got := expirou(c.err); got != c.quer {
			t.Errorf("%s: expirou=%v, quer %v", c.nome, got, c.quer)
		}
	}
}
