package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"reflect"
	"strings"
	"time"
)

// Reject duplicate names recursively: encoding/json otherwise silently keeps the last value.
func strictJSON(b []byte, dst any) error {
	if len(b) > 16384 {
		return errors.New("limite")
	}
	d := json.NewDecoder(bytes.NewReader(b))
	d.UseNumber()
	var walk func(int, reflect.Type) error
	walk = func(depth int, typ reflect.Type) error {
		for typ.Kind() == reflect.Pointer {
			typ = typ.Elem()
		}
		if depth > 12 {
			return errors.New("profundidade")
		}
		t, e := d.Token()
		if e != nil {
			return e
		}
		if delim, ok := t.(json.Delim); ok {
			switch delim {
			case '{':
				seen := map[string]bool{}
				allowed := map[string]reflect.Type{}
				required := map[string]bool{}
				if typ.Kind() != reflect.Struct {
					return errors.New("objeto invalido")
				}
				for i := 0; i < typ.NumField(); i++ {
					f := typ.Field(i)
					name := strings.Split(f.Tag.Get("json"), ",")[0]
					allowed[name] = f.Type
					if !strings.Contains(f.Tag.Get("json"), ",omitempty") {
						required[name] = true
					}
				}
				for d.More() {
					k, e := d.Token()
					if e != nil {
						return e
					}
					name, ok := k.(string)
					fieldType, known := allowed[name]
					if !ok || !known || seen[name] {
						return errors.New("duplicado")
					}
					seen[name] = true
					if e = walk(depth+1, fieldType); e != nil {
						return e
					}
				}
				for name := range required {
					if !seen[name] {
						return errors.New("campo obrigatorio")
					}
				}
			case '[':
				if typ.Kind() != reflect.Slice {
					return errors.New("lista invalida")
				}
				for d.More() {
					if e = walk(depth+1, typ.Elem()); e != nil {
						return e
					}
				}
			default:
				return errors.New("json")
			}
			_, e = d.Token()
			return e
		}
		if t == nil {
			return errors.New("null invalido")
		}
		return nil
	}
	if err := walk(0, reflect.TypeOf(dst).Elem()); err != nil {
		return err
	}
	if _, e := d.Token(); e != io.EOF {
		return errors.New("json adicional")
	}
	d = json.NewDecoder(bytes.NewReader(b))
	d.DisallowUnknownFields()
	return d.Decode(dst)
}

type claims struct {
	Telemetria           bool   `json:"telemetria,omitempty"`
	Iss                  string `json:"iss"`
	Aud                  string `json:"aud"`
	Exp                  int64  `json:"exp"`
	Nbf                  int64  `json:"nbf,omitempty"`
	Iat                  int64  `json:"iat,omitempty"`
	Scope                string `json:"scope,omitempty"`
	Nonce                string `json:"nonce,omitempty"`
	Plano                string `json:"plano,omitempty"`
	MemoriaDisponivelMiB int64  `json:"memoriaDisponivelMiB,omitempty"`
	DiscoLivreMiB        int64  `json:"discoLivreMiB,omitempty"`
	CPUs                 int    `json:"cpus,omitempty"`
}

func parseJWT(raw string, key []byte, issuer, audience string, now time.Time) (claims, error) {
	var c claims
	if len(raw) > 4096 {
		return c, errors.New("token")
	}
	parts := strings.Split(raw, ".")
	if len(parts) != 3 {
		return c, errors.New("token")
	}
	header, e := base64.RawURLEncoding.DecodeString(parts[0])
	if e != nil {
		return c, e
	}
	var h struct {
		Alg string `json:"alg"`
		Typ string `json:"typ"`
	}
	if strictJSON(header, &h) != nil || h.Alg != "HS256" || h.Typ != "JWT" {
		return c, errors.New("algoritmo")
	}
	sig, e := base64.RawURLEncoding.DecodeString(parts[2])
	if e != nil {
		return c, e
	}
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(parts[0] + "." + parts[1]))
	if !hmac.Equal(sig, mac.Sum(nil)) {
		return c, errors.New("assinatura")
	}
	payload, e := base64.RawURLEncoding.DecodeString(parts[1])
	if e != nil {
		return c, e
	}
	if e = strictJSON(payload, &c); e != nil {
		return c, e
	}
	n := now.Unix()
	if c.Iss != issuer || c.Aud != audience || c.Exp <= n || c.Nbf > n || c.Iat > n {
		return c, errors.New("claims")
	}
	return c, nil
}
