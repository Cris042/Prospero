FROM golang:1.27.2-bookworm@sha256:5cf287a799e6b94384bad13d16b14904c531f51ba65792237e122ce42b392f61 AS build
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
