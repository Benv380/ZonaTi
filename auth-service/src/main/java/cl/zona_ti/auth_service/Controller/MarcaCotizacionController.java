package cl.zona_ti.auth_service.Controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import cl.zona_ti.auth_service.Dto.ActualizarMarcaCotizacionRequest;
import cl.zona_ti.auth_service.Dto.MarcaCotizacionResponse;
import cl.zona_ti.auth_service.Model.Empresa;
import cl.zona_ti.auth_service.Security.AuthenticatedPrincipal;
import cl.zona_ti.auth_service.Service.MarcaCotizacionService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;

// "Base de cotizacion" (logo + condiciones comerciales) de una empresa --
// distinto de EmpresaController (exclusivo GLOBAL): esto lo puede VER
// cualquiera de la empresa, y EDITAR el ADMIN_EMPRESA de esa empresa
// (ademas de GLOBAL). Por eso vive en su propio controller, no adentro
// del que ya tiene @PreAuthorize("hasRole('GLOBAL')") a nivel de clase.
@RestController
public class MarcaCotizacionController {

    private final MarcaCotizacionService marcaCotizacionService;

    public MarcaCotizacionController(MarcaCotizacionService marcaCotizacionService) {
        this.marcaCotizacionService = marcaCotizacionService;
    }

    // Sin @PreAuthorize de rol -- lo puede ver GLOBAL o cualquier alcance
    // de la MISMA empresa (EMPRESA o USUARIO), el permiso real se resuelve
    // en el service porque depende de DE QUIEN es la empresa, no del rol
    // en abstracto.
    @GetMapping("/auth/empresas/{empresaId}/marca-cotizacion")
    public ResponseEntity<MarcaCotizacionResponse> obtener(
            @PathVariable Long empresaId,
            @AuthenticationPrincipal AuthenticatedPrincipal solicitante
    ) {
        return ResponseEntity.ok(marcaCotizacionService.obtener(empresaId, solicitante));
    }

    // GLOBAL o EMPRESA (ADMIN_EMPRESA) -- nunca un USUARIO llano, aunque
    // sea de la misma empresa (el service repite el chequeo, ver
    // verificarPuedeEditar).
    @PutMapping("/auth/empresas/{empresaId}/marca-cotizacion")
    @PreAuthorize("hasAnyRole('GLOBAL','EMPRESA')")
    public ResponseEntity<MarcaCotizacionResponse> actualizar(
            @PathVariable Long empresaId,
            @Valid @RequestBody ActualizarMarcaCotizacionRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal actor
    ) {
        return ResponseEntity.ok(marcaCotizacionService.actualizar(empresaId, request, actor));
    }

    // Mismo patron que EmpresaController.obtenerLogo, pero con el permiso
    // de "cualquiera de la misma empresa" en vez de exclusivo GLOBAL.
    @GetMapping("/auth/empresas/{empresaId}/marca-cotizacion/logo")
    public ResponseEntity<byte[]> obtenerLogo(
            @PathVariable Long empresaId,
            @AuthenticationPrincipal AuthenticatedPrincipal solicitante
    ) {
        Empresa empresa = marcaCotizacionService.obtenerParaLogo(empresaId, solicitante);
        if (empresa.getLogo() == null) {
            throw new EntityNotFoundException("La empresa " + empresaId + " no tiene logo cargado");
        }
        MediaType tipo = empresa.getLogoTipoContenido() != null
                ? MediaType.parseMediaType(empresa.getLogoTipoContenido())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(tipo)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(empresa.getLogo());
    }
}
