package br.prospero.fo.cotacao;

import br.prospero.fo.cotacao.dominio.LivroCotado;
import br.prospero.fo.cotacao.dominio.RepositorioCotacao;
import br.prospero.fo.cotacao.dominio.SessaoCotacao;
import br.prospero.fo.ordens.dominio.EventoMatching;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Fixture de consulta REST; persistência e aplicação são verificadas contra JPA real no IT. */
@Alternative
@Priority(1)
@ApplicationScoped
public class RepositorioCotacaoMemoria implements RepositorioCotacao {
    private final Map<String, LivroCotado> livros = new ConcurrentHashMap<>();

    public void preparar(LivroCotado livro) { livros.put(livro.sessao().ticker(), livro); }
    public void limpar() { livros.clear(); }

    @Override
    public boolean aplicar(EventoMatching evento) {
        throw new UnsupportedOperationException("Aplicação deve ser testada no adaptador PostgreSQL");
    }

    @Override
    public Optional<SessaoCotacao> sessao(String ticker, LocalDate dia) {
        return livro(ticker).map(LivroCotado::sessao).filter(s -> s.sessao().equals(dia));
    }

    @Override
    public Optional<LivroCotado> livro(String ticker) { return Optional.ofNullable(livros.get(ticker)); }
}
