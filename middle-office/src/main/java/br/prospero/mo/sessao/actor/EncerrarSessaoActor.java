package br.prospero.mo.sessao.actor;

import br.prospero.mo.comum.dominio.Categoria;
import br.prospero.mo.comum.dominio.RegraNegocioException;
import br.prospero.mo.mensageria.dominio.MensagemSaida;
import br.prospero.mo.sessao.dominio.RepositorioSessao;
import br.prospero.mo.sessao.dto.EncerramentoResponseDTO;
import br.prospero.mo.sessao.helper.EnvelopeEncerramento;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/** Encerramento manual (decisão do usuário, ADR-013 RF05c.4): um comando por ativo/data, sem agendador. */
@ApplicationScoped
public class EncerrarSessaoActor {
    private static final Logger LOG = Logger.getLogger(EncerrarSessaoActor.class);
    private static final Set<String> ATIVOS = Set.of("ALFA3", "BETA3", "GAMA3", "DELTA3", "OMEGA3");
    private final RepositorioSessao repositorio;
    private final Clock relogio;
    private final boolean habilitado;

    @Inject
    public EncerrarSessaoActor(RepositorioSessao repositorio, Clock relogio,
                               @ConfigProperty(name = "prospero.sessao.encerramento.habilitado", defaultValue = "false")
                               boolean habilitado) {
        this.repositorio = repositorio;
        this.relogio = relogio;
        this.habilitado = habilitado;
    }

    @Transactional
    public EncerramentoResponseDTO encerrar(String ticker, String data, String traceparent) {
        if (!habilitado) {
            throw new RegraNegocioException(Categoria.NAO_ENCONTRADO, "ENCERRAMENTO_DESABILITADO",
                    "Encerramento de sessão desabilitado neste ambiente.");
        }
        if (!ATIVOS.contains(ticker)) {
            throw new RegraNegocioException(Categoria.NAO_ENCONTRADO, "ATIVO_DESCONHECIDO", "Ativo desconhecido.");
        }
        LocalDate sessao = sessao(data);
        var comando = new MensagemSaida(EnvelopeEncerramento.id(ticker, sessao), EnvelopeEncerramento.TOPICO, ticker,
                EnvelopeEncerramento.TIPO, EnvelopeEncerramento.serializar(ticker, sessao,
                relogio.instant().truncatedTo(ChronoUnit.MICROS), traceparent), traceparent,
                relogio.instant().truncatedTo(ChronoUnit.MICROS));
        boolean novo = repositorio.registrar(comando);
        LOG.infof("Encerramento de sessão %s: ticker=%s data=%s", novo ? "registrado" : "repetido", ticker, sessao);
        return new EncerramentoResponseDTO(ticker, sessao, comando.id(), !novo);
    }

    private static LocalDate sessao(String data) {
        try {
            LocalDate sessao = LocalDate.parse(data);
            if (sessao.toString().equals(data)) {
                return sessao;
            }
        } catch (DateTimeParseException ignorada) {
            // Tratado abaixo sem refletir a entrada.
        }
        throw new RegraNegocioException(Categoria.ENTRADA_INVALIDA, "DATA_INVALIDA", "Data de sessão inválida (AAAA-MM-DD).");
    }
}
