package cl.zona_ti.auth_service.Service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import cl.zona_ti.auth_service.Dto.ActualizarPerfilRequest;
import cl.zona_ti.auth_service.Dto.PerfilBusquedaResponse;
import cl.zona_ti.auth_service.Model.Alcance;
import cl.zona_ti.auth_service.Model.Empresa;
import cl.zona_ti.auth_service.Model.PerfilBusqueda;
import cl.zona_ti.auth_service.Repository.EmpresaRepository;
import cl.zona_ti.auth_service.Repository.PerfilBusquedaRepository;
import cl.zona_ti.auth_service.Security.AuthenticatedPrincipal;
import jakarta.persistence.EntityNotFoundException;

@Service
public class PerfilBusquedaService {

    private final PerfilBusquedaRepository perfilBusquedaRepository;
    private final EmpresaRepository empresaRepository;

    public PerfilBusquedaService(PerfilBusquedaRepository perfilBusquedaRepository, EmpresaRepository empresaRepository) {
        this.perfilBusquedaRepository = perfilBusquedaRepository;
        this.empresaRepository = empresaRepository;
    }

    // Lo llama compra-service EN VIVO (ver PerfilClient alli), reenviando
    // el mismo Bearer token del usuario -- devuelve TODOS los filtros de la
    // empresa del usuario autenticado (antes era uno solo). Roles GLOBAL no
    // tienen empresa -- no tiene sentido que llamen esto, se rechaza
    // explicito en vez de devolver cualquier cosa (mismo criterio que
    // antes).
    public List<PerfilBusquedaResponse> misPerfiles(AuthenticatedPrincipal principal) {
        if (principal.empresaId() == null) {
            throw new IllegalArgumentException("El usuario autenticado no pertenece a una empresa");
        }
        return listarPorEmpresa(principal.empresaId());
    }

    // Panel de administracion (Mi Empresa / Administracion) -- GLOBAL
    // cualquier empresa, EMPRESA solo la propia (ver verificarPermiso).
    public List<PerfilBusquedaResponse> listar(Long empresaId, AuthenticatedPrincipal actor) {
        verificarPermiso(empresaId, actor);
        return listarPorEmpresa(empresaId);
    }

    private List<PerfilBusquedaResponse> listarPorEmpresa(Long empresaId) {
        return perfilBusquedaRepository.findByEmpresaIdOrderById(empresaId).stream()
                .map(this::toResponse)
                .toList();
    }

    public PerfilBusquedaResponse crear(Long empresaId, ActualizarPerfilRequest request, AuthenticatedPrincipal actor) {
        verificarPermiso(empresaId, actor);
        Empresa empresa = empresaRepository.findById(empresaId)
                .orElseThrow(() -> new EntityNotFoundException("Empresa no encontrada: " + empresaId));

        PerfilBusqueda perfil = new PerfilBusqueda();
        perfil.setEmpresa(empresa);
        aplicarDatos(perfil, request);

        return toResponse(perfilBusquedaRepository.save(perfil));
    }

    // "perfilId" identifica al filtro directamente -- a diferencia de
    // crear() no hace falta empresaId en la URL, el permiso se verifica
    // contra la empresa DUEÑA de ese filtro puntual (evita que alguien
    // edite el filtro de otra empresa mandando cualquier id).
    public PerfilBusquedaResponse actualizar(Long perfilId, ActualizarPerfilRequest request, AuthenticatedPrincipal actor) {
        PerfilBusqueda perfil = obtenerPerfil(perfilId);
        verificarPermiso(perfil.getEmpresa().getId(), actor);
        aplicarDatos(perfil, request);
        return toResponse(perfilBusquedaRepository.save(perfil));
    }

    public void eliminar(Long perfilId, AuthenticatedPrincipal actor) {
        PerfilBusqueda perfil = obtenerPerfil(perfilId);
        verificarPermiso(perfil.getEmpresa().getId(), actor);
        perfilBusquedaRepository.delete(perfil);
    }

    private void aplicarDatos(PerfilBusqueda perfil, ActualizarPerfilRequest request) {
        perfil.setNombre(request.getNombre());
        perfil.setRubro(request.getRubro());
        perfil.setPalabrasClave(request.getPalabrasClave());
        perfil.setRegionCodigo(request.getRegionCodigo());
        perfil.setRegionNombre(request.getRegionNombre());
        perfil.setPerfilCompletado(true);
        perfil.setActualizadoEn(LocalDateTime.now());
    }

    private PerfilBusqueda obtenerPerfil(Long perfilId) {
        return perfilBusquedaRepository.findById(perfilId)
                .orElseThrow(() -> new EntityNotFoundException("Filtro no encontrado: " + perfilId));
    }

    private void verificarPermiso(Long empresaId, AuthenticatedPrincipal actor) {
        if (actor.alcance() == Alcance.GLOBAL) {
            return;
        }
        if (!empresaId.equals(actor.empresaId())) {
            throw new AccessDeniedException("No podes administrar el perfil de otra empresa");
        }
    }

    private PerfilBusquedaResponse toResponse(PerfilBusqueda perfil) {
        return new PerfilBusquedaResponse(
                perfil.getId(),
                perfil.getEmpresa().getId(),
                perfil.getNombre(),
                perfil.getRubro(),
                perfil.getPalabrasClave(),
                perfil.getRegionCodigo(),
                perfil.getRegionNombre());
    }
}
