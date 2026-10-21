package br.prospero.mo.matching.jpa;

import br.prospero.mo.matching.helper.EnvelopeMatching;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.enterprise.inject.Instance;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static br.prospero.mo.matching.helper.EnvelopeMatching.*;

/** Locks de contas ordenados antecedem reservas. Todos os efeitos/dedup usam a transação do actor. */
@ApplicationScoped
public class RepositorioMatchingJpa {
    @Inject Instance<EntityManager> gerenciadores;

    private EntityManager em() {
        return gerenciadores.get();
    }

    public record Reserva(UUID intencao, UUID ordem, String conta, String ticker, String recurso, String tipo,
                          String validade, LocalDate sessao, long inicial, long restante,
                          BigDecimal limite, BigDecimal teto, BigDecimal valor, String estado, BigDecimal gatilho, boolean acionada,
                          boolean oco) { }

    /** Progresso de uma perna OCO; caixa/custódia continuam na reserva do grupo (RF05c.3b). */
    public record Perna(UUID ordem, UUID intencao, String papel, String tipo, BigDecimal limite, BigDecimal gatilho,
                        long quantidade, long executada, long reduzida, long cancelada, boolean acionada, String estado) {
        long restante() { return quantidade - executada - reduzida - cancelada; }
        boolean viva() { return !estado.equals("ENCERRADA"); }
        boolean takeProfit() { return papel.equals("TAKE_PROFIT"); }
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public void aplicar(JsonNode evento) {
        JsonNode d=evento.get("dados");
        boolean negocio=texto(evento,"tipo").equals("NEGOCIO");
        var contas= negocio ? java.util.stream.Stream.of(texto(d,"contaCompra"),texto(d,"contaVenda"))
                : java.util.stream.Stream.of(texto(d,"conta"));
        contas.distinct().sorted().forEach(c -> em().createNativeQuery("SELECT conta FROM conta_risco WHERE conta=:c FOR UPDATE").setParameter("c",c).getResultList());
        String hash=assinatura(evento);
        if (duplicado("matching_evento","id",uuid(evento,"id"),hash)) return;
        boolean v4 = evento.get("versao").intValue() == 4;
        if (v4) exigir(uuid(evento,"causacao").equals(encerramento(texto(evento,"ticker"),texto(evento,"dataSessao"))),"causacao encerramento");
        if (texto(evento,"tipo").equals("SESSAO_ENCERRADA")) {
            // Marco do encerramento: sem reserva própria; registra só o fato para deduplicação.
            exigir(uuid(evento,"correlacao").equals(uuid(evento,"causacao")),"correlacao encerramento");
            inserir("matching_evento","id",uuid(evento,"id"),hash);
            return;
        }
        exigir(!em().createNativeQuery("SELECT 1 FROM reserva WHERE admissao=:a AND ticker=:t AND data_sessao=:s")
                .setParameter("a",uuid(evento,"correlacao")).setParameter("t",texto(evento,"ticker"))
                .setParameter("s",LocalDate.parse(texto(evento,"dataSessao"))).getResultList().isEmpty(),"correlacao");
        boolean v2 = evento.get("versao").intValue() >= 2;
        if (v2 && !v4) exigir(!em().createNativeQuery("SELECT 1 FROM reserva WHERE admissao=:a AND ticker=:t AND data_sessao=:s")
                .setParameter("a",uuid(evento,"causacao")).setParameter("t",texto(evento,"ticker"))
                .setParameter("s",LocalDate.parse(texto(evento,"dataSessao"))).getResultList().isEmpty(),"causacao");
        if (negocio) {
            // A assinatura do negócio ignora envelope/id; nomes e valores dos dados são o contrato financeiro.
            String assinaturaNegocio=assinatura(d);
            String agressor=texto(d,"agressor").equals("COMPRA")?"Compra":"Venda";
            exigir(uuid(evento,"correlacao").equals(br.prospero.mo.risco.helper.EnvelopeAdmissao.id(uuid(d,"intencao"+agressor))),"agressor correlacao");
            if (!duplicado("matching_negocio","negocio",texto(d,"negocio"),assinaturaNegocio)) {
                Reserva compra=reserva(uuid(d,"intencaoCompra"));
                Reserva venda=reserva(uuid(d,"intencaoVenda"));
                validarVersao(evento,compra,venda);
                UUID ordemCompra=uuid(d,"ordemCompra"), ordemVenda=uuid(d,"ordemVenda");
                exigir(!ordemCompra.equals(ordemVenda) && !compra.intencao().equals(venda.intencao()),"ordens distintas");
                validar(compra,evento,d,"Compra"); validar(venda,evento,d,"Venda");
                long q=inteiro(d,"quantidade",1,1000000); BigDecimal p=preco(d,"preco");
                Perna pernaCompra=compra.oco()?perna(compra,ordemCompra):null, pernaVenda=venda.oco()?perna(venda,ordemVenda):null;
                exigir(executavel(compra,pernaCompra,v2) && executavel(venda,pernaVenda,v2),"stop nao acionado");
                exigir(q<=compra.restante() && q<=venda.restante(),"restante");
                exigir((pernaCompra==null || q<=pernaCompra.restante()) && (pernaVenda==null || q<=pernaVenda.restante()),"restante perna");
                exigir(p.compareTo(compra.teto())<=0,"teto compra");
                BigDecimal limiteCompra=pernaCompra==null?compra.limite():pernaCompra.limite();
                BigDecimal limiteVenda=pernaVenda==null?venda.limite():pernaVenda.limite();
                exigir(limiteCompra==null || p.compareTo(limiteCompra)<=0,"limite compra");
                exigir(limiteVenda==null || p.compareTo(limiteVenda)>=0,"limite venda");
                var consumo=br.prospero.mo.matching.helper.CalculoConsumo.compra(compra.restante(),q,compra.teto(),p,compra.valor());
                consumirCompra(compra,q,consumo); consumirVenda(venda,q);
                if (pernaCompra!=null) executarPerna(pernaCompra,q);
                if (pernaVenda!=null) executarPerna(pernaVenda,q);
                inserir("matching_negocio","negocio",texto(d,"negocio"),assinaturaNegocio);
            }
        } else {
            Reserva r=reserva(uuid(d,"intencao"));
            if (!v4) validarVersao(evento,r);
            validar(r,evento,d,"");
            if (r.oco()) {
                aplicarPerna(evento,d,r,perna(r,uuid(d,"ordem")));
                inserir("matching_evento","id",uuid(evento,"id"),hash);
                return;
            }
            exigir(!stop(r) || v2,"versao stop");
            if (v2) exigir(uuid(evento,"correlacao").equals(br.prospero.mo.risco.helper.EnvelopeAdmissao.id(r.intencao())),"alvo correlacao");
            switch(texto(evento,"tipo")) {
                case "STOP_PENDENTE" -> {
                    exigir(stop(r) && !r.acionada() && r.estado().equals("ATIVA"),"stop pendente");
                    exigir(r.gatilho()!=null && r.gatilho().compareTo(preco(d,"precoGatilho"))==0,"gatilho");
                }
                case "STOP_ACIONADO" -> {
                    exigir(stop(r) && !r.acionada() && r.estado().equals("ATIVA") && r.gatilho()!=null,"stop acionamento");
                    exigir(texto(d,"tipoExecutavel").equals(r.tipo().equals("STOP")?"MARKET":"LIMIT"),"tipo executavel");
                    em().createNativeQuery("UPDATE reserva SET stop_acionado=TRUE WHERE intencao=:i")
                            .setParameter("i",r.intencao()).executeUpdate();
                }
                case "ORDEM_REJEITADA" -> { exigir(uuid(evento,"correlacao").equals(br.prospero.mo.risco.helper.EnvelopeAdmissao.id(r.intencao())),"rejeicao correlacao"); exigir(r.inicial()==r.restante(),"rejeicao executada"); exigir(!stop(r) || !r.acionada(),"rejeicao apos acionamento");
                    exigir(!texto(d,"motivo").equals("LIMITE_STOPS_PENDENTES") || stop(r) && !r.acionada(),"rejeicao stop"); encerrar(r); }
                case "ORDEM_ENCERRADA" -> {
                    exigir(inteiro(d,"executada",0,1000000)==r.inicial()-r.restante(),"executada");
                    exigir(inteiro(d,"cancelada",0,1000000)==r.restante(),"cancelada");
                    String motivo=texto(d,"motivo");
                    if (motivo.equals("EXPIRADA_DIA")) {
                        // Expira ofertas DIA e stops pendentes; libera exatamente o saldo restante.
                        exigir(r.validade().equals("DIA"),"expiracao validade");
                        encerrar(r);
                        break;
                    }
                    boolean excesso=motivo.equals("LIMITE_CASCATA");
                    exigir(!excesso || stop(r) && !r.acionada() && r.inicial()==r.restante(),"cascata");
                    exigir(!stop(r) || excesso || r.acionada(),"terminal stop nao acionado");
                    exigir(!motivo.equals("FOK_SEM_LIQUIDEZ") || r.validade().equals("FOK") && r.inicial()==r.restante(),"terminal FOK");
                    exigir(!motivo.equals("SALDO_CANCELADO_IOC") || (r.validade().equals("IOC") || r.tipo().equals("STOP") && r.acionada()),"terminal IOC");
                    exigir(texto(d,"motivo").equals("EXECUTADA") == (r.restante()==0),"execucao terminal");
                    encerrar(r);
                }
                case "OFERTA_NO_LIVRO" -> {
                    exigir(r.estado().equals("ATIVA") || r.estado().equals("PARCIAL"),"reserva encerrada");
                    exigir(inteiro(d,"restante",1,1000000)==r.restante(),"oferta restante");
                    exigir((r.tipo().equals("LIMIT") || r.tipo().equals("STOP_LIMIT") && r.acionada()) && r.validade().equals("DIA"),"oferta tipo");
                    exigir(r.limite()!=null && r.limite().compareTo(preco(d,"preco"))==0,"oferta preco");
                    exigir(texto(d,"lado").equals(r.recurso().equals("CAIXA")?"COMPRA":"VENDA"),"oferta lado");
                }
                default -> {
                    exigir(uuid(evento,"correlacao").equals(br.prospero.mo.risco.helper.EnvelopeAdmissao.id(r.intencao())),"aceita correlacao");
                    exigir(!r.estado().equals("ENCERRADA"),"reserva encerrada");
                }
            }
        }
        inserir("matching_evento","id",uuid(evento,"id"),hash);
    }

    /** v3 exatamente quando a raiz ou um alvo é OCO; senão v2 exatamente quando há stop (ADR-013). */
    private void validarVersao(JsonNode evento, Reserva... alvos) {
        int versao = evento.get("versao").intValue();
        List<?> raizes = em().createNativeQuery("SELECT tipo,oco FROM reserva WHERE admissao=:a AND ticker=:t AND data_sessao=:s")
                .setParameter("a",uuid(evento,versao>=2?"causacao":"correlacao"))
                .setParameter("t",texto(evento,"ticker")).setParameter("s",LocalDate.parse(texto(evento,"dataSessao"))).getResultList();
        boolean envolveOco = raizes.stream().anyMatch(c -> Boolean.TRUE.equals(((Object[]) c)[1]))
                || java.util.Arrays.stream(alvos).anyMatch(Reserva::oco);
        boolean envolveStop = raizes.stream().map(c -> ((Object[]) c)[0]).anyMatch(t -> "STOP".equals(t) || "STOP_LIMIT".equals(t))
                || java.util.Arrays.stream(alvos).anyMatch(RepositorioMatchingJpa::stop);
        exigir(versao == (envolveOco ? 3 : envolveStop ? 2 : 1),"versao stop");
    }

    private static boolean executavel(Reserva r, Perna p, boolean v2) {
        if (p == null) return !stop(r) || v2 && r.acionada();
        // TP negocia até a captura (que o encerra); SL só depois de acionado.
        return p.viva() && (p.takeProfit() || p.acionada());
    }

    private Perna perna(Reserva r, UUID ordem) {
        return perna(r.intencao(),ordem);
    }

    private Perna perna(UUID intencao, UUID ordem) {
        List<?> linhas=em().createNativeQuery("""
                SELECT ordem,intencao,papel,tipo,preco_limite,preco_gatilho,quantidade,executada,reduzida,cancelada,
                stop_acionado,estado FROM reserva_perna WHERE ordem=:o FOR UPDATE
                """).setParameter("o",ordem).getResultList();
        exigir(linhas.size()==1,"perna ausente"); Object[] c=(Object[])linhas.getFirst();
        Perna p=new Perna((UUID)c[0],(UUID)c[1],(String)c[2],(String)c[3],(BigDecimal)c[4],(BigDecimal)c[5],
                ((Number)c[6]).longValue(),((Number)c[7]).longValue(),((Number)c[8]).longValue(),((Number)c[9]).longValue(),
                (Boolean)c[10],(String)c[11]);
        exigir(p.intencao().equals(intencao),"perna de outro grupo");
        return p;
    }

    private Perna irma(Perna p) {
        List<?> ordens=em().createNativeQuery("SELECT ordem FROM reserva_perna WHERE intencao=:i AND ordem<>:o")
                .setParameter("i",p.intencao()).setParameter("o",p.ordem()).getResultList();
        exigir(ordens.size()==1,"irma ausente");
        return perna(p.intencao(),(UUID)ordens.getFirst());
    }

    private void executarPerna(Perna p, long q) {
        em().createNativeQuery("UPDATE reserva_perna SET executada=executada+:q,estado='PARCIAL' WHERE ordem=:o")
                .setParameter("q",q).setParameter("o",p.ordem()).executeUpdate();
    }

    /** Fatos de uma perna OCO: só o último terminal do grupo libera o restante reservado. */
    private void aplicarPerna(JsonNode evento, JsonNode d, Reserva r, Perna p) {
        exigir(uuid(evento,"correlacao").equals(br.prospero.mo.risco.helper.EnvelopeAdmissao.id(r.intencao())),"alvo correlacao");
        boolean stopLoss=!p.takeProfit();
        switch(texto(evento,"tipo")) {
            case "ORDEM_ACEITA" -> exigir(p.viva(),"perna encerrada");
            case "STOP_PENDENTE" -> {
                exigir(stopLoss && !p.acionada() && p.estado().equals("ATIVA"),"stop pendente");
                exigir(p.gatilho().compareTo(preco(d,"precoGatilho"))==0,"gatilho");
            }
            case "ORDEM_REDUZIDA" -> {
                exigir(stopLoss && !p.acionada() && p.estado().equals("ATIVA"),"reducao perna");
                long reduzida=inteiro(d,"reduzida",1,1000000), restante=inteiro(d,"restante",1,1000000);
                Perna tp=irma(p);
                exigir(reduzida<p.restante() && restante==p.restante()-reduzida && restante==tp.quantidade()-tp.executada(),"reducao restante");
                em().createNativeQuery("UPDATE reserva_perna SET reduzida=reduzida+:q WHERE ordem=:o")
                        .setParameter("q",reduzida).setParameter("o",p.ordem()).executeUpdate();
            }
            case "STOP_ACIONADO" -> {
                exigir(stopLoss && !p.acionada() && p.estado().equals("ATIVA") && !irma(p).viva(),"stop acionamento");
                exigir(texto(d,"tipoExecutavel").equals(p.tipo().equals("STOP")?"MARKET":"LIMIT"),"tipo executavel");
                em().createNativeQuery("UPDATE reserva_perna SET stop_acionado=TRUE WHERE ordem=:o")
                        .setParameter("o",p.ordem()).executeUpdate();
            }
            case "OFERTA_NO_LIVRO" -> {
                exigir(p.viva() && (p.takeProfit() || p.tipo().equals("STOP_LIMIT") && p.acionada()),"oferta tipo");
                exigir(inteiro(d,"restante",1,1000000)==p.restante(),"oferta restante");
                exigir(p.limite()!=null && p.limite().compareTo(preco(d,"preco"))==0,"oferta preco");
                exigir(texto(d,"lado").equals(r.recurso().equals("CAIXA")?"COMPRA":"VENDA"),"oferta lado");
            }
            case "ORDEM_REJEITADA" -> {
                exigir(p.viva() && p.executada()==0 && p.reduzida()==0 && !p.acionada(),"rejeicao executada");
                exigir(!texto(d,"motivo").equals("LIMITE_STOPS_PENDENTES") || stopLoss || irma(p).viva(),"rejeicao stop");
                encerrarPerna(r,p,p.restante());
            }
            case "ORDEM_ENCERRADA" -> {
                exigir(p.viva(),"terminal divergente");
                exigir(inteiro(d,"executada",0,1000000)==p.executada(),"executada");
                exigir(inteiro(d,"cancelada",0,1000000)==p.restante(),"cancelada");
                String motivo=texto(d,"motivo");
                Perna outra=irma(p);
                switch (motivo) {
                    case "EXPIRADA_DIA" -> { }
                    case "CANCELADA_OCO" -> exigir(p.takeProfit() ? outra.viva() : !outra.viva() && !p.acionada() && p.executada()==0,"cancelamento oco");
                    case "LIMITE_CASCATA" -> exigir(stopLoss && !p.acionada() && p.executada()==0 && !outra.viva(),"cascata");
                    default -> {
                        exigir(p.takeProfit() ? Set.of("EXECUTADA","LIMITE_NEGOCIOS").contains(motivo) : p.acionada(),"terminal perna");
                        exigir(!motivo.equals("FOK_SEM_LIQUIDEZ"),"terminal FOK");
                        exigir(!motivo.equals("SALDO_CANCELADO_IOC") || p.tipo().equals("STOP"),"terminal IOC");
                        exigir(motivo.equals("EXECUTADA") == (p.restante()==0),"execucao terminal");
                    }
                }
                encerrarPerna(r,p,p.restante());
            }
            default -> throw new IllegalStateException("tipo validado na fronteira");
        }
    }

    private void encerrarPerna(Reserva r, Perna p, long cancelada) {
        em().createNativeQuery("UPDATE reserva_perna SET cancelada=cancelada+:c,estado='ENCERRADA' WHERE ordem=:o")
                .setParameter("c",cancelada).setParameter("o",p.ordem()).executeUpdate();
        if (!irma(p).viva()) {
            encerrar(r);
        }
    }

    // Grupo OCO não tem tipo próprio: os tipos ficam nas pernas.
    private static boolean stop(Reserva r) { return "STOP".equals(r.tipo()) || "STOP_LIMIT".equals(r.tipo()); }

    private void validar(Reserva r, JsonNode e,JsonNode d,String lado) {
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

    private Reserva reserva(UUID i) {
        List<?> linhas=em().createNativeQuery("""
                SELECT intencao,ordem,conta,ticker,recurso,tipo,validade,data_sessao,quantidade_inicial,
                quantidade_restante,preco_limite,preco_maximo,valor_restante,estado,preco_gatilho,stop_acionado,oco FROM reserva WHERE intencao=:i FOR UPDATE
                """).setParameter("i",i).getResultList();
        exigir(linhas.size()==1,"reserva ausente"); Object[] c=(Object[])linhas.getFirst();
        boolean oco=(Boolean)c[16];
        exigir((c[1]!=null || oco) && c[7]!=null && c[8]!=null && c[9]!=null,"reserva legada");
        LocalDate data=c[7] instanceof LocalDate l ? l : ((java.sql.Date)c[7]).toLocalDate();
        return new Reserva((UUID)c[0],(UUID)c[1],(String)c[2],(String)c[3],(String)c[4],(String)c[5],(String)c[6],data,
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
    private void consumirCompra(Reserva r,long q,br.prospero.mo.matching.helper.CalculoConsumo.Compra consumo) {
        BigDecimal custo=consumo.custo();
        BigDecimal retido=consumo.retido();
        int n=em().createNativeQuery("UPDATE conta_risco SET reservado=reservado-:r,total=total-:p,disponivel=disponivel+:r-:p WHERE conta=:c AND reservado>=:r AND total>=:p")
                .setParameter("r",retido).setParameter("p",custo).setParameter("c",r.conta()).executeUpdate();
        exigir(n==1,"controle caixa");
        em().createNativeQuery("UPDATE reserva SET quantidade_restante=quantidade_restante-:q,valor_restante=valor_restante-:v,estado='PARCIAL' WHERE intencao=:i")
                .setParameter("q",q).setParameter("v",retido).setParameter("i",r.intencao()).executeUpdate();
    }
    private void consumirVenda(Reserva r,long q) {
        int n=em().createNativeQuery("UPDATE custodia_risco SET reservado=reservado-:q,total=total-:q WHERE conta=:c AND ticker=:t AND reservado>=:q AND total>=:q")
                .setParameter("q",q).setParameter("c",r.conta()).setParameter("t",r.ticker()).executeUpdate();
        exigir(n==1,"controle custodia");
        em().createNativeQuery("UPDATE reserva SET quantidade_restante=quantidade_restante-:q,estado='PARCIAL' WHERE intencao=:i")
                .setParameter("q",q).setParameter("i",r.intencao()).executeUpdate();
    }
    private void encerrar(Reserva r) {
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
