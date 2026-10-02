package cl.zona_ti.auth_service.Controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cl.zona_ti.auth_service.Model.TipoAsignacion;
import cl.zona_ti.auth_service.Service.AsignacionService;

// Endpoints servidor-a-servidor (compra-service -> auth-service) SIN un
// usuario logueado de por medio -- hoy solo LimpiezaScheduler en
// compra-service, corriendo en background, preguntando "que codigos
// tienen alguna asignacion" antes de purgar cache vieja. No usa JWT (no
// hay token de usuario que reenviar desde un scheduler) -- se autentica
// con un secreto compartido en el header X-Internal-Key, chequeado a mano
// acá (ver SecurityConfig: "/auth/internal/**" esta en permitAll(), este
// controller es quien hace de gatekeeper). Path bajo /internal a proposito
// para que quede obvio que esto no es para el frontend.
@RestController
public class InternalController {

    private final AsignacionService asignacionService;

    @Value("${internal.service-key}")
    private String claveInterna;

    public InternalController(AsignacionService asignacionService) {
        this.asignacionService = asignacionService;
    }

    private void verificarClave(String claveRecibida) {
        if (claveInterna == null || claveInterna.isBlank() || !claveInterna.equals(claveRecibida)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Clave interna invalida o no configurada");
        }
    }

    // GET /auth/internal/asignaciones/codigos?tipo=COMPRA_AGIL -- todos los
    // codigos externos que tienen AL MENOS una asignacion en todo el
    // sistema (cualquier empresa, cualquier estado). Ver
    // AsignacionRepository.findCodigosConAsignacion para el detalle de por
    // que es GLOBAL/sin filtro -- LimpiezaScheduler lo usa para no borrar
    // nunca algo que alguien ya miro.
    @GetMapping("/auth/internal/asignaciones/codigos")
    public List<String> codigosConAsignacion(
            @RequestHeader("X-Internal-Key") String claveRecibida,
            @RequestParam TipoAsignacion tipo
    ) {
        verificarClave(claveRecibida);
        return asignacionService.codigosConAsignacion(tipo);
    }
}
