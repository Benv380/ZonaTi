package cl.zona_ti.auth_service.Dto;

// Nunca incluye el password (ni el hash) -- este es el DTO que se
// devuelve al cliente, a diferencia de la entidad User completa.
//
// "requiereSupervision": SOLO va en true/false real cuando lo arma
// UsuarioService.toResponse() (usado por los endpoints admin-only --
// crear/listarPorEmpresa/listarTodos/actualizar). UsuarioService.miPerfil()
// NO usa ese helper a proposito, siempre manda false aca -- el propio
// usuario marcado nunca debe poder ver que lo estan supervisando.
public record UsuarioResponse(
        Long id,
        String name,
        String lastName,
        String email,
        String username,
        String rol,
        Long empresaId,
        boolean requiereSupervision
) {
}
