package cl.zona_ti.auth_service.Service;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import org.springframework.stereotype.Service;

import cl.zona_ti.auth_service.Dto.CrearEmpresaRequest;
import cl.zona_ti.auth_service.Dto.EmpresaResponse;
import cl.zona_ti.auth_service.Model.Empresa;
import cl.zona_ti.auth_service.Repository.EmpresaRepository;
import cl.zona_ti.auth_service.Repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;

// Todo este service es exclusivo de rol GLOBAL -- lo restringe
// EmpresaController con @PreAuthorize("hasRole('GLOBAL')") en cada
// endpoint, no hay ningun caso de uso para EMPRESA/USER aca (a diferencia
// de UsuarioService/AsignacionService/PerfilBusquedaService, que si
// distinguen permisos por alcance).
@Service
public class EmpresaService {

    private final EmpresaRepository empresaRepository;
    private final UserRepository userRepository;

    public EmpresaService(EmpresaRepository empresaRepository, UserRepository userRepository) {
        this.empresaRepository = empresaRepository;
        this.userRepository = userRepository;
    }

    public EmpresaResponse crear(CrearEmpresaRequest request) {
        Empresa empresa = new Empresa();
        aplicarDatos(empresa, request);

        return toResponse(empresaRepository.save(empresa));
    }

    public List<EmpresaResponse> listar() {
        return empresaRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    // Usado por el panel de detalle de una empresa (ver Administracion.jsx
    // -> EmpresaDetalle.jsx): antes esa pantalla no existia y alcanzaba con
    // encontrar la empresa dentro de la lista ya cargada en memoria, pero un
    // link directo (o F5) necesita poder traerla sola.
    public EmpresaResponse obtener(Long id) {
        return toResponse(obtenerEmpresa(id));
    }

    public EmpresaResponse actualizar(Long id, CrearEmpresaRequest request) {
        Empresa empresa = obtenerEmpresa(id);
        aplicarDatos(empresa, request);
        return toResponse(empresaRepository.save(empresa));
    }

    // Comun a crear/actualizar -- todos los campos del registro de empresa
    // son opcionales salvo "nombre" (ver @NotBlank en el DTO), asi que se
    // copian tal cual vengan. "estado" solo se pisa si viene informado, para
    // que el formulario general (que no siempre lo manda) no reactive por
    // accidente una empresa que se desactivo aparte.
    private void aplicarDatos(Empresa empresa, CrearEmpresaRequest request) {
        empresa.setNombre(request.getNombre());
        empresa.setRut(request.getRut());
        empresa.setRubro(request.getRubro());
        empresa.setRepresentanteLegal(request.getRepresentanteLegal());
        empresa.setDireccion(request.getDireccion());
        empresa.setComuna(request.getComuna());
        empresa.setRegion(request.getRegion());
        empresa.setTelefono(request.getTelefono());
        empresa.setEmail(request.getEmail());
        empresa.setNombreFantasia(request.getNombreFantasia());
        empresa.setGiro(request.getGiro());
        empresa.setRutRepresentanteLegal(request.getRutRepresentanteLegal());
        empresa.setTamanoEmpresa(request.getTamanoEmpresa());
        empresa.setChileproveedoresRegistrado(request.isChileproveedoresRegistrado());
        empresa.setChileproveedoresCodigo(request.getChileproveedoresCodigo());
        empresa.setSitioWeb(request.getSitioWeb());
        if (request.getEstado() != null && !request.getEstado().isBlank()) {
            empresa.setEstado(request.getEstado());
        } else if (empresa.getEstado() == null) {
            empresa.setEstado("ACTIVA");
        }
        if (request.getLogoBase64() != null && !request.getLogoBase64().isBlank()) {
            empresa.setLogo(Base64.getDecoder().decode(request.getLogoBase64()));
            empresa.setLogoTipoContenido(request.getLogoTipoContenido());
        }
        empresa.setActualizadoEn(LocalDateTime.now());
    }

    // No se permite borrar una empresa que todavia tiene usuarios --
    // "users.empresa_id" no tiene ON DELETE CASCADE (a proposito, ver
    // schema.sql: borrar una empresa no deberia arrastrar cuentas de
    // usuario en silencio). Hay que reasignarlos o eliminarlos antes.
    public void eliminar(Long id) {
        Empresa empresa = obtenerEmpresa(id);
        if (!userRepository.findByEmpresaId(id).isEmpty()) {
            throw new IllegalStateException(
                    "No se puede eliminar una empresa con usuarios asignados. Hay que reasignarlos o eliminarlos primero");
        }
        empresaRepository.delete(empresa);
    }

    // El binario se sirve aparte de listar()/crear()/actualizar() (que ya
    // devuelven solo "tieneLogo") -- mismo motivo que los adjuntos de
    // compra-service: no tiene sentido mandar bytes pesados en cada
    // listado si nadie los pidio.
    public Empresa obtenerParaLogo(Long id) {
        return obtenerEmpresa(id);
    }

    private Empresa obtenerEmpresa(Long id) {
        return empresaRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Empresa no encontrada: " + id));
    }

    private EmpresaResponse toResponse(Empresa empresa) {
        return new EmpresaResponse(
                empresa.getId(),
                empresa.getNombre(),
                empresa.getRut(),
                empresa.getRubro(),
                empresa.getRepresentanteLegal(),
                empresa.getDireccion(),
                empresa.getComuna(),
                empresa.getRegion(),
                empresa.getTelefono(),
                empresa.getEmail(),
                empresa.getNombreFantasia(),
                empresa.getGiro(),
                empresa.getRutRepresentanteLegal(),
                empresa.getTamanoEmpresa(),
                empresa.isChileproveedoresRegistrado(),
                empresa.getChileproveedoresCodigo(),
                empresa.getSitioWeb(),
                empresa.getEstado(),
                empresa.getLogo() != null,
                empresa.getActualizadoEn());
    }
}
