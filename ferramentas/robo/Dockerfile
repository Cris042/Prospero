FROM golang:1.27.3-bookworm@sha256:21ade014faf952365b9ecbd8dbdb87176fbacb82fac9fb132d9f2a7b8cc3f132 AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN rm -f *_test.go && CGO_ENABLED=0 GOTOOLCHAIN=local go build -trimpath -ldflags='-s -w' -o /out/robo-formador .

FROM scratch
COPY --from=build /out/robo-formador /robo-formador
USER 65532:65532
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 CMD ["/robo-formador", "--healthcheck"]
ENTRYPOINT ["/robo-formador"]
