FROM golang:1.27.3-bookworm@sha256:21ade014faf952365b9ecbd8dbdb87176fbacb82fac9fb132d9f2a7b8cc3f132 AS build
WORKDIR /src
COPY go.mod ./
COPY *.go openapi.json ./
RUN CGO_ENABLED=0 GOTOOLCHAIN=local go build -trimpath -ldflags='-s -w' -o /out/api-carga .

FROM scratch
COPY --from=build /out/api-carga /api-carga
USER 65532:65532
EXPOSE 8080 9091
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 CMD ["/api-carga", "--healthcheck"]
ENTRYPOINT ["/api-carga"]
