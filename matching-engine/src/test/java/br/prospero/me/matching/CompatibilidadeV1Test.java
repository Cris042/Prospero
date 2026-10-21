package br.prospero.me.matching;

import br.prospero.me.matching.helper.EnvelopeMatching;
import br.prospero.me.matching.helper.SessaoMatching;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Fixture capturada executando o serializer da base ee0f776, antes da integracao de stops. */
class CompatibilidadeV1Test {
    @Test void logHistoricoMarketLimitConservaBytesCompletos() throws Exception {
        String[][] log={ {"VENDA","LIMIT","DIA","3","10.00"}, {"VENDA","LIMIT","DIA","2","9.90"},
                {"COMPRA","MARKET","IOC","4",null},{"COMPRA","LIMIT","IOC","3","10.00"},
                {"COMPRA","LIMIT","DIA","3","9.90"},{"VENDA","MARKET","IOC","4",null},
                {"COMPRA","LIMIT","FOK","4","10.00"} };
        var json=new ObjectMapper();var sessao=new SessaoMatching();
        var digest=java.security.MessageDigest.getInstance("SHA-256");int eventos=0;
        for(int i=0;i<log.length;i++) {
            var l=log[i]; UUID ordem=UUID.nameUUIDFromBytes(("fixture-v1-ordem-"+i).getBytes(StandardCharsets.UTF_8));
            UUID intencao=UUID.nameUUIDFromBytes(("fixture-v1-intencao-"+i).getBytes(StandardCharsets.UTF_8));
            ObjectNode comando=(ObjectNode)json.readTree(EnvelopeMatchingTest.comando(ordem,intencao,l[0],l[4]==null?"10.00":l[4],"2026-10-18"));
            ObjectNode d=(ObjectNode)comando.get("dados"); d.put("tipo",l[1]);d.put("validade",l[2]);d.put("quantidade",Integer.parseInt(l[3]));
            d.put("precoLimite",l[4]);if(l[1].equals("MARKET")&&l[0].equals("COMPRA"))d.put("precoMaximo","11.00");
            for(byte[] evento:sessao.processar(EnvelopeMatching.comando(json.writeValueAsBytes(comando),"ALFA3"))) {
                digest.update(evento); digest.update((byte)'\n');eventos++;
                assertEquals(1,json.readTree(evento).path("versao").asInt()); assertFalse(json.readTree(evento).has("causacao"));
            }
        }
        String hash=java.util.HexFormat.of().formatHex(digest.digest());
        assertEquals(21,eventos);
        assertEquals("7ccb90b97843c637b35a8652301727628ab5627bded6b5269b2c9c8f54c3eeef",hash);
    }
}
