# Próspero — Angular

Interface local do simulador. A fundação apresenta os quatro contextos e consulta identidade/prontidão por API, com estados de indisponibilidade independentes. Negociação ainda não implementada.

## Executar e verificar

```bash
npm ci
npm test -- --watch=false
npm run format:check
npm run build
npm start
```

Node 24.16.0; Angular 22.2.3 e TypeScript 6.0. O dev server usa `proxy.conf.json` para as APIs em localhost:8081–8084. No container, o build estático é servido por Nginx em 8080 e publicado em localhost:4200, com proxy por DNS Docker e mesma origem.

Consulte o [roadmap Angular](ROADMAP.md), o [README geral](../README.md) e os [contratos de API](../docs/adrs/ADR-003-contratos-api.md). O runner de planilhas será criado na P03, dentro deste projeto, como CLI em container.
