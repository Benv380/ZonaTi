package cl.zona_ti.licitacion_service.Controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.zona_ti.licitacion_service.Dto.LicitacionDto.LicitacionResponse;
import cl.zona_ti.licitacion_service.Security.AuthenticatedPrincipal;
import cl.zona_ti.licitacion_service.Service.LicitacionService;

@RestController
@CrossOrigin(origins = "http://localhost:5173" )
@RequestMapping("/compra/licitacion")
public class LicitacionController {

    private final LicitacionService licitacionService;

    public LicitacionController(LicitacionService licitacionService) {
        this.licitacionService = licitacionService;
    }

    // Cartera completa -- cualquier usuario autenticado puede pedir el
    // detalle de cualquier código (ver LicitacionService). "authorization"/
    // "principal" quedan disponibles para /mi-filtro y /listar, que sí los
    // usan.
    @GetMapping("/{codigo}")
    public ResponseEntity<LicitacionResponse> getLicitacionByCodigo(
            @PathVariable String codigo,
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestHeader("Authorization") String authorization
    ) {
        return ResponseEntity.ok(licitacionService.getLicitacionByCodigo(codigo, principal, authorization));
    }

    // fecha en formato DDMMAAAA, ej: 04082026
    @GetMapping("/por-fecha")
    public ResponseEntity<LicitacionResponse> getLicitacionesPorFecha(@RequestParam String fecha) {
        return ResponseEntity.ok(licitacionService.getLicitacionesPorFecha(fecha));
    }

    // Traer las licitaciones publicadas en los ultimos N dias (ver
    // licitacion-service.dias-listado), se hayan cerrado ya o no.
    // Lee directo de lo que LicitacionSyncScheduler ya sincronizo en
    // background -- NO le pega en vivo a la API externa (eso fue lo que
    // causaba el 504: con una ventana de varios dias, la sincronizacion en
    // vivo tarda mas que el timeout del proxy).
    //
    // Cartera completa para cualquier usuario autenticado (ya no se acota
    // por asignaciones individuales, ver LicitacionService). "pagina"
    // (1-based) y "tamano" paginan la respuesta para no mandar de un tiron
    // los cientos de licitaciones de la ventana entera.
    @GetMapping("/listar")
    public ResponseEntity<LicitacionResponse> listarUltimosDias(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestHeader("Authorization") String authorization,
            @RequestParam(defaultValue = "1") int pagina,
            @RequestParam(defaultValue = "15") int tamano
    ) {
        return ResponseEntity.ok(licitacionService.listarUltimosDiasCacheado(principal, authorization, pagina, tamano));
    }

    // "Ver mi filtro" -- acota lo mismo que /listar por el perfil de
    // busqueda de la empresa (rubro/palabras clave/region). Ver
    // LicitacionService.buscarConFiltroEmpresa para el detalle de por que
    // es un filtro LOCAL y no una busqueda en vivo (a diferencia de
    // /compra/agil, la API de licitaciones no lo soporta). Misma
    // paginacion que /listar.
    @GetMapping("/mi-filtro")
    public ResponseEntity<LicitacionResponse> buscarConFiltroEmpresa(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestHeader("Authorization") String authorization,
            @RequestParam(defaultValue = "1") int pagina,
            @RequestParam(defaultValue = "15") int tamano
    ) {
        return ResponseEntity.ok(licitacionService.buscarConFiltroEmpresa(principal, authorization, pagina, tamano));
    }

}
