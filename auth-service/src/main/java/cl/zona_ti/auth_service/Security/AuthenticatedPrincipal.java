package cl.zona_ti.auth_service.Security;

import cl.zona_ti.auth_service.Model.Alcance;

// Lo que queda como "principal" en el SecurityContext despues de validar
// el JWT (ver JwtAuthenticationFilter) -- controllers/services lo reciben
// con @AuthenticationPrincipal en vez de tener que ir a la BD de nuevo a
// buscar el usuario que ya viaja (firmado) en el token.
public record AuthenticatedPrincipal(
        String username,
        String rol,
        Alcance alcance,
        Long empresaId
) {
}
