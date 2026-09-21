package cl.zona_ti.compra_service.Security;

import cl.zona_ti.compra_service.Model.Alcance;

// Copia deliberada de auth-service/Security/AuthenticatedPrincipal.java --
// ver Alcance.java para el porque no se comparte codigo entre servicios.
public record AuthenticatedPrincipal(
        String username,
        String rol,
        Alcance alcance,
        Long empresaId
) {
}
