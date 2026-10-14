package cl.zona_ti.licitacion_service.Util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Coincidencia de las "palabras clave" de un filtro de la empresa (ver Mi
// Empresa en el front) contra el texto de una compra/licitacion. Misma
// regla para Compra Agil y Licitaciones, la que promete el formulario:
//   - las comas (o ; o saltos de linea) separan FRASES, y alcanza con que
//     coincida CUALQUIERA ("materiales electricos, cemento");
//   - una frase coincide si aparecen TODAS sus palabras, en cualquier
//     orden ("materiales electricos" encuentra "MATERIALES Y ARTICULOS
//     ELECTRICOS");
//   - sin distinguir mayusculas, tildes, singular/plural ni masculino/
//     femenino, e ignorando palabras vacias ("de", "y", "para"...);
//   - por PALABRA COMPLETA, no por pedazo de texto (ver coincide).
// Bug real 2026-10-06: Compra Agil buscaba el texto ENTERO (comas
// incluidas) como una sola frase y distinguiendo tildes -- "Mis rubros"
// casi nunca encontraba nada; Licitaciones separaba por palabra suelta (con
// la coma pegada a la palabra) y mostraba todo lo que tuviera cualquiera.
public final class FiltroPalabrasClave {

    private static final Set<String> PALABRAS_VACIAS = Set.of(
            "de", "del", "la", "las", "el", "los", "lo", "y", "e", "o", "u", "para", "por", "con", "sin",
            "en", "a", "al", "un", "una", "unos", "unas", "que", "se", "su", "sus");

    private FiltroPalabrasClave() {
    }

    // Lista de frases, cada una como lista de palabras ya normalizadas.
    // Vacia = el filtro no tiene palabras clave (no restringe por texto).
    public static List<List<String>> frases(String palabrasClave) {
        List<List<String>> frases = new ArrayList<>();
        if (palabrasClave == null || palabrasClave.isBlank()) {
            return frases;
        }
        for (String frase : palabrasClave.split("[,;\\n|]+")) {
            List<String> palabras = Arrays.stream(normalizar(frase).split(" "))
                    .filter(p -> p.length() >= 2 && !PALABRAS_VACIAS.contains(p))
                    .map(FiltroPalabrasClave::raiz)
                    .toList();
            if (!palabras.isEmpty()) {
                frases.add(palabras);
            }
        }
        return frases;
    }

    // Frases de un filtro de la empresa: las del RUBRO mas las de las
    // PALABRAS CLAVE (una licitacion aparece si cumple cualquiera de las dos).
    // Pedido 2026-10-14 (paridad con Api-Prueba): el rubro que configura el
    // ADMIN_EMPRESA no filtraba nada (era solo una etiqueta). Acepta varios
    // rubros separados por coma, igual que las palabras clave.
    public static List<List<String>> frasesDelFiltro(String rubro, String palabrasClave) {
        List<List<String>> frases = new ArrayList<>(frases(rubro));
        frases.addAll(frases(palabrasClave));
        return frases;
    }

    // true si "textoNormalizado" (ver normalizar) cumple alguna frase. Sin
    // frases, no restringe.
    // Bug real 2026-10-14: se buscaba cada palabra como PEDAZO de texto en
    // cualquier parte ("ti" aparecia dentro de "reactivos", "red" dentro de
    // "credito"), y un filtro de informatica mostraba compras de reactivos o
    // de control de temperatura. Ahora se compara palabra por palabra (ver
    // contienePalabra).
    public static boolean coincide(String textoNormalizado, List<List<String>> frases) {
        if (frases.isEmpty()) {
            return true;
        }
        if (textoNormalizado == null || textoNormalizado.isBlank()) {
            return false;
        }
        Set<String> palabrasTexto = new HashSet<>(Arrays.asList(textoNormalizado.split(" ")));
        for (List<String> frase : frases) {
            if (frase.stream().allMatch(raiz -> contienePalabra(palabrasTexto, raiz))) {
                return true;
            }
        }
        return false;
    }

    // Palabras cortas (hasta 3 letras: "ti", "pc", "red"): tiene que estar la
    // palabra exacta o su plural. Mas largas: alguna palabra del texto tiene
    // que EMPEZAR con la raiz (asi "informatic" encuentra "informatica" e
    // "informaticos", pero nunca un pedazo en medio de otra palabra).
    private static boolean contienePalabra(Set<String> palabrasTexto, String raiz) {
        if (raiz.length() <= 3) {
            return palabrasTexto.contains(raiz) || palabrasTexto.contains(raiz + "s")
                    || palabrasTexto.contains(raiz + "es") || palabrasTexto.contains(raiz + "e");
        }
        for (String palabra : palabrasTexto) {
            if (palabra.startsWith(raiz)) {
                return true;
            }
        }
        return false;
    }

    // Minusculas, sin tildes, y todo lo que no sea letra o numero pasa a un
    // espacio simple.
    public static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        String sinTildes = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    // Saca el plural (es/s final) y, en palabras largas, la vocal de genero
    // (a/o final), para que "electricos" encuentre "electrica" y viceversa:
    // la raiz se busca al comienzo de las palabras del texto (ver
    // contienePalabra).
    private static String raiz(String palabra) {
        String raiz = palabra;
        // "es" completo solo si antes hay consonante (redes -> red,
        // computadores -> computador); si no, solo la "s" (series -> serie).
        if (raiz.length() > 4 && raiz.endsWith("es") && "aeiou".indexOf(raiz.charAt(raiz.length() - 3)) < 0) {
            raiz = raiz.substring(0, raiz.length() - 2);
        } else if (raiz.length() > 4 && raiz.endsWith("s")) {
            raiz = raiz.substring(0, raiz.length() - 1);
        }
        if (raiz.length() > 5 && (raiz.endsWith("a") || raiz.endsWith("o"))) {
            raiz = raiz.substring(0, raiz.length() - 1);
        }
        return raiz;
    }
}
