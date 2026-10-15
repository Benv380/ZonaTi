package cl.zona_ti.compra_service.Controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.zona_ti.compra_service.Service.CatalogoService;
import cl.zona_ti.compra_service.Service.CatalogoService.ResultadoBusqueda;

// Buscador del catalogo de productos de MP para el selector de filtros de Mi
// Empresa (ver CatalogoService). Solo lectura de un catalogo publico: basta
// con estar autenticado.
@RestController
@RequestMapping("/compra/catalogo")
public class CatalogoController {

    private final CatalogoService catalogoService;

    public CatalogoController(CatalogoService catalogoService) {
        this.catalogoService = catalogoService;
    }

    // GET /compra/catalogo/buscar?q=notebook
    @GetMapping("/buscar")
    public ResponseEntity<ResultadoBusqueda> buscar(@RequestParam String q) {
        return ResponseEntity.ok(catalogoService.buscar(q));
    }
}
