package cl.zona_ti.compra_service.Util;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

// ObjectMapper para respuestas de Mercado Publico cuyo formato real no
// siempre calza con lo documentado. Jackson ya tolera por si solo texto <->
// numero, decimal -> entero, etc.; lo que NO tolera (y tumbaba la lectura
// del detalle COMPLETO de una compra, bug real 2026-10-06: "Error while
// extracting response for type CompraAgilDetalleResponse") es:
//   - un booleano donde el DTO espera un numero (ej. true en vez de 1), o
//   - un objeto/arreglo donde el DTO espera un valor simple.
// Aca esos casos no tiran excepcion: el booleano pasa a 1/0, un objeto/
// arreglo en un campo de texto se guarda como su JSON, y en un campo
// numerico/booleano queda null. Cada campo que cae en esto se avisa UNA vez
// en el log (con su ruta), para poder corregir el DTO.
public final class JsonTolerante {

    private static final Logger log = LoggerFactory.getLogger(JsonTolerante.class);

    private static final Set<String> rutasAvisadas = ConcurrentHashMap.newKeySet();

    private JsonTolerante() {
    }

    public static ObjectMapper crear() {
        SimpleModule modulo = new SimpleModule("mercado-publico-tolerante");
        modulo.addDeserializer(Integer.class, new IntegerTolerante());
        modulo.addDeserializer(Long.class, new LongTolerante());
        modulo.addDeserializer(Double.class, new DoubleTolerante());
        modulo.addDeserializer(Boolean.class, new BooleanTolerante());
        modulo.addDeserializer(String.class, new StringTolerante());
        return JsonMapper.builder().addModule(modulo).build();
    }

    private static void avisar(JsonParser p, String esperado, JsonToken recibido) {
        // Sin los indices de arreglo ("/proveedores_cotizando/0/es_emt" ->
        // "/proveedores_cotizando/es_emt"), para avisar una vez por campo y
        // no una vez por cada fila.
        String ruta = p.streamReadContext().pathAsPointer().toString().replaceAll("/\\d+", "");
        if (rutasAvisadas.add(ruta + "|" + recibido)) {
            log.warn("Mercado Publico mando {} en '{}' (se esperaba {}) -- se tolero, revisar el DTO.",
                    recibido, ruta, esperado);
        }
    }

    // Consume el objeto/arreglo completo para dejar el parser en el
    // siguiente campo.
    private static JsonNode consumirEstructura(JsonParser p, DeserializationContext ctxt) {
        return ctxt.readTree(p);
    }

    private static boolean esEstructura(JsonToken t) {
        return t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY;
    }

    private static Double numeroDesdeTexto(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(texto.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class IntegerTolerante extends StdDeserializer<Integer> {
        IntegerTolerante() {
            super(Integer.class);
        }

        @Override
        public Integer deserialize(JsonParser p, DeserializationContext ctxt) {
            JsonToken t = p.currentToken();
            if (t == JsonToken.VALUE_NUMBER_INT) {
                return p.getIntValue();
            }
            if (t == JsonToken.VALUE_NUMBER_FLOAT) {
                return (int) p.getDoubleValue();
            }
            if (t == JsonToken.VALUE_STRING) {
                Double valor = numeroDesdeTexto(p.getValueAsString());
                return valor == null ? null : valor.intValue();
            }
            if (t == JsonToken.VALUE_TRUE || t == JsonToken.VALUE_FALSE) {
                avisar(p, "numero", t);
                return t == JsonToken.VALUE_TRUE ? 1 : 0;
            }
            if (esEstructura(t)) {
                avisar(p, "numero", t);
                consumirEstructura(p, ctxt);
            }
            return null;
        }
    }

    private static final class LongTolerante extends StdDeserializer<Long> {
        LongTolerante() {
            super(Long.class);
        }

        @Override
        public Long deserialize(JsonParser p, DeserializationContext ctxt) {
            JsonToken t = p.currentToken();
            if (t == JsonToken.VALUE_NUMBER_INT) {
                return p.getLongValue();
            }
            if (t == JsonToken.VALUE_NUMBER_FLOAT) {
                return (long) p.getDoubleValue();
            }
            if (t == JsonToken.VALUE_STRING) {
                Double valor = numeroDesdeTexto(p.getValueAsString());
                return valor == null ? null : valor.longValue();
            }
            if (t == JsonToken.VALUE_TRUE || t == JsonToken.VALUE_FALSE) {
                avisar(p, "numero", t);
                return t == JsonToken.VALUE_TRUE ? 1L : 0L;
            }
            if (esEstructura(t)) {
                avisar(p, "numero", t);
                consumirEstructura(p, ctxt);
            }
            return null;
        }
    }

    private static final class DoubleTolerante extends StdDeserializer<Double> {
        DoubleTolerante() {
            super(Double.class);
        }

        @Override
        public Double deserialize(JsonParser p, DeserializationContext ctxt) {
            JsonToken t = p.currentToken();
            if (t == JsonToken.VALUE_NUMBER_INT || t == JsonToken.VALUE_NUMBER_FLOAT) {
                return p.getDoubleValue();
            }
            if (t == JsonToken.VALUE_STRING) {
                return numeroDesdeTexto(p.getValueAsString());
            }
            if (t == JsonToken.VALUE_TRUE || t == JsonToken.VALUE_FALSE) {
                avisar(p, "numero", t);
                return t == JsonToken.VALUE_TRUE ? 1.0 : 0.0;
            }
            if (esEstructura(t)) {
                avisar(p, "numero", t);
                consumirEstructura(p, ctxt);
            }
            return null;
        }
    }

    private static final class BooleanTolerante extends StdDeserializer<Boolean> {
        BooleanTolerante() {
            super(Boolean.class);
        }

        @Override
        public Boolean deserialize(JsonParser p, DeserializationContext ctxt) {
            JsonToken t = p.currentToken();
            if (t == JsonToken.VALUE_TRUE) {
                return true;
            }
            if (t == JsonToken.VALUE_FALSE) {
                return false;
            }
            if (t == JsonToken.VALUE_NUMBER_INT || t == JsonToken.VALUE_NUMBER_FLOAT) {
                return p.getDoubleValue() != 0;
            }
            if (t == JsonToken.VALUE_STRING) {
                String texto = p.getValueAsString();
                if (texto == null || texto.isBlank()) {
                    return null;
                }
                String limpio = texto.trim().toLowerCase();
                return limpio.equals("true") || limpio.equals("1") || limpio.equals("si") || limpio.equals("sí");
            }
            if (esEstructura(t)) {
                avisar(p, "booleano", t);
                consumirEstructura(p, ctxt);
            }
            return null;
        }
    }

    private static final class StringTolerante extends StdDeserializer<String> {
        StringTolerante() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser p, DeserializationContext ctxt) {
            JsonToken t = p.currentToken();
            if (esEstructura(t)) {
                avisar(p, "texto", t);
                return consumirEstructura(p, ctxt).toString();
            }
            return p.getValueAsString();
        }
    }
}
