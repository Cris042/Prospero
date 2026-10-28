package br.prospero.mo.matching.jpa;

import br.prospero.mo.cancelamento.actor.ConfirmarCancelamentoActor;
import br.prospero.mo.cancelamento.dominio.EstadoCancelamento;
import br.prospero.mo.cancelamento.dominio.MotivoRecusa;
import br.prospero.mo.cancelamento.dominio.OperacaoCancelamento;
import br.prospero.mo.liquidacao.dominio.CreditoEsperado;
import br.prospero.mo.liquidacao.dominio.RepositorioLiquidacao;
import br.prospero.mo.matching.dominio.FatoMatching;
import br.prospero.mo.matching.dominio.NegocioMatching;
import br.prospero.mo.matching.dominio.PernaOco;
import br.prospero.mo.matching.dominio.ReservaMatching;
import br.prospero.mo.matching.helper.CalculoConsumo;
import br.prospero.mo.mensageria.dominio.ParticaoBloqueadaException;
import br.prospero.mo.risco.helper.EnvelopeAdmissao;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.enterprise.inject.Instance;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static br.prospero.mo.matching.helper.EnvelopeMatching.*;

/**
 * Locks de contas ordenados antecedem reservas. Todos os efeitos/dedup usam a transação do actor. As guardas e
 * transições de reserva, perna e negócio ficam no domínio (`mo.matching.dominio`); aqui só travar, carregar e persistir.
 */
@ApplicationScoped
public class RepositorioMatchingJpa {
    @Inject Instance<EntityManager> gerenciadores;
    @Inject RepositorioLiquidacao liquidacao;
    @Inject ConfirmarCancelamentoActor cancelamento;

    private EntityManager em() {
        return gerenciadores.get();
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public void aplicar(JsonNode evento) {
        if (evento.get("versao").intValue() == 5) {
            aplicarCancelamento(evento);
            return;
        }
        if (texto(evento,"tipo").equals("SESSAO_ENCERRADA")) {
            aplicarMarco(evento);
            return;
        }
        JsonNode d=evento.get("dados");
        boolean negocio=texto(evento,"tipo").equals("NEGOCIO");
        travarContas(d,negocio);
        String hash=assinatura(evento);
        if (duplicado("matching_evento","id",uuid(evento,"id"),hash)) return;
        boolean v4 = evento.get("versao").intValue() == 4;
        if (v4) exigirCausacaoEncerramento(evento);
        exigirAdmissaoDaSessao(evento,"correlacao");
        boolean v2 = evento.get("versao").intValue() >= 2;
        if (v2 && !v4) exigirAdmissaoDaSessao(evento,"causacao");
        if (negocio) {
            aplicarNegocio(evento,d,v2);
        } else {
            aplicarFatoDeOrdem(evento,d,v2,v4);
        }
        inserir("matching_evento","id",uuid(evento,"id"),hash);
    }

    /**
     * v5 (cancelamento manual, ADR-013 RF10c2), já validado por ler(). Sem operação persistida não há o que liberar:
     * bloqueia antes de travar conta, tocar reserva ou deduplicar. Com operação, o terminal libera a reserva pelo mesmo
     * encerrar() dos demais terminais e a decisão fecha a operação, tudo na transação do consumidor.
     */
    private void aplicarCancelamento(JsonNode evento) {
        JsonNode d=evento.get("dados");
        UUID ordem=uuid(d,"ordem");
        if (cancelamento.operacao(ordem).isEmpty()) throw new ParticaoBloqueadaException("CANCELAMENTO_SEM_COORDENACAO");
        travarContas(d,false);
        String hash=assinatura(evento);
        if (duplicado("matching_evento","id",uuid(evento,"id"),hash)) return;
        OperacaoCancelamento operacao=cancelamento.travar(ordem);
        exigirOperacaoDoEvento(evento,d,operacao);
        if (texto(evento,"tipo").equals("ORDEM_ENCERRADA")) {
            encerrarPorCancelamento(evento,d,operacao);
        } else {
            decidirCancelamento(d,operacao);
        }
        inserir("matching_evento","id",uuid(evento,"id"),hash);
    }

    /** Causação, correlação e alvo do evento são exatamente os da operação coordenada. */
    private static void exigirOperacaoDoEvento(JsonNode evento, JsonNode d, OperacaoCancelamento operacao) {
        exigir(uuid(evento,"causacao").equals(operacao.comando()),"causacao cancelamento");
        exigir(uuid(evento,"correlacao").equals(operacao.admissao()),"correlacao cancelamento");
        exigir(operacao.intencao().equals(uuid(d,"intencao")) && operacao.conta().equals(texto(d,"conta"))
                && operacao.ticker().equals(texto(evento,"ticker"))
                && operacao.dataSessao().equals(LocalDate.parse(texto(evento,"dataSessao"))),"alvo cancelamento");
    }

    private void encerrarPorCancelamento(JsonNode evento, JsonNode d, OperacaoCancelamento operacao) {
        exigir(operacao.estado()==EstadoCancelamento.COMANDO_EMITIDO,"estado cancelamento");
        exigirAdmissaoDaSessao(evento,"correlacao");
        ReservaMatching r=reserva(operacao.intencao());
        validar(r,evento,d,"");
        r.exigirCancelamentoManual(inteiro(d,"executada",0,1000000),inteiro(d,"cancelada",1,1000000));
        encerrar(r);
        cancelamento.terminalAplicado(operacao,uuid(evento,"id"));
    }

    private void decidirCancelamento(JsonNode d, OperacaoCancelamento operacao) {
        String resultado=texto(d,"resultado");
        if (resultado.equals("APLICADO")) {
            exigir(operacao.estado()==EstadoCancelamento.TERMINAL_APLICADO && operacao.terminal().equals(uuid(d,"terminal")),"decisao sem terminal");
            cancelamento.confirmar(operacao);
            return;
        }
        exigir(operacao.estado()==EstadoCancelamento.COMANDO_EMITIDO,"recusa do matching");
        cancelamento.recusar(operacao,MotivoRecusa.valueOf(resultado));
    }

    private void travarContas(JsonNode d, boolean negocio) {
        var contas= negocio ? Stream.of(texto(d,"contaCompra"),texto(d,"contaVenda")) : Stream.of(texto(d,"conta"));
        contas.distinct().sorted().forEach(c -> em().createNativeQuery("SELECT conta FROM conta_risco WHERE conta=:c FOR UPDATE").setParameter("c",c).getResultList());
    }

    /** A admissão informada no campo (correlação ou causação) existe para o mesmo ativo e sessão. */
    private void exigirAdmissaoDaSessao(JsonNode evento, String campo) {
        exigir(!em().createNativeQuery("SELECT 1 FROM reserva WHERE admissao=:a AND ticker=:t AND data_sessao=:s")
                .setParameter("a",uuid(evento,campo)).setParameter("t",texto(evento,"ticker"))
                .setParameter("s",LocalDate.parse(texto(evento,"dataSessao"))).getResultList().isEmpty(),campo);
    }

    private void aplicarNegocio(JsonNode evento, JsonNode d, boolean v2) {
        // A assinatura do negócio ignora envelope/id; nomes e valores dos dados são o contrato financeiro.
        String assinaturaNegocio=assinatura(d);
        String agressor=texto(d,"agressor").equals("COMPRA")?"Compra":"Venda";
        exigir(uuid(evento,"correlacao").equals(EnvelopeAdmissao.id(uuid(d,"intencao"+agressor))),"agressor correlacao");
        if (duplicado("matching_negocio","negocio",texto(d,"negocio"),assinaturaNegocio)) return;
        ReservaMatching compra=reserva(uuid(d,"intencaoCompra"));
        ReservaMatching venda=reserva(uuid(d,"intencaoVenda"));
        validarVersao(evento,compra,venda);
        UUID ordemCompra=uuid(d,"ordemCompra"), ordemVenda=uuid(d,"ordemVenda");
        exigir(!ordemCompra.equals(ordemVenda) && !compra.intencao().equals(venda.intencao()),"ordens distintas");
        validar(compra,evento,d,"Compra"); validar(venda,evento,d,"Venda");
        long q=inteiro(d,"quantidade",1,1000000); BigDecimal p=preco(d,"preco");
        PernaOco pernaCompra=compra.oco()?perna(compra,ordemCompra):null, pernaVenda=venda.oco()?perna(venda,ordemVenda):null;
        new NegocioMatching(compra,venda,pernaCompra,pernaVenda,q,p).exigirExecutavel(v2);
        var consumo=CalculoConsumo.compra(compra.restante(),q,compra.teto(),p,compra.valor());
        consumirCompra(compra,q,consumo); consumirVenda(venda,q);
        if (pernaCompra!=null) executarPerna(pernaCompra,q);
        if (pernaVenda!=null) executarPerna(pernaVenda,q);
        // RF08c (ADR-016): créditos D+2 esperados na mesma transação dos débitos D0, sob os locks das contas.
        liquidacao.registrarExpectativas(uuid(evento,"id"),CreditoEsperado.doNegocio(texto(d,"negocio"),texto(evento,"ticker"),
                compra.conta(),venda.conta(),p,q));
        inserir("matching_negocio","negocio",texto(d,"negocio"),assinaturaNegocio);
    }

    private void aplicarFatoDeOrdem(JsonNode evento, JsonNode d, boolean v2, boolean v4) {
        ReservaMatching r=reserva(uuid(d,"intencao"));
        if (!v4) validarVersao(evento,r);
        validar(r,evento,d,"");
        FatoMatching fato=fato(evento,d);
        UUID admissao=EnvelopeAdmissao.id(r.intencao());
        if (r.oco()) {
            aplicarPerna(fato,r,perna(r,uuid(d,"ordem")),admissao);
            return;
        }
        switch (r.transicao(fato,v2,admissao)) {
            case ACIONAR_STOP -> em().createNativeQuery("UPDATE reserva SET stop_acionado=TRUE WHERE intencao=:i")
                    .setParameter("i",r.intencao()).executeUpdate();
            case ENCERRAR -> encerrar(r);
            case NENHUMA -> { }
            // Redução só existe no Stop Loss de grupo OCO (tratado em aplicarPerna).
            case REDUZIR -> throw new IllegalStateException("reducao fora de OCO");
        }
    }

    /** Fatos de uma perna OCO: só o último terminal do grupo libera o restante reservado. */
    private void aplicarPerna(FatoMatching fato, ReservaMatching r, PernaOco p, UUID admissao) {
        switch (p.transicao(fato,r,admissao,() -> irma(p))) {
            case REDUZIR -> em().createNativeQuery("UPDATE reserva_perna SET reduzida=reduzida+:q WHERE ordem=:o")
                    .setParameter("q",fato.reduzida()).setParameter("o",p.ordem()).executeUpdate();
            case ACIONAR_STOP -> em().createNativeQuery("UPDATE reserva_perna SET stop_acionado=TRUE WHERE ordem=:o")
                    .setParameter("o",p.ordem()).executeUpdate();
            case ENCERRAR -> encerrarPerna(r,p,p.restante());
            case NENHUMA -> { }
        }
    }

    /** Marco do encerramento (v4): sem conta nem reserva; só o fato deduplicado (RF05c.4). */
    private void aplicarMarco(JsonNode evento) {
        exigirCausacaoEncerramento(evento);
        exigir(uuid(evento,"correlacao").equals(uuid(evento,"causacao")),"correlacao encerramento");
        // Id derivado da causação: o marco é o último evento do comando, de índice igual às expiradas.
        String derivado=UUID.nameUUIDFromBytes(("matching:"+uuid(evento,"causacao")+":"+evento.get("dados").get("expiradas").longValue())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        exigir(uuid(evento,"id").toString().equals(derivado),"id encerramento");
        String hash=assinatura(evento);
        if (!duplicado("matching_evento","id",uuid(evento,"id"),hash)) inserir("matching_evento","id",uuid(evento,"id"),hash);
    }

    private static void exigirCausacaoEncerramento(JsonNode evento) {
        exigir(uuid(evento,"causacao").equals(encerramento(texto(evento,"ticker"),texto(evento,"dataSessao"))),"causacao encerramento");
    }

    /** v3 exatamente quando a raiz ou um alvo é OCO; senão v2 exatamente quando há stop (ADR-013). */
    private void validarVersao(JsonNode evento, ReservaMatching... alvos) {
        int versao = evento.get("versao").intValue();
        List<?> raizes = em().createNativeQuery("SELECT tipo,oco FROM reserva WHERE admissao=:a AND ticker=:t AND data_sessao=:s")
                .setParameter("a",uuid(evento,versao>=2?"causacao":"correlacao"))
                .setParameter("t",texto(evento,"ticker")).setParameter("s",LocalDate.parse(texto(evento,"dataSessao"))).getResultList();
        boolean envolveOco = raizes.stream().anyMatch(c -> Boolean.TRUE.equals(((Object[]) c)[1]))
                || java.util.Arrays.stream(alvos).anyMatch(ReservaMatching::oco);
        boolean envolveStop = raizes.stream().map(c -> ((Object[]) c)[0]).anyMatch(t -> "STOP".equals(t) || "STOP_LIMIT".equals(t))
                || java.util.Arrays.stream(alvos).anyMatch(ReservaMatching::stop);
        exigir(versao == (envolveOco ? 3 : envolveStop ? 2 : 1),"versao stop");
    }

    private PernaOco perna(ReservaMatching r, UUID ordem) {
        return perna(r.intencao(),ordem);
    }

    private PernaOco perna(UUID intencao, UUID ordem) {
        List<?> linhas=em().createNativeQuery("""
                SELECT ordem,intencao,papel,tipo,preco_limite,preco_gatilho,quantidade,executada,reduzida,cancelada,
                stop_acionado,estado FROM reserva_perna WHERE ordem=:o FOR UPDATE
                """).setParameter("o",ordem).getResultList();
        exigir(linhas.size()==1,"perna ausente"); Object[] c=(Object[])linhas.getFirst();
        PernaOco p=new PernaOco((UUID)c[0],(UUID)c[1],(String)c[2],(String)c[3],(BigDecimal)c[4],(BigDecimal)c[5],
                ((Number)c[6]).longValue(),((Number)c[7]).longValue(),((Number)c[8]).longValue(),((Number)c[9]).longValue(),
                (Boolean)c[10],(String)c[11]);
        exigir(p.intencao().equals(intencao),"perna de outro grupo");
        return p;
    }

    private PernaOco irma(PernaOco p) {
        List<?> ordens=em().createNativeQuery("SELECT ordem FROM reserva_perna WHERE intencao=:i AND ordem<>:o")
                .setParameter("i",p.intencao()).setParameter("o",p.ordem()).getResultList();
        exigir(ordens.size()==1,"irma ausente");
        return perna(p.intencao(),(UUID)ordens.getFirst());
    }

    private void executarPerna(PernaOco p, long q) {
        em().createNativeQuery("UPDATE reserva_perna SET executada=executada+:q,estado='PARCIAL' WHERE ordem=:o")
                .setParameter("q",q).setParameter("o",p.ordem()).executeUpdate();
    }

    private void encerrarPerna(ReservaMatching r, PernaOco p, long cancelada) {
        em().createNativeQuery("UPDATE reserva_perna SET cancelada=cancelada+:c,estado='ENCERRADA' WHERE ordem=:o")
                .setParameter("c",cancelada).setParameter("o",p.ordem()).executeUpdate();
        if (!irma(p).viva()) {
            encerrar(r);
        }
    }

    private void validar(ReservaMatching r, JsonNode e,JsonNode d,String lado) {
        // No grupo OCO a perna é conferida em reserva_perna; a reserva confere só conta, ativo e sessão.
        exigir((r.oco() || r.ordem()!=null && r.ordem().equals(uuid(d,"ordem"+lado))) && r.conta().equals(texto(d,"conta"+lado)),"identidade reserva");
        exigir(r.ticker().equals(texto(e,"ticker")) && r.sessao().equals(LocalDate.parse(texto(e,"dataSessao"))),"sessao reserva");
        if (!lado.isEmpty()) {
            exigir(!r.estado().equals("ENCERRADA"),"reserva encerrada");
            exigir(r.recurso().equals(lado.equals("Compra")?"CAIXA":"CUSTODIA"),"lado reserva");
        }
        Object[] a=(Object[])em().createNativeQuery("SELECT lote,tick,quantidade_maxima FROM ativo_risco WHERE ticker=:t").setParameter("t",r.ticker()).getSingleResult();
        if (d.has("preco")) exigir(preco(d,"preco").remainder((BigDecimal)a[1]).signum()==0,"tick");
        if (d.has("quantidade")) { long q=d.get("quantidade").longValue(); exigir(q%((Number)a[0]).longValue()==0 && q<=((Number)a[2]).longValue(),"lote"); }
    }

    private ReservaMatching reserva(UUID i) {
        List<?> linhas=em().createNativeQuery("""
                SELECT intencao,ordem,conta,ticker,recurso,tipo,validade,data_sessao,quantidade_inicial,
                quantidade_restante,preco_limite,preco_maximo,valor_restante,estado,preco_gatilho,stop_acionado,oco FROM reserva WHERE intencao=:i FOR UPDATE
                """).setParameter("i",i).getResultList();
        exigir(linhas.size()==1,"reserva ausente"); Object[] c=(Object[])linhas.getFirst();
        boolean oco=(Boolean)c[16];
        exigir((c[1]!=null || oco) && c[7]!=null && c[8]!=null && c[9]!=null,"reserva legada");
        LocalDate data=c[7] instanceof LocalDate l ? l : ((java.sql.Date)c[7]).toLocalDate();
        return new ReservaMatching((UUID)c[0],(UUID)c[1],(String)c[2],(String)c[3],(String)c[4],(String)c[5],(String)c[6],data,
                ((Number)c[8]).longValue(),((Number)c[9]).longValue(),(BigDecimal)c[10],(BigDecimal)c[11],(BigDecimal)c[12],(String)c[13],(BigDecimal)c[14],(Boolean)c[15],oco);
    }
    private boolean duplicado(String tabela,String coluna,Object id,String hash) {
        List<?> existentes=em().createNativeQuery("SELECT assinatura FROM "+tabela+" WHERE "+coluna+"=:i").setParameter("i",id).getResultList();
        if(existentes.isEmpty()) return false;
        exigir(hash.equals(existentes.getFirst()),"dedup divergente"); return true;
    }
    private void inserir(String tabela,String coluna,Object id,String hash) {
        em().createNativeQuery("INSERT INTO "+tabela+" ("+coluna+",assinatura) VALUES (:i,:h)").setParameter("i",id).setParameter("h",hash).executeUpdate();
    }
    private void consumirCompra(ReservaMatching r,long q,CalculoConsumo.Compra consumo) {
        BigDecimal custo=consumo.custo();
        BigDecimal retido=consumo.retido();
        int n=em().createNativeQuery("UPDATE conta_risco SET reservado=reservado-:r,total=total-:p,disponivel=disponivel+:r-:p WHERE conta=:c AND reservado>=:r AND total>=:p")
                .setParameter("r",retido).setParameter("p",custo).setParameter("c",r.conta()).executeUpdate();
        exigir(n==1,"controle caixa");
        em().createNativeQuery("UPDATE reserva SET quantidade_restante=quantidade_restante-:q,valor_restante=valor_restante-:v,estado='PARCIAL' WHERE intencao=:i")
                .setParameter("q",q).setParameter("v",retido).setParameter("i",r.intencao()).executeUpdate();
    }
    private void consumirVenda(ReservaMatching r,long q) {
        int n=em().createNativeQuery("UPDATE custodia_risco SET reservado=reservado-:q,total=total-:q WHERE conta=:c AND ticker=:t AND reservado>=:q AND total>=:q")
                .setParameter("q",q).setParameter("c",r.conta()).setParameter("t",r.ticker()).executeUpdate();
        exigir(n==1,"controle custodia");
        em().createNativeQuery("UPDATE reserva SET quantidade_restante=quantidade_restante-:q,estado='PARCIAL' WHERE intencao=:i")
                .setParameter("q",q).setParameter("i",r.intencao()).executeUpdate();
    }
    private void encerrar(ReservaMatching r) {
        exigir(!r.estado().equals("ENCERRADA"),"terminal divergente");
        int n;
        if(r.recurso().equals("CAIXA")) {
            n=em().createNativeQuery("UPDATE conta_risco SET reservado=reservado-:v,disponivel=disponivel+:v WHERE conta=:c AND reservado>=:v")
                    .setParameter("v",r.valor()).setParameter("c",r.conta()).executeUpdate();
        } else {
            n=em().createNativeQuery("UPDATE custodia_risco SET reservado=reservado-:q,disponivel=disponivel+:q WHERE conta=:c AND ticker=:t AND reservado>=:q")
                    .setParameter("q",r.restante()).setParameter("c",r.conta()).setParameter("t",r.ticker()).executeUpdate();
        }
        exigir(n==1,"controle liberacao");
        em().createNativeQuery("UPDATE reserva SET estado='ENCERRADA',quantidade_restante=0,valor_restante=CASE WHEN valor_restante IS NULL THEN NULL ELSE 0 END WHERE intencao=:i")
                .setParameter("i",r.intencao()).executeUpdate();
    }
}
