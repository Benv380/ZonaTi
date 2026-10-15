package cl.zona_ti.compra_service.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import cl.zona_ti.compra_service.Util.FiltroPalabrasClave;
import jakarta.annotation.PostConstruct;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

// Catalogo de productos de Mercado Publico (clasificacion ONU), cargado en
// memoria al arrancar desde resources/catalogo/rubros_onu.csv (el listado
// oficial de rubros de MP: Tipo;Nivel1;Nivel2;Nivel3;IDCategoria;
// NombreProducto;CodigoProducto, ~18.900 productos).
//
// Pedido 2026-10-15: los filtros de la empresa no pueden traer "falsos
// positivos". Comparar palabras en texto libre nunca lo garantiza, pero cada
// producto que pide una compra viene con su codigo de este catalogo
// (confirmado en LAB01: Compra Agil guarda 43211503 para "Notebook, laptop o
// computador portatil excepto Tablet PC", y los items de licitacion traen el
// mismo codigo). Asi, el ADMIN_EMPRESA elige familias, clases o productos de
// este catalogo y una compra aparece solo si pide un producto de lo elegido:
// comparacion exacta de codigos.
//
// Niveles que se pueden elegir y su "clave":
//   FAMILIA  -> "Nivel1 > Nivel2" (362 familias, el nombre solo se repite
//               entre segmentos, por eso va con el segmento)
//   CLASE    -> IDCategoria
//   PRODUCTO -> CodigoProducto
// Actualizar el catalogo = reemplazar el CSV y volver a desplegar.
@Service
public class CatalogoService {

    private static final Logger log = LoggerFactory.getLogger(CatalogoService.class);

    private static final String ARCHIVO = "catalogo/rubros_onu.csv";
    private static final int MAX_RESULTADOS = 15;

    public record Producto(String codigo, String nombre, String tipo, String segmento, String familia,
            String clase, String idCategoria) {
        String claveFamilia() {
            return segmento + " > " + familia;
        }
    }

    public record Seleccion(String nivel, String clave, String nombre) {
    }

    public record ResultadoFamilia(String clave, String nombre, String segmento, int productos) {
    }

    public record ResultadoClase(String clave, String nombre, String familia, int productos) {
    }

    public record ResultadoProducto(String clave, String nombre, String clase) {
    }

    public record ResultadoBusqueda(List<ResultadoFamilia> familias, List<ResultadoClase> clases,
            List<ResultadoProducto> productos) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    private List<Producto> productos = List.of();
    private Map<String, Set<String>> codigosPorFamilia = Map.of();
    private Map<String, Set<String>> codigosPorClase = Map.of();

    @PostConstruct
    void cargar() {
        List<Producto> leidos = new ArrayList<>();
        try (BufferedReader lector = new BufferedReader(
                new InputStreamReader(new ClassPathResource(ARCHIVO).getInputStream(), StandardCharsets.UTF_8))) {
            lector.readLine(); // encabezado
            String linea;
            while ((linea = lector.readLine()) != null) {
                List<String> campos = camposCsv(linea);
                if (campos.size() < 7 || campos.get(6).isBlank()) {
                    continue;
                }
                leidos.add(new Producto(campos.get(6).trim(), campos.get(5).trim(), campos.get(0).trim(),
                        campos.get(1).trim(), campos.get(2).trim(), campos.get(3).trim(), campos.get(4).trim()));
            }
        } catch (IOException e) {
            // Sin catalogo los filtros por catalogo no encuentran nada (nunca
            // muestran de mas); el resto del servicio sigue funcionando.
            log.error("No se pudo cargar el catalogo de productos ({}): {}", ARCHIVO, e.getMessage());
            return;
        }

        Map<String, Set<String>> porFamilia = new HashMap<>();
        Map<String, Set<String>> porClase = new HashMap<>();
        for (Producto p : leidos) {
            porFamilia.computeIfAbsent(p.claveFamilia(), k -> new HashSet<>()).add(p.codigo());
            porClase.computeIfAbsent(p.idCategoria(), k -> new HashSet<>()).add(p.codigo());
        }
        this.productos = List.copyOf(leidos);
        this.codigosPorFamilia = porFamilia;
        this.codigosPorClase = porClase;
        log.info("Catalogo de productos cargado: {} productos, {} familias, {} clases",
                leidos.size(), porFamilia.size(), porClase.size());
    }

    // Busca en el catalogo para el selector de Mi Empresa: familias, clases y
    // productos cuyo NOMBRE coincide con lo escrito (misma regla de palabras
    // que los filtros: todas las palabras, por palabra completa, sin tildes ni
    // plural). Aca la coincidencia por palabras esta bien: solo sugiere
    // opciones, el filtro despues compara codigos exactos.
    public ResultadoBusqueda buscar(String texto) {
        List<List<String>> frases = FiltroPalabrasClave.frases(texto);
        if (frases.isEmpty()) {
            return new ResultadoBusqueda(List.of(), List.of(), List.of());
        }

        Map<String, ResultadoFamilia> familias = new LinkedHashMap<>();
        Map<String, ResultadoClase> clases = new LinkedHashMap<>();
        List<ResultadoProducto> encontrados = new ArrayList<>();
        for (Producto p : productos) {
            if (!familias.containsKey(p.claveFamilia()) && coincide(p.familia(), frases)) {
                familias.put(p.claveFamilia(), new ResultadoFamilia(p.claveFamilia(), p.familia(), p.segmento(),
                        codigosPorFamilia.get(p.claveFamilia()).size()));
            }
            if (!clases.containsKey(p.idCategoria()) && coincide(p.clase(), frases)) {
                clases.put(p.idCategoria(), new ResultadoClase(p.idCategoria(), p.clase(), p.familia(),
                        codigosPorClase.get(p.idCategoria()).size()));
            }
            if (coincide(p.nombre(), frases)) {
                encontrados.add(new ResultadoProducto(p.codigo(), p.nombre(), p.clase()));
            }
        }
        // Nombres mas cortos primero: suelen ser el producto "principal"
        // ("Notebook..." antes que "Bolso para notebook...").
        encontrados.sort(Comparator.comparingInt(r -> r.nombre().length()));
        return new ResultadoBusqueda(
                familias.values().stream().limit(MAX_RESULTADOS).toList(),
                clases.values().stream().limit(MAX_RESULTADOS).toList(),
                encontrados.stream().limit(MAX_RESULTADOS).toList());
    }

    // true si el filtro tiene alguna seleccion del catalogo. Con seleccion,
    // el filtro compara SOLO por codigo (aunque no resuelva a ningun codigo:
    // ahi no muestra nada, nunca cae a la busqueda por palabras).
    public static boolean tieneSeleccion(String catalogoJson) {
        return catalogoJson != null && !catalogoJson.replaceAll("\\s", "").isEmpty()
                && !catalogoJson.replaceAll("\\s", "").equals("[]");
    }

    // Codigos de producto que cubre la seleccion de un filtro (familias y
    // clases se expanden a todos sus productos).
    public Set<String> codigosDe(String catalogoJson) {
        Set<String> codigos = new HashSet<>();
        if (!tieneSeleccion(catalogoJson)) {
            return codigos;
        }
        List<Seleccion> selecciones;
        try {
            selecciones = objectMapper.readValue(catalogoJson, new TypeReference<List<Seleccion>>() { });
        } catch (Exception e) {
            log.warn("Seleccion de catalogo invalida, se ignora: {}", e.getMessage());
            return codigos;
        }
        for (Seleccion s : selecciones) {
            if (s == null || s.nivel() == null || s.clave() == null) {
                continue;
            }
            switch (s.nivel()) {
                case "FAMILIA" -> codigos.addAll(codigosPorFamilia.getOrDefault(s.clave(), Set.of()));
                case "CLASE" -> codigos.addAll(codigosPorClase.getOrDefault(s.clave(), Set.of()));
                case "PRODUCTO" -> codigos.add(s.clave());
                default -> log.warn("Nivel de catalogo desconocido: {}", s.nivel());
            }
        }
        return codigos;
    }

    private static boolean coincide(String nombre, List<List<String>> frases) {
        return FiltroPalabrasClave.coincide(FiltroPalabrasClave.normalizar(nombre), frases);
    }

    // Separa una linea del CSV (";") respetando campos entre comillas, donde
    // "" es una comilla literal (ej. "Llave ""T"" para grifos").
    static List<String> camposCsv(String linea) {
        List<String> campos = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        boolean entreComillas = false;
        for (int i = 0; i < linea.length(); i++) {
            char c = linea.charAt(i);
            if (entreComillas) {
                if (c == '"' && i + 1 < linea.length() && linea.charAt(i + 1) == '"') {
                    actual.append('"');
                    i++;
                } else if (c == '"') {
                    entreComillas = false;
                } else {
                    actual.append(c);
                }
            } else if (c == '"') {
                entreComillas = true;
            } else if (c == ';') {
                campos.add(actual.toString());
                actual.setLength(0);
            } else {
                actual.append(c);
            }
        }
        campos.add(actual.toString());
        return campos;
    }
}
