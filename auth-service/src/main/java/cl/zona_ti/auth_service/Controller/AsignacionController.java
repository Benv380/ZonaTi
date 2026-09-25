package cl.zona_ti.auth_service.Controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.zona_ti.auth_service.Dto.ActualizarCotizacionRequest;
import cl.zona_ti.auth_service.Dto.ActualizarDetalleDesarrolloRequest;
import cl.zona_ti.auth_service.Dto.ActualizarEstadoRequest;
import cl.zona_ti.auth_service.Dto.AsignacionEmpresaResumen;
import cl.zona_ti.auth_service.Dto.AsignacionGlobalResponse;
import cl.zona_ti.auth_service.Dto.AsignacionResponse;
import cl.zona_ti.auth_service.Dto.CrearAsignacionRequest;
import cl.zona_ti.auth_service.Model.TipoAsignacion;
import cl.zona_ti.auth_service.Security.AuthenticatedPrincipal;
import cl.zona_ti.auth_service.Service.AsignacionService;
import jakarta.validation.Valid;

@RestController
public class AsignacionController {

    private final AsignacionService asignacionService;

    public AsignacionController(AsignacionService asignacionService) {
        this.asignacionService = asignacionService;
    }

    // La consulta compra-service EN VIVO en cada listado, forwardeando el
    // mismo Bearer token que le mando el usuario (ver AsignacionClient en
    // compra-service) -- por eso alcanza con "authenticated()" (cualquier
    // rol) en vez de restringir por rol: cada quien solo puede pedir SUS
    // propios codigos, nunca los de otro usuario (no recibe un userId, lo
    // saca del propio JWT).
    @GetMapping("/auth/me/asignaciones")
    public ResponseEntity<List<String>> misAsignaciones(
            @RequestParam TipoAsignacion tipo,
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ResponseEntity.ok(asignacionService.misCodigos(principal, tipo));
    }

    // Cruce de datos entre usuarios/admins de una misma empresa: cualquier
    // usuario autenticado puede ver que codigos de compra-agil/licitacion
    // ya estan tomados por un compañero (y por quien), para no duplicar
    // trabajo -- sin @PreAuthorize de rol, a diferencia de listarPorEmpresa
    // (el alcance real -- SOLO mi empresa -- lo resuelve el service con el
    // empresaId del propio JWT, no hay forma de pedir el de otra empresa).
    @GetMapping("/auth/me/empresa/asignaciones")
    public ResponseEntity<List<AsignacionEmpresaResumen>> asignacionesDeMiEmpresa(
            @RequestParam TipoAsignacion tipo,
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ResponseEntity.ok(asignacionService.misCodigosEmpresa(principal, tipo));
    }

    // Version con el detalle completo (estado, origen, quien la
    // recomendo, etc.) de TODAS mis asignaciones/recomendaciones, sin
    // importar tipo -- lo usa el dashboard "Mis activas" del Home (ver
    // MisActivas.jsx en el front). Distinto del endpoint de arriba, que
    // solo devuelve los codigos y lo consume compra-service para filtrar.
    @GetMapping("/auth/me/asignaciones/detalle")
    public ResponseEntity<List<AsignacionResponse>> misAsignacionesDetalle(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ResponseEntity.ok(asignacionService.misAsignacionesDetalle(principal));
    }

    // GLOBAL o EMPRESA -- ver AsignacionService.verificarPermiso para el
    // acotamiento real (EMPRESA solo puede tocar usuarios de su empresa).
    @PostMapping("/auth/usuarios/{userId}/asignaciones")
    @PreAuthorize("hasAnyRole('GLOBAL','EMPRESA')")
    public ResponseEntity<AsignacionResponse> asignar(
            @PathVariable Long userId,
            @Valid @RequestBody CrearAsignacionRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal creador
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(asignacionService.asignar(userId, request, creador));
    }

    // "De abajo hacia arriba" -- cualquier usuario autenticado (tipicamente
    // rol USER) se recomienda a si mismo una licitacion/compra, sin
    // aprobacion previa (ver AsignacionService.recomendar). No hace falta
    // restringir el rol: un GLOBAL/EMPRESA tambien podria usarlo para si
    // mismo, no hay nada que proteger ahi.
    @PostMapping("/auth/me/asignaciones")
    public ResponseEntity<AsignacionResponse> recomendar(
            @Valid @RequestBody CrearAsignacionRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(asignacionService.recomendar(request, principal));
    }

    // Simetrico a recomendar() (POST arriba) -- el propio usuario se saca
    // a si mismo una compra/licitacion desde la ventana de detalle (boton
    // "Quitarme"), identificandola por codigo en vez de por id (el front
    // nunca vio el id de la fila). Sin restriccion de rol, mismo motivo
    // que recomendar(): no hay nada que proteger, cada quien solo puede
    // tocar lo suyo (el service resuelve la fila a partir del propio JWT).
    @DeleteMapping("/auth/me/asignaciones")
    public ResponseEntity<Void> quitarme(
            @RequestParam String codigoExterno,
            @RequestParam TipoAsignacion tipo,
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        asignacionService.eliminarMia(codigoExterno, tipo, principal);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/auth/usuarios/{userId}/asignaciones")
    @PreAuthorize("hasAnyRole('GLOBAL','EMPRESA')")
    public ResponseEntity<List<AsignacionResponse>> listarPorUsuario(
            @PathVariable Long userId,
            @AuthenticationPrincipal AuthenticatedPrincipal solicitante
    ) {
        return ResponseEntity.ok(asignacionService.listarPorUsuario(userId, solicitante));
    }

    // Panel de "toda la empresa" -- ve las asignaciones/recomendaciones de
    // TODOS los usuarios de una empresa junto, sin entrar uno por uno.
    @GetMapping("/auth/empresas/{empresaId}/asignaciones")
    @PreAuthorize("hasAnyRole('GLOBAL','EMPRESA')")
    public ResponseEntity<List<AsignacionResponse>> listarPorEmpresa(
            @PathVariable Long empresaId,
            @AuthenticationPrincipal AuthenticatedPrincipal solicitante
    ) {
        return ResponseEntity.ok(asignacionService.listarPorEmpresa(empresaId, solicitante));
    }

    // Panel GLOBAL de "todas las empresas juntas" (ver Administracion.jsx)
    // -- exclusivo ADMIN, ninguna otra pantalla necesita mezclar
    // asignaciones de mas de una empresa a la vez.
    @GetMapping("/auth/asignaciones")
    @PreAuthorize("hasRole('GLOBAL')")
    public ResponseEntity<List<AsignacionGlobalResponse>> listarTodas() {
        return ResponseEntity.ok(asignacionService.listarTodas());
    }

    // Sin @PreAuthorize a nivel de rol -- el permiso real (dueño de la
    // fila, o admin de su empresa, o GLOBAL) se resuelve dentro del
    // service porque depende de DE QUIEN es la fila puntual, no del rol
    // en abstracto (ver AsignacionService.verificarPuedeActualizarEstado).
    @PatchMapping("/auth/asignaciones/{id}/estado")
    public ResponseEntity<AsignacionResponse> actualizarEstado(
            @PathVariable Long id,
            @Valid @RequestBody ActualizarEstadoRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal actor
    ) {
        return ResponseEntity.ok(asignacionService.actualizarEstado(id, request, actor));
    }

    // Sin @PreAuthorize a nivel de rol, mismo motivo que actualizarEstado:
    // el permiso real (dueño de la fila, admin de su empresa, o GLOBAL) se
    // resuelve dentro del service. Quien puede avanzar el estado de una
    // asignacion tambien puede editar su cotizacion.
    @PutMapping("/auth/asignaciones/{id}/cotizacion")
    public ResponseEntity<AsignacionResponse> actualizarCotizacion(
            @PathVariable Long id,
            @RequestBody ActualizarCotizacionRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal actor
    ) {
        return ResponseEntity.ok(asignacionService.actualizarCotizacion(id, request, actor));
    }

    // Igual criterio que actualizarCotizacion, para el detalle de texto
    // libre de la fase DESARROLLO -- a diferencia de esa, este SI lleva
    // @Valid (ver ActualizarDetalleDesarrolloRequest, tiene limite de
    // caracteres real).
    @PutMapping("/auth/asignaciones/{id}/detalle-desarrollo")
    public ResponseEntity<AsignacionResponse> actualizarDetalleDesarrollo(
            @PathVariable Long id,
            @Valid @RequestBody ActualizarDetalleDesarrolloRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal actor
    ) {
        return ResponseEntity.ok(asignacionService.actualizarDetalleDesarrollo(id, request, actor));
    }

    // Exclusivo GLOBAL/EMPRESA (ver AsignacionService.aprobarRevision) --
    // a diferencia de actualizarEstado, esta el propio dueño de la fila NO
    // la puede llamar.
    @PatchMapping("/auth/asignaciones/{id}/revision")
    @PreAuthorize("hasAnyRole('GLOBAL','EMPRESA')")
    public ResponseEntity<AsignacionResponse> aprobarRevision(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedPrincipal actor
    ) {
        return ResponseEntity.ok(asignacionService.aprobarRevision(id, actor));
    }

    // Exclusivo GLOBAL/EMPRESA (ver AsignacionService.marcarEnviada) -- la
    // llama el flujo externo de Power Automate Desktop despues de subir la
    // cotizacion a Mercado Publico de verdad, nunca el propio dueño de la
    // fila.
    @PatchMapping("/auth/asignaciones/{id}/enviada")
    @PreAuthorize("hasAnyRole('GLOBAL','EMPRESA')")
    public ResponseEntity<AsignacionResponse> marcarEnviada(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedPrincipal actor
    ) {
        return ResponseEntity.ok(asignacionService.marcarEnviada(id, actor));
    }

    @DeleteMapping("/auth/asignaciones/{id}")
    @PreAuthorize("hasAnyRole('GLOBAL','EMPRESA')")
    public ResponseEntity<Void> eliminar(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedPrincipal solicitante
    ) {
        asignacionService.eliminar(id, solicitante);
        return ResponseEntity.noContent().build();
    }
}
