package cl.zona_ti.compra_service.Controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.zona_ti.compra_service.Dto.CompraAgilDto.CompraAgilDetalleResponse;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.CompraAgilListadoResponse;
import cl.zona_ti.compra_service.Security.AuthenticatedPrincipal;
import cl.zona_ti.compra_service.Service.CompraAgilService;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/compra/agil")
public class CompraAgilController {

    private final CompraAgilService compraAgilService;

    public CompraAgilController(CompraAgilService compraAgilService) {
        this.compraAgilService = compraAgilService;
    }

    // Busqueda en vivo contra Mercado Publico, ej:
    // /compra/agil?publicado_desde=2026-08-01T00:00:00Z&publicado_hasta=2026-08-04T23:59:59Z&tamano_pagina=10
    // /compra/agil?q=materiales%20electricos&region=13&estado=publicada,proveedor_seleccionado
    // Ver doc API Compra Agil v2 seccion 5.1 para el detalle de filtros disponibles.
    //
    // Los "filtros" de la URL solo los usa un rol GLOBAL tal cual -- para
    // EMPRESA/USUARIO, CompraAgilService.buscar() los IGNORA por completo y
    // arma la busqueda con el perfil_busqueda de la empresa del usuario en
    // su lugar (rubro/region configurados), asi nadie puede salirse de eso
    // solo cambiando la URL. Ver el comentario en buscar() para el detalle.
    @GetMapping
    public ResponseEntity<CompraAgilListadoResponse> listar(
            @RequestParam Map<String, String> filtros,
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestHeader("Authorization") String authorization
    ) {
        return ResponseEntity.ok(compraAgilService.buscar(filtros, principal, authorization));
    }

    @GetMapping("/{codigo}")
    public ResponseEntity<CompraAgilDetalleResponse> getDetalleByCodigo(
            @PathVariable String codigo,
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestHeader("Authorization") String authorization
    ) {
        return ResponseEntity.ok(compraAgilService.getDetalleByCodigo(codigo, principal, authorization));
    }

    // Traer las compras de las ultimas 48 horas -- lee del cache (BD), no le
    // pega en vivo a la API externa (ver CompraAgilService). Cartera
    // completa para cualquier usuario autenticado (ya no se acota por
    // asignaciones). "pagina" (1-based) y "tamano" paginan la respuesta.
    @GetMapping("/listar")
    public ResponseEntity<CompraAgilListadoResponse> listarUltimasOchoHoras(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestHeader("Authorization") String authorization,
            @RequestParam(defaultValue = "1") int pagina,
            @RequestParam(defaultValue = "15") int tamano
    ) {
        return ResponseEntity.ok(compraAgilService.listarUltimasOchoHorasCacheado(principal, authorization, pagina, tamano));
    }

    // Mismo cache/ventana de 48h que /listar, pero solo las compras que ya
    // pasaron a un 2do llamado (el primero no recibio suficientes ofertas).
    // Ver boton "En 2do llamado" en CompraRapida.jsx.
    @GetMapping("/segundo-llamado")
    public ResponseEntity<CompraAgilListadoResponse> listarSegundoLlamado(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestHeader("Authorization") String authorization,
            @RequestParam(defaultValue = "1") int pagina,
            @RequestParam(defaultValue = "15") int tamano
    ) {
        return ResponseEntity.ok(compraAgilService.listarSegundoLlamadoCacheado(principal, authorization, pagina, tamano));
    }

    // Barra de busqueda por palabra clave (ver CompraRapida.jsx) -- distinto
    // del "/" de arriba (ese es en vivo contra Mercado Publico, con filtros
    // avanzados y acotado por perfil para EMPRESA/USUARIO). Este busca en el
    // cache local (nombre/descripcion/organismo comprador), rapido y sin
    // restriccion de rol -- mismo criterio que /listar.
    @GetMapping("/buscar")
    public ResponseEntity<CompraAgilListadoResponse> buscarPorTexto(
            @RequestParam String q,
            @RequestParam(defaultValue = "1") int pagina,
            @RequestParam(defaultValue = "15") int tamano
    ) {
        return ResponseEntity.ok(compraAgilService.buscarPorTexto(q, pagina, tamano));
    }

}
