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
                          BigDecimal limite, BigDecimal teto, BigDecimal valor, String estado) { }

    @Transactional(Transactional.TxType.MANDATORY)
    public void aplicar(JsonNode evento) {
        JsonNode d=evento.get("dados");
        boolean negocio=texto(evento,"tipo").equals("NEGOCIO");
        var contas= negocio ? java.util.stream.Stream.of(texto(d,"contaCompra"),texto(d,"contaVenda"))
                : java.util.stream.Stream.of(texto(d,"conta"));
        contas.distinct().sorted().forEach(c -> em().createNativeQuery("SELECT conta FROM conta_risco WHERE conta=:c FOR UPDATE").setParameter("c",c).getResultList());
        String hash=assinatura(evento);
        if (duplicado("matching_evento","id",uuid(evento,"id"),hash)) return;
        exigir(!em().createNativeQuery("SELECT 1 FROM reserva WHERE admissao=:a AND ticker=:t AND data_sessao=:s")
                .setParameter("a",uuid(evento,"correlacao")).setParameter("t",texto(evento,"ticker"))
                .setParameter("s",LocalDate.parse(texto(evento,"dataSessao"))).getResultList().isEmpty(),"correlacao");
        if (negocio) {
            // A assinatura do negócio ignora envelope/id; nomes e valores dos dados são o contrato financeiro.
            String assinaturaNegocio=assinatura(d);
            String agressor=texto(d,"agressor").equals("COMPRA")?"Compra":"Venda";
            exigir(uuid(evento,"correlacao").equals(br.prospero.mo.risco.helper.EnvelopeAdmissao.id(uuid(d,"intencao"+agressor))),"agressor correlacao");
            if (!duplicado("matching_negocio","negocio",texto(d,"negocio"),assinaturaNegocio)) {
                Reserva compra=reserva(uuid(d,"intencaoCompra"));
                Reserva venda=reserva(uuid(d,"intencaoVenda"));
                exigir(!compra.ordem().equals(venda.ordem()),"ordens distintas");
                validar(compra,evento,d,"Compra"); validar(venda,evento,d,"Venda");
                long q=inteiro(d,"quantidade",1,1000000); BigDecimal p=preco(d,"preco");
                exigir(q<=compra.restante() && q<=venda.restante(),"restante");
                exigir(p.compareTo(compra.teto())<=0,"teto compra");
                exigir(compra.limite()==null || p.compareTo(compra.limite())<=0,"limite compra");
                exigir(venda.limite()==null || p.compareTo(venda.limite())>=0,"limite venda");
                var consumo=br.prospero.mo.matching.helper.CalculoConsumo.compra(compra.restante(),q,compra.teto(),p,compra.valor());
                consumirCompra(compra,q,consumo); consumirVenda(venda,q);
                inserir("matching_negocio","negocio",texto(d,"negocio"),assinaturaNegocio);
            }
        } else {
            Reserva r=reserva(uuid(d,"intencao"));
            validar(r,evento,d,"");
            switch(texto(evento,"tipo")) {
                case "ORDEM_REJEITADA" -> { exigir(uuid(evento,"correlacao").equals(br.prospero.mo.risco.helper.EnvelopeAdmissao.id(r.intencao())),"rejeicao correlacao"); exigir(r.inicial()==r.restante(),"rejeicao executada"); encerrar(r); }
                case "ORDEM_ENCERRADA" -> {
                    exigir(inteiro(d,"executada",0,1000000)==r.inicial()-r.restante(),"executada");
                    exigir(inteiro(d,"cancelada",0,1000000)==r.restante(),"cancelada");
                    String motivo=texto(d,"motivo");
                    exigir(!motivo.equals("FOK_SEM_LIQUIDEZ") || r.validade().equals("FOK") && r.inicial()==r.restante(),"terminal FOK");
                    exigir(!motivo.equals("SALDO_CANCELADO_IOC") || r.validade().equals("IOC"),"terminal IOC");
                    exigir(texto(d,"motivo").equals("EXECUTADA") == (r.restante()==0),"execucao terminal");
                    encerrar(r);
                }
                case "OFERTA_NO_LIVRO" -> {
                    exigir(r.estado().equals("ATIVA") || r.estado().equals("PARCIAL"),"reserva encerrada");
                    exigir(inteiro(d,"restante",1,1000000)==r.restante(),"oferta restante");
                    exigir(r.tipo().equals("LIMIT") && r.validade().equals("DIA"),"oferta tipo");
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

    private void validar(Reserva r, JsonNode e,JsonNode d,String lado) {
        exigir(r.ordem()!=null && r.ordem().equals(uuid(d,"ordem"+lado)) && r.conta().equals(texto(d,"conta"+lado)),"identidade reserva");
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
                quantidade_restante,preco_limite,preco_maximo,valor_restante,estado FROM reserva WHERE intencao=:i FOR UPDATE
                """).setParameter("i",i).getResultList();
        exigir(linhas.size()==1,"reserva ausente"); Object[] c=(Object[])linhas.getFirst();
        exigir(c[1]!=null && c[7]!=null && c[8]!=null && c[9]!=null,"reserva legada");
        LocalDate data=c[7] instanceof LocalDate l ? l : ((java.sql.Date)c[7]).toLocalDate();
        return new Reserva((UUID)c[0],(UUID)c[1],(String)c[2],(String)c[3],(String)c[4],(String)c[5],(String)c[6],data,
                ((Number)c[8]).longValue(),((Number)c[9]).longValue(),(BigDecimal)c[10],(BigDecimal)c[11],(BigDecimal)c[12],(String)c[13]);
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
