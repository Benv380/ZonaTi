package cl.zona_ti.auth_service.Service;

import java.time.LocalDateTime;

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

    // Lo llama compra-service EN VIVO (ver PerfilClient/CompraAgilService
    // alli) en cada busqueda de un usuario EMPRESA/USUARIO, para armar el
    // q=/region= automaticamente en vez de confiar en lo que mande el
    // cliente. Roles GLOBAL no tienen empresa -- no tiene sentido que
    // llamen esto (compra-service ni deberia intentarlo, ver ahi), asi que
    // ac se rechaza explicito en vez de devolver cualquier cosa.
    public PerfilBusquedaResponse miPerfil(AuthenticatedPrincipal principal) {
        if (principal.empresaId() == null) {
            throw new IllegalArgumentException("El usuario autenticado no pertenece a una empresa");
        }
        return perfilBusquedaRepository.findById(principal.empresaId())
                .map(this::toResponse)
                .orElse(PerfilBusquedaResponse.vacio(principal.empresaId()));
    }

    // Seteo manual por ahora (ver comentario en ActualizarPerfilRequest) --
    // GLOBAL puede tocar cualquier empresa, EMPRESA solo la propia (mismo
    // patron de permiso que UsuarioService/AsignacionService).
    public PerfilBusquedaResponse actualizar(Long empresaId, ActualizarPerfilRequest request, AuthenticatedPrincipal actor) {
        verificarPermiso(empresaId, actor);

        Empresa empresa = empresaRepository.findById(empresaId)
                .orElseThrow(() -> new EntityNotFoundException("Empresa no encontrada: " + empresaId));

        PerfilBusqueda perfil = perfilBusquedaRepository.findById(empresaId).orElseGet(() -> {
            PerfilBusqueda nuevo = new PerfilBusqueda();
            nuevo.setEmpresa(empresa);
            return nuevo;
        });

        perfil.setRubro(request.getRubro());
        perfil.setPalabrasClave(request.getPalabrasClave());
        perfil.setRegionCodigo(request.getRegionCodigo());
        perfil.setRegionNombre(request.getRegionNombre());
        perfil.setPerfilCompletado(true);
        perfil.setActualizadoEn(LocalDateTime.now());

        PerfilBusqueda guardado = perfilBusquedaRepository.save(perfil);
        return toResponse(guardado);
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
                perfil.getEmpresaId(),
                perfil.getRubro(),
                perfil.getPalabrasClave(),
                perfil.getRegionCodigo(),
                perfil.getRegionNombre(),
                Boolean.TRUE.equals(perfil.getPerfilCompletado()));
    }
}
