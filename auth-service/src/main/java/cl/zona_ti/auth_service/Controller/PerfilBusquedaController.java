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
import org.springframework.web.bind.annotation.RestController;

import cl.zona_ti.auth_service.Dto.ActualizarPerfilRequest;
import cl.zona_ti.auth_service.Dto.PerfilBusquedaResponse;
import cl.zona_ti.auth_service.Security.AuthenticatedPrincipal;
import cl.zona_ti.auth_service.Service.PerfilBusquedaService;
import jakarta.validation.Valid;

@RestController
public class PerfilBusquedaController {

    private final PerfilBusquedaService perfilBusquedaService;

    public PerfilBusquedaController(PerfilBusquedaService perfilBusquedaService) {
        this.perfilBusquedaService = perfilBusquedaService;
    }

    // La consulta compra-service EN VIVO (ver PerfilClient alli),
    // reenviando el mismo Bearer token del usuario -- por eso alcanza con
    // "authenticated()" (cualquier rol EMPRESA/USUARIO): cada quien solo
    // puede pedir los filtros de SU propia empresa, nunca de otra (no
    // recibe un empresaId, lo saca del propio JWT). Devuelve TODOS los
    // filtros (multi-filtro, ver PerfilBusquedaService).
    @GetMapping("/auth/perfil/me")
    public ResponseEntity<List<PerfilBusquedaResponse>> misPerfiles(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ResponseEntity.ok(perfilBusquedaService.misPerfiles(principal));
    }

    // Panel de administracion (Mi Empresa) -- GLOBAL cualquier empresa,
    // EMPRESA solo la propia (ver PerfilBusquedaService.verificarPermiso).
    @GetMapping("/auth/empresas/{empresaId}/perfiles")
    @PreAuthorize("hasAnyRole('GLOBAL','EMPRESA')")
    public ResponseEntity<List<PerfilBusquedaResponse>> listar(
            @PathVariable Long empresaId,
            @AuthenticationPrincipal AuthenticatedPrincipal actor
    ) {
        return ResponseEntity.ok(perfilBusquedaService.listar(empresaId, actor));
    }

    @PostMapping("/auth/empresas/{empresaId}/perfiles")
    @PreAuthorize("hasAnyRole('GLOBAL','EMPRESA')")
    public ResponseEntity<PerfilBusquedaResponse> crear(
            @PathVariable Long empresaId,
            @Valid @RequestBody ActualizarPerfilRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal actor
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(perfilBusquedaService.crear(empresaId, request, actor));
    }

    // Sin empresaId en la URL a proposito -- el permiso se verifica contra
    // la empresa DUEÑA del filtro (ver PerfilBusquedaService.actualizar),
    // no contra lo que mande el cliente.
    @PutMapping("/auth/perfiles/{perfilId}")
    @PreAuthorize("hasAnyRole('GLOBAL','EMPRESA')")
    public ResponseEntity<PerfilBusquedaResponse> actualizar(
            @PathVariable Long perfilId,
            @Valid @RequestBody ActualizarPerfilRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal actor
    ) {
        return ResponseEntity.ok(perfilBusquedaService.actualizar(perfilId, request, actor));
    }

    @DeleteMapping("/auth/perfiles/{perfilId}")
    @PreAuthorize("hasAnyRole('GLOBAL','EMPRESA')")
    public ResponseEntity<Void> eliminar(
            @PathVariable Long perfilId,
            @AuthenticationPrincipal AuthenticatedPrincipal actor
    ) {
        perfilBusquedaService.eliminar(perfilId, actor);
        return ResponseEntity.noContent().build();
    }
}
