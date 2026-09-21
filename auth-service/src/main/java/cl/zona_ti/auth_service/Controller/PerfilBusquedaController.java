package cl.zona_ti.auth_service.Controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    // puede pedir el perfil de SU propia empresa, nunca de otra (no recibe
    // un empresaId, lo saca del propio JWT).
    @GetMapping("/auth/perfil/me")
    public ResponseEntity<PerfilBusquedaResponse> miPerfil(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ResponseEntity.ok(perfilBusquedaService.miPerfil(principal));
    }

    // Seteo manual (ver comentario en ActualizarPerfilRequest -- el
    // onboarding con LLM, cuando se conecte, va a pegarle a este mismo
    // service method). GLOBAL o EMPRESA -- EMPRESA solo su propia empresa
    // (ver PerfilBusquedaService.verificarPermiso).
    @PutMapping("/auth/empresas/{empresaId}/perfil")
    @PreAuthorize("hasAnyRole('GLOBAL','EMPRESA')")
    public ResponseEntity<PerfilBusquedaResponse> actualizar(
            @PathVariable Long empresaId,
            @Valid @RequestBody ActualizarPerfilRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal actor
    ) {
        return ResponseEntity.ok(perfilBusquedaService.actualizar(empresaId, request, actor));
    }
}
