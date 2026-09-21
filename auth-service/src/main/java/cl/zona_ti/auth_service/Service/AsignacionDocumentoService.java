package cl.zona_ti.auth_service.Service;

import java.util.Base64;
import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import cl.zona_ti.auth_service.Dto.AsignacionDocumentoResponse;
import cl.zona_ti.auth_service.Dto.SubirDocumentoRequest;
import cl.zona_ti.auth_service.Model.Alcance;
import cl.zona_ti.auth_service.Model.Asignacion;
import cl.zona_ti.auth_service.Model.AsignacionDocumento;
import cl.zona_ti.auth_service.Repository.AsignacionDocumentoRepository;
import cl.zona_ti.auth_service.Repository.AsignacionRepository;
import cl.zona_ti.auth_service.Security.AuthenticatedPrincipal;
import jakarta.persistence.EntityNotFoundException;

// Documentos que se suben a mano en la fase ANALISIS de una asignacion (ver
// CotizacionCompraAgil.jsx, "Documentación necesaria"). El permiso es el
// MISMO que para tocar el resto de esa asignacion (estado, cotizacion,
// detalle de desarrollo) -- ver AsignacionService.verificarPuedeActualizarEstado,
// replicado aca porque ese metodo es privado y cada service de este
// proyecto arma su propio chequeo (mismo patron que UsuarioService,
// EmpresaService, MarcaCotizacionService, etc.).
@Service
public class AsignacionDocumentoService {

    // Mas generoso que el limite del logo (300 KB, para un membrete chico)
    // -- esto son documentos reales (certificados, boletas, PDFs), pero
    // sigue acotado para no dejar subir cualquier cosa sin limite.
    private static final int PESO_MAX_BYTES = 10_000_000; // 10 MB

    private final AsignacionDocumentoRepository documentoRepository;
    private final AsignacionRepository asignacionRepository;

    public AsignacionDocumentoService(AsignacionDocumentoRepository documentoRepository, AsignacionRepository asignacionRepository) {
        this.documentoRepository = documentoRepository;
        this.asignacionRepository = asignacionRepository;
    }

    public List<AsignacionDocumentoResponse> listar(Long asignacionId, AuthenticatedPrincipal actor) {
        verificarPermiso(obtenerAsignacion(asignacionId), actor);
        return documentoRepository.listarPorAsignacion(asignacionId);
    }

    public AsignacionDocumentoResponse subir(Long asignacionId, SubirDocumentoRequest request, AuthenticatedPrincipal actor) {
        Asignacion asignacion = obtenerAsignacion(asignacionId);
        verificarPermiso(asignacion, actor);

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(request.getContenidoBase64());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("El archivo no es un base64 válido");
        }
        if (bytes.length > PESO_MAX_BYTES) {
            throw new IllegalArgumentException("El archivo pesa demasiado (máx. " + (PESO_MAX_BYTES / 1_000_000) + " MB)");
        }

        AsignacionDocumento documento = new AsignacionDocumento();
        documento.setAsignacion(asignacion);
        documento.setNombreArchivo(request.getNombreArchivo());
        documento.setTipoContenido(request.getTipoContenido());
        documento.setTamanoBytes(bytes.length);
        documento.setContenido(bytes);

        AsignacionDocumento guardado = documentoRepository.save(documento);
        return new AsignacionDocumentoResponse(guardado.getId(), guardado.getNombreArchivo(), guardado.getTamanoBytes());
    }

    public AsignacionDocumento obtenerParaDescargar(Long documentoId, AuthenticatedPrincipal actor) {
        AsignacionDocumento documento = obtenerDocumento(documentoId);
        verificarPermiso(documento.getAsignacion(), actor);
        return documento;
    }

    public void eliminar(Long documentoId, AuthenticatedPrincipal actor) {
        AsignacionDocumento documento = obtenerDocumento(documentoId);
        verificarPermiso(documento.getAsignacion(), actor);
        documentoRepository.delete(documento);
    }

    private Asignacion obtenerAsignacion(Long asignacionId) {
        return asignacionRepository.findById(asignacionId)
                .orElseThrow(() -> new EntityNotFoundException("Asignación no encontrada: " + asignacionId));
    }

    private AsignacionDocumento obtenerDocumento(Long documentoId) {
        return documentoRepository.findById(documentoId)
                .orElseThrow(() -> new EntityNotFoundException("Documento no encontrado: " + documentoId));
    }

    // Identico criterio a AsignacionService.verificarPuedeActualizarEstado:
    // GLOBAL, el propio dueño de la fila, o el ADMIN_EMPRESA de su empresa.
    private void verificarPermiso(Asignacion asignacion, AuthenticatedPrincipal actor) {
        if (actor.alcance() == Alcance.GLOBAL) {
            return;
        }
        if (asignacion.getUsuario().getUsername().equals(actor.username())) {
            return;
        }
        Long empresaAsignacion = asignacion.getUsuario().getEmpresa() != null
                ? asignacion.getUsuario().getEmpresa().getId()
                : null;
        if (actor.alcance() == Alcance.EMPRESA && empresaAsignacion != null && empresaAsignacion.equals(actor.empresaId())) {
            return;
        }
        throw new AccessDeniedException("No podés gestionar los documentos de esta asignación");
    }
}
