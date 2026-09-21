package cl.zona_ti.auth_service.Dto;

// Version liviana de UsuarioResponse -- lo que necesita el selector de
// "a quien recomendar" (ver GET /auth/me/companeros): gente de tu MISMA
// empresa, sin exponer email/lo demas que si trae UsuarioResponse (eso
// queda reservado para los paneles de administracion).
public record CompaneroResponse(Long id, String username, String rol) {
}
