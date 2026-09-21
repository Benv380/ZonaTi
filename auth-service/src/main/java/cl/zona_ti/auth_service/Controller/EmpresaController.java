package cl.zona_ti.auth_service.Controller;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cl.zona_ti.auth_service.Dto.CrearEmpresaRequest;
import cl.zona_ti.auth_service.Dto.EmpresaResponse;
import cl.zona_ti.auth_service.Model.Empresa;
import cl.zona_ti.auth_service.Service.EmpresaService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;

// Exclusivo de rol GLOBAL (ADMIN) -- gestion de empresas
// es el nivel mas alto de administracion, un ADMIN_EMPRESA solo administra
// SU empresa (ver UsuarioController/AsignacionController/PerfilBusquedaController).
@RestController
@RequestMapping("/auth/empresas")
@PreAuthorize("hasRole('GLOBAL')")
public class EmpresaController {

    private final EmpresaService empresaService;

    public EmpresaController(EmpresaService empresaService) {
        this.empresaService = empresaService;
    }

    @PostMapping
    public ResponseEntity<EmpresaResponse> crear(@Valid @RequestBody CrearEmpresaRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(empresaService.crear(request));
    }

    @GetMapping
    public ResponseEntity<List<EmpresaResponse>> listar() {
        return ResponseEntity.ok(empresaService.listar());
    }

    // Una sola empresa -- usado por el panel de detalle (ver
    // EmpresaDetalle.jsx en el front), que puede llegar por link directo o
    // F5 sin tener ya la lista completa cargada en memoria.
    @GetMapping("/{id}")
    public ResponseEntity<EmpresaResponse> obtener(@PathVariable Long id) {
        return ResponseEntity.ok(empresaService.obtener(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmpresaResponse> actualizar(@PathVariable Long id, @Valid @RequestBody CrearEmpresaRequest request) {
        return ResponseEntity.ok(empresaService.actualizar(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(@PathVariable Long id) {
        empresaService.eliminar(id);
        return ResponseEntity.noContent().build();
    }

    // El logo se sube como parte del PUT/POST normal (ver
    // CrearEmpresaRequest.logoBase64) -- este endpoint es solo para
    // BAJARLO, aparte, para no tener que mandar el binario en cada
    // GET /auth/empresas. Devuelve 404 si la empresa no tiene logo
    // cargado todavia (distinto de "empresa no existe", que ya tira 404
    // desde obtenerParaLogo -> EntityNotFoundException).
    @GetMapping("/{id}/logo")
    public ResponseEntity<byte[]> obtenerLogo(@PathVariable Long id) {
        Empresa empresa = empresaService.obtenerParaLogo(id);
        if (empresa.getLogo() == null) {
            throw new EntityNotFoundException("La empresa " + id + " no tiene logo cargado");
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
