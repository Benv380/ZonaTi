package cl.zona_ti.compra_service.Util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
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
//   - sin distinguir mayusculas, tildes ni singular/plural, e ignorando
//     palabras vacias ("de", "y", "para"...).
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

    // true si "textoNormalizado" (ver normalizar) cumple alguna frase. Sin
    // frases, no restringe.
    public static boolean coincide(String textoNormalizado, List<List<String>> frases) {
        if (frases.isEmpty()) {
            return true;
        }
        if (textoNormalizado == null || textoNormalizado.isBlank()) {
            return false;
        }
        for (List<String> frase : frases) {
            if (frase.stream().allMatch(textoNormalizado::contains)) {
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

    // Saca el plural (es/s final) para que "electricos" encuentre "electrico"
    // y viceversa: se busca la raiz como substring del texto.
    private static String raiz(String palabra) {
        if (palabra.length() > 5 && palabra.endsWith("es")) {
            return palabra.substring(0, palabra.length() - 2);
        }
        if (palabra.length() > 4 && palabra.endsWith("s")) {
            return palabra.substring(0, palabra.length() - 1);
        }
        return palabra;
    }
}
