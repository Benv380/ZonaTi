package cl.zona_ti.auth_service.Dto;

// De solo metadata -- NUNCA lleva la contraseña, ni cifrada ni
// descifrada. Sirve para que un admin confirme si ya hay credenciales
// cargadas y cual es el usuario, sin exponer el secreto en cada GET
// general. El secreto de verdad solo sale por
// ChileCompraCredencialesSecretoResponse (endpoint aparte, uso exclusivo
// del flujo de Power Automate).
public record ChileCompraCredencialesResponse(
        Long empresaId,
        String chileCompraUsuario,
        boolean configurado
) {
}
