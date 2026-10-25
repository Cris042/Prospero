package br.prospero.fo.ordens.dominio;

import java.util.Optional;
import java.util.UUID;

public interface RepositorioTrajetoria {
    Optional<PaginaTrajetoria> consultar(String conta, UUID ordem, long apos, int limite);
}
