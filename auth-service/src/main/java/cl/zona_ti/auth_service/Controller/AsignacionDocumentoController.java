package cl.zona_ti.auth_service.Controller;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import cl.zona_ti.auth_service.Dto.AsignacionDocumentoResponse;
import cl.zona_ti.auth_service.Dto.SubirDocumentoRequest;
import cl.zona_ti.auth_service.Model.AsignacionDocumento;
import cl.zona_ti.auth_service.Security.AuthenticatedPrincipal;
import cl.zona_ti.auth_service.Service.AsignacionDocumentoService;
import jakarta.validation.Valid;

// Sin @PreAuthorize a nivel de rol -- mismo criterio que AsignacionController
// (actualizarEstado/actualizarCotizacion): el permiso real (dueño de la fila,
// admin de su empresa, o GLOBAL) se resuelve dentro del service porque
// depende de DE QUIEN es la asignacion puntual, no del rol en abstracto.
@RestController
public class AsignacionDocumentoController {

    private final AsignacionDocumentoService documentoService;

    public AsignacionDocumentoController(AsignacionDocumentoService documentoService) {
        this.documentoService = documentoService;
    }

    @GetMapping("/auth/asignaciones/{asignacionId}/documentos")
    public ResponseEntity<List<AsignacionDocumentoResponse>> listar(
            @PathVariable Long asignacionId,
            @AuthenticationPrincipal AuthenticatedPrincipal actor
    ) {
        return ResponseEntity.ok(documentoService.listar(asignacionId, actor));
    }

    @PostMapping("/auth/asignaciones/{asignacionId}/documentos")
    public ResponseEntity<AsignacionDocumentoResponse> subir(
            @PathVariable Long asignacionId,
            @Valid @RequestBody SubirDocumentoRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal actor
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(documentoService.subir(asignacionId, request, actor));
    }

    // Descarga directa del binario -- mismo patron que los adjuntos de
    // licitacion en compra-service (Content-Disposition: attachment).
    @GetMapping("/auth/documentos/{id}")
    public ResponseEntity<byte[]> descargar(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedPrincipal actor
    ) {
        AsignacionDocumento documento = documentoService.obtenerParaDescargar(id, actor);
        MediaType tipo = documento.getTipoContenido() != null
                ? MediaType.parseMediaType(documento.getTipoContenido())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(tipo)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + documento.getNombreArchivo() + "\"")
                .body(documento.getContenido());
    }

    @DeleteMapping("/auth/documentos/{id}")
    public ResponseEntity<Void> eliminar(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedPrincipal actor
    ) {
        documentoService.eliminar(id, actor);
        return ResponseEntity.noContent().build();
    }
}
