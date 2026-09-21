package cl.zona_ti.auth_service.Service;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import cl.zona_ti.auth_service.Dto.ActualizarChileCompraCredencialesRequest;
import cl.zona_ti.auth_service.Dto.ChileCompraCredencialesResponse;
import cl.zona_ti.auth_service.Dto.ChileCompraCredencialesSecretoResponse;
import cl.zona_ti.auth_service.Model.Alcance;
import cl.zona_ti.auth_service.Model.Empresa;
import cl.zona_ti.auth_service.Repository.EmpresaRepository;
import cl.zona_ti.auth_service.Security.AuthenticatedPrincipal;
import cl.zona_ti.auth_service.Security.CifradoAesService;
import jakarta.persistence.EntityNotFoundException;

// Credenciales de Chile Compra (Mercado Publico) que carga cada empresa
// para que el flujo de Power Automate pueda subir sus cotizaciones ya
// aprobadas -- ver [[arquitectura-despliegue-azure]] / la automatizacion
// completa. A proposito NO vive en MarcaCotizacionService: ese service
// expone su GET a cualquier USUARIO de la empresa (lo necesita el
// generador de PDF); esto es mas sensible, GET/PUT quedan acotados a
// GLOBAL/EMPRESA (ADMIN_EMPRESA) nomas -- mismo motivo que EmpresaService/
// UsuarioService restringen por alcance en vez de reusar el patron mas
// permisivo.
@Service
public class ChileCompraCredencialesService {

    private final EmpresaRepository empresaRepository;
    private final CifradoAesService cifradoAesService;

    public ChileCompraCredencialesService(EmpresaRepository empresaRepository, CifradoAesService cifradoAesService) {
        this.empresaRepository = empresaRepository;
        this.cifradoAesService = cifradoAesService;
    }

    // La empresa las carga ella misma (GLOBAL tambien puede, por soporte)
    // -- se cifran ANTES de guardar, la contraseña en texto plano del
    // request nunca toca la base de datos tal cual.
    public ChileCompraCredencialesResponse actualizar(Long empresaId, ActualizarChileCompraCredencialesRequest request, AuthenticatedPrincipal actor) {
        verificarPuedeEditar(empresaId, actor);
        Empresa empresa = obtenerEmpresa(empresaId);

        empresa.setChileCompraUsuario(request.getChileCompraUsuario());
        empresa.setChileCompraPasswordCifrada(cifradoAesService.cifrar(request.getChileCompraPassword()));

        return toResponse(empresaRepository.save(empresa));
    }

    // Solo metadata (usuario + si esta configurado) -- nunca la
    // contraseña, ni cifrada ni descifrada. Para que la pantalla de Mi
    // Empresa pueda mostrar "ya cargado" sin manejar el secreto en el
    // front para nada.
    public ChileCompraCredencialesResponse obtener(Long empresaId, AuthenticatedPrincipal solicitante) {
        verificarPuedeEditar(empresaId, solicitante);
        return toResponse(obtenerEmpresa(empresaId));
    }

    // La contraseña YA DESCIFRADA -- exclusivo para que el flujo de Power
    // Automate la use en el momento de loguearse en Chile Compra. Mismo
    // chequeo de permiso que actualizar()/obtener() (GLOBAL o el
    // ADMIN_EMPRESA/automatizacion de esa empresa puntual) -- la cuenta
    // dedicada de automatizacion (alcance EMPRESA, ver la memoria del
    // despliegue) solo puede pedir la de su propia empresa, igual que
    // cualquier otro endpoint acotado por alcance en este servicio.
    public ChileCompraCredencialesSecretoResponse obtenerSecreto(Long empresaId, AuthenticatedPrincipal solicitante) {
        verificarPuedeEditar(empresaId, solicitante);
        Empresa empresa = obtenerEmpresa(empresaId);
        if (empresa.getChileCompraPasswordCifrada() == null) {
            throw new EntityNotFoundException("Esta empresa todavía no cargó sus credenciales de Chile Compra");
        }
        return new ChileCompraCredencialesSecretoResponse(
                empresa.getChileCompraUsuario(),
                cifradoAesService.descifrar(empresa.getChileCompraPasswordCifrada()));
    }

    // GLOBAL o el ADMIN_EMPRESA (alcance EMPRESA) de esa MISMA empresa --
    // nunca un USUARIO llano, a diferencia de MarcaCotizacionService.
    private void verificarPuedeEditar(Long empresaId, AuthenticatedPrincipal actor) {
        if (actor.alcance() == Alcance.GLOBAL) {
            return;
        }
        if (actor.alcance() != Alcance.EMPRESA || !empresaId.equals(actor.empresaId())) {
            throw new AccessDeniedException("No podes gestionar las credenciales de Chile Compra de otra empresa");
        }
    }

    private Empresa obtenerEmpresa(Long empresaId) {
        return empresaRepository.findById(empresaId)
                .orElseThrow(() -> new EntityNotFoundException("Empresa no encontrada: " + empresaId));
    }

    private ChileCompraCredencialesResponse toResponse(Empresa empresa) {
        return new ChileCompraCredencialesResponse(
                empresa.getId(),
                empresa.getChileCompraUsuario(),
                empresa.getChileCompraPasswordCifrada() != null);
    }
}
