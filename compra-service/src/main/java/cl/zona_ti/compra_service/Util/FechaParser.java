package cl.zona_ti.compra_service.Util;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

// Las fechas que devuelven las APIs de Mercado Publico (Licitacion y Compra
// Agil) no vienen todas en un unico formato: a veces traen offset/zona
// (ISO-8601 con "Z" o "+00:00") y a veces vienen como fecha y hora simple sin
// zona. Este parser se usa en los mappers para guardar todo como
// LocalDateTime (hora local, sin zona) al cachear, sin reventar el guardado
// completo si un campo puntual viene en un formato inesperado.
//
// Bug real 2026-09-30: las fechas que manda Mercado Publico, aunque algunas
// traigan "Z" (UTC), en realidad son hora de CHILE tal cual -- confirmado
// comparando fecha_cierre con fecha_cierre_primer_llamado del mismo instante
// (una con "Z", otra sin zona, mismo valor de reloj) y contra la hora real al
// momento del request. Convertir esa "Z" a UTC real corria el reloj guardado
// ~3-4h hacia adelante. Ahora: SIEMPRE se guarda tal cual la escribe Mercado
// Publico, descartando cualquier offset/zona que traiga.
public final class FechaParser {

    public static final ZoneId ZONA_MERCADO_PUBLICO = ZoneId.of("America/Santiago");

    private static final List<DateTimeFormatter> FORMATOS_SIN_ZONA = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            // Compra Agil manda las fechas SIN segundos (ej. "2026-08-24 09:59"),
            // a diferencia de Licitacion -- ninguno de los formatos de arriba
            // matcheaba eso, asi que fecha_publicacion/fecha_cierre/etc. quedaban
            // siempre null para Compra Agil.
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"));

    private FechaParser() {
    }

    public static LocalDateTime parse(String fecha) {
        if (fecha == null || fecha.isBlank()) {
            return null;
        }
        try {
            // Se descarta el offset/zona a proposito (ver comentario de
            // arriba) -- el valor de reloj que trae es hora de Chile.
            return OffsetDateTime.parse(fecha).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }
        for (DateTimeFormatter formato : FORMATOS_SIN_ZONA) {
            try {
                return LocalDateTime.parse(fecha, formato);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    // Hora actual en Chile -- usar SIEMPRE esto (no LocalDateTime.now()) para
    // comparar contra fechas parseadas con este mismo parser: el contenedor
    // corre en UTC, y comparar esas fechas (hora de Chile) contra la hora UTC
    // del contenedor desalineaba cualquier filtro/ventana por varias horas.
    public static LocalDateTime ahora() {
        return LocalDateTime.now(ZONA_MERCADO_PUBLICO);
    }

    // Le adjunta el offset REAL de Chile (-03:00 o -04:00 segun horario de
    // verano), no un "Z" falso -- lo que se guarda ya es hora de Chile (ver
    // parse() arriba), no UTC.
    public static String format(LocalDateTime fecha) {
        return fecha == null ? null
                : fecha.atZone(ZONA_MERCADO_PUBLICO).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
