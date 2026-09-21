package cl.zona_ti.auth_service.Controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cl.zona_ti.auth_service.Dto.ActualizarUsuarioRequest;
import cl.zona_ti.auth_service.Dto.CompaneroResponse;
import cl.zona_ti.auth_service.Dto.CrearUsuarioRequest;
import cl.zona_ti.auth_service.Dto.UsuarioResponse;
import cl.zona_ti.auth_service.Security.AuthenticatedPrincipal;
import cl.zona_ti.auth_service.Service.UsuarioService;
import jakarta.validation.Valid;

@RestController
public class UsuarioController {

    private final UsuarioService usuarioService;

    public UsuarioController(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    // GLOBAL (ADMIN) puede crear cualquier usuario, con
    // cualquier rol/empresa. EMPRESA (ADMIN_EMPRESA) tambien puede crear
    // usuarios, pero UsuarioService.crear() ignora el rol/empresa que
    // mande en el body y fuerza USER + su propia empresa -- ver el
    // comentario alli para el motivo (evitar auto-escalada de privilegios).
    @PostMapping("/auth/usuarios")
    @PreAuthorize("hasAnyRole('GLOBAL','EMPRESA')")
    public ResponseEntity<UsuarioResponse> crear(
            @Valid @RequestBody CrearUsuarioRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal creador
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(usuarioService.crear(request, creador));
    }

    // Panel de gestion de un ADMIN_EMPRESA (o GLOBAL viendo cualquier
    // empresa) -- lista los usuarios de esa empresa.
    @GetMapping("/auth/empresas/{empresaId}/usuarios")
    @PreAuthorize("hasAnyRole('GLOBAL','EMPRESA')")
    public ResponseEntity<List<UsuarioResponse>> listarPorEmpresa(
            @PathVariable Long empresaId,
            @AuthenticationPrincipal AuthenticatedPrincipal solicitante
    ) {
        return ResponseEntity.ok(usuarioService.listarPorEmpresa(empresaId, solicitante));
    }

    // Perfil propio (nombre completo, correo) -- cualquier autenticado. El
    // JWT no trae esto (solo username/rol/alcance/empresaId), y hace falta
    // para cosas como el "Vendedor" de una cotizacion generada.
    @GetMapping("/auth/me")
    public ResponseEntity<UsuarioResponse> miPerfil(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ResponseEntity.ok(usuarioService.miPerfil(principal));
    }

    // Selector de "a quien recomendar" (ver AsignacionController.recomendar)
    // -- cualquier autenticado con empresa (EMPRESA/USUARIO) ve a su gente.
    @GetMapping("/auth/me/companeros")
    public ResponseEntity<List<CompaneroResponse>> misCompaneros(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ResponseEntity.ok(usuarioService.listarCompaneros(principal));
    }

    // Panel de administracion general -- exclusivo GLOBAL, todos los
    // usuarios del sistema sin importar empresa.
    @GetMapping("/auth/usuarios")
    @PreAuthorize("hasRole('GLOBAL')")
    public ResponseEntity<List<UsuarioResponse>> listarTodos() {
        return ResponseEntity.ok(usuarioService.listarTodos());
    }

    @PutMapping("/auth/usuarios/{id}")
    @PreAuthorize("hasAnyRole('GLOBAL','EMPRESA')")
    public ResponseEntity<UsuarioResponse> actualizar(
            @PathVariable Long id,
            @Valid @RequestBody ActualizarUsuarioRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal actor
    ) {
        return ResponseEntity.ok(usuarioService.actualizar(id, request, actor));
    }

    @DeleteMapping("/auth/usuarios/{id}")
    @PreAuthorize("hasAnyRole('GLOBAL','EMPRESA')")
    public ResponseEntity<Void> eliminar(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedPrincipal actor
    ) {
        usuarioService.eliminar(id, actor);
        return ResponseEntity.noContent().build();
    }
}
