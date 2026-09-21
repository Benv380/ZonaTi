package cl.zona_ti.auth_service.Dto;

// A diferencia de ChileCompraCredencialesResponse, esta SI lleva la
// contraseña -- ya descifrada, lista para usar. Solo la devuelve
// ChileCompraCredencialesController en su endpoint dedicado "/secreto" --
// pensado para que lo llame exclusivamente el flujo de Power Automate,
// nunca la pantalla de Mi Empresa (esa usa ChileCompraCredencialesResponse,
// sin el secreto).
public record ChileCompraCredencialesSecretoResponse(
        String chileCompraUsuario,
        String chileCompraPassword
) {
}
