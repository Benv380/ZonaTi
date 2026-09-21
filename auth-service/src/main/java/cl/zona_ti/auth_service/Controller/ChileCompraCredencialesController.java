package cl.zona_ti.auth_service.Controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import cl.zona_ti.auth_service.Dto.ActualizarChileCompraCredencialesRequest;
import cl.zona_ti.auth_service.Dto.ChileCompraCredencialesResponse;
import cl.zona_ti.auth_service.Dto.ChileCompraCredencialesSecretoResponse;
import cl.zona_ti.auth_service.Security.AuthenticatedPrincipal;
import cl.zona_ti.auth_service.Service.ChileCompraCredencialesService;
import jakarta.validation.Valid;

// Credenciales de Chile Compra para el flujo de Power Automate. A
// diferencia de MarcaCotizacionController (que deja ver a cualquier
// USUARIO de la empresa), acá los 3 endpoints son exclusivo GLOBAL/EMPRESA
// -- no hay motivo para que un USER llano vea ni siquiera si estan
// configuradas.
@RestController
public class ChileCompraCredencialesController {

    private final ChileCompraCredencialesService chileCompraCredencialesService;

    public ChileCompraCredencialesController(ChileCompraCredencialesService chileCompraCredencialesService) {
        this.chileCompraCredencialesService = chileCompraCredencialesService;
    }

    @GetMapping("/auth/empresas/{empresaId}/chile-compra-credenciales")
    @PreAuthorize("hasAnyRole('GLOBAL','EMPRESA')")
    public ResponseEntity<ChileCompraCredencialesResponse> obtener(
            @PathVariable Long empresaId,
            @AuthenticationPrincipal AuthenticatedPrincipal solicitante
    ) {
        return ResponseEntity.ok(chileCompraCredencialesService.obtener(empresaId, solicitante));
    }

    @PutMapping("/auth/empresas/{empresaId}/chile-compra-credenciales")
    @PreAuthorize("hasAnyRole('GLOBAL','EMPRESA')")
    public ResponseEntity<ChileCompraCredencialesResponse> actualizar(
            @PathVariable Long empresaId,
            @Valid @RequestBody ActualizarChileCompraCredencialesRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal actor
    ) {
        return ResponseEntity.ok(chileCompraCredencialesService.actualizar(empresaId, request, actor));
    }

    // Devuelve la contraseña YA DESCIFRADA -- pensado exclusivamente para
    // que lo llame la cuenta de automatizacion del flujo de Power
    // Automate, nunca la pantalla de Mi Empresa (esa usa el GET de
    // arriba, sin el secreto).
    @GetMapping("/auth/empresas/{empresaId}/chile-compra-credenciales/secreto")
    @PreAuthorize("hasAnyRole('GLOBAL','EMPRESA')")
    public ResponseEntity<ChileCompraCredencialesSecretoResponse> obtenerSecreto(
            @PathVariable Long empresaId,
            @AuthenticationPrincipal AuthenticatedPrincipal solicitante
    ) {
        return ResponseEntity.ok(chileCompraCredencialesService.obtenerSecreto(empresaId, solicitante));
    }
}
