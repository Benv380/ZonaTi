package cl.zona_ti.compra_service.Repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cl.zona_ti.compra_service.Model.CompraAgilEntity;

public interface CompraAgilRepository extends JpaRepository<CompraAgilEntity, String> {

    // Usada por CompraAgilService.listarUltimasOchoHorasCacheado() (lectura
    // rapida para el usuario, sin pegarle a la API externa -- ver el mismo
    // fix que se hizo para licitaciones). DISTINCT + JOIN FETCH de
    // "documentos" porque toItemDto()/documentosDto() la recorre siempre.
    // "detalleCompleto = true" (2026-09-23, en las 4 queries de este
    // archivo): una compra que solo tiene el resumen del listado (sin el
    // detalle completo sincronizado todavia) NUNCA aparece en ninguna
    // lista -- decision explicita del usuario: si algo se muestra, tiene
    // que poder abrirse al instante, sin caer en el mensaje de "todavia no
    // se sincronizo" (ver CompraAgilService.getDetalleByCodigo). Mejor que
    // no aparezca un par de minutos a que aparezca y despues no cargue.
    @Query("SELECT DISTINCT c FROM CompraAgilEntity c LEFT JOIN FETCH c.documentos "
            + "WHERE c.fechaPublicacion >= :desde AND c.detalleCompleto = true "
            + "ORDER BY c.fechaPublicacion DESC")
    List<CompraAgilEntity> findByFechaPublicacionDesde(@Param("desde") LocalDateTime desde);

    // Mismo universo (ultimas 48h de publicacion) que findByFechaPublicacionDesde,
    // pero solo las que estan ACTUALMENTE en 2do llamado -- no basta con
    // que tengan fecha_cierre_segundo_llamado seteada (eso puede venir
    // planificado de entrada y quedar viejo). Se exige ademas que:
    //   - el 2do llamado siga abierto (su cierre todavia no paso), y
    //   - el 1er llamado ya haya cerrado (si no, seria el 1er llamado el
    //     vigente, no el 2do).
    // Ver boton "En 2do llamado" en CompraRapida.jsx. Ordenadas por cuando
    // cierra el 2do llamado, la mas proxima primero.
    @Query("SELECT DISTINCT c FROM CompraAgilEntity c LEFT JOIN FETCH c.documentos "
            + "WHERE c.fechaPublicacion >= :desde AND c.detalleCompleto = true "
            + "AND c.fechaCierreSegundoLlamado IS NOT NULL AND c.fechaCierreSegundoLlamado > :ahora "
            + "AND (c.fechaCierrePrimerLlamado IS NULL OR c.fechaCierrePrimerLlamado <= :ahora) "
            + "ORDER BY c.fechaCierreSegundoLlamado ASC")
    List<CompraAgilEntity> findEnSegundoLlamadoDesde(@Param("desde") LocalDateTime desde, @Param("ahora") LocalDateTime ahora);

    // Barra de busqueda por palabra clave (ver CompraAgilService.buscarPorTexto):
    // busca dentro del CACHE local (nombre/descripcion/organismo comprador,
    // sin distinguir mayusculas), no en vivo contra Mercado Publico -- mismo
    // criterio que findByFechaPublicacionDesde de arriba, para no agregar
    // otra llamada externa lenta a algo que el scheduler ya sincroniza cada
    // 10 minutos. Sin filtro de fecha (a diferencia de las 2 de arriba):
    // una compra puntual buscada por codigo tambien queda cacheada aunque
    // tenga mas de 48h, y una busqueda por palabra clave deberia poder
    // encontrarla igual.
    @Query("SELECT DISTINCT c FROM CompraAgilEntity c LEFT JOIN FETCH c.documentos "
            + "WHERE (LOWER(c.nombre) LIKE LOWER(CONCAT('%', :texto, '%')) "
            + "OR LOWER(c.descripcion) LIKE LOWER(CONCAT('%', :texto, '%')) "
            + "OR LOWER(c.organismoComprador) LIKE LOWER(CONCAT('%', :texto, '%'))) "
            + "AND c.detalleCompleto = true "
            + "ORDER BY c.fechaPublicacion DESC")
    List<CompraAgilEntity> buscarPorTexto(@Param("texto") String texto);

    // "Ver mi filtro" en Compra Agil (ver CompraAgilService.buscarPorPerfil)
    // -- mismo cache que buscarPorTexto, pero ademas acotado por region
    // cuando la empresa tiene una configurada en su perfil_busqueda
    // (":region IS NULL" deja pasar todas las regiones si no configuro
    // ninguna). "texto" son las palabras clave del perfil -- si vienen
    // vacias ("%%"), el LIKE matchea cualquier cosa, asi que en ese caso
    // el filtro real termina siendo solo la region (o nada, si tampoco hay
    // region).
    @Query("SELECT DISTINCT c FROM CompraAgilEntity c LEFT JOIN FETCH c.documentos "
            + "WHERE (LOWER(c.nombre) LIKE LOWER(CONCAT('%', :texto, '%')) "
            + "OR LOWER(c.descripcion) LIKE LOWER(CONCAT('%', :texto, '%')) "
            + "OR LOWER(c.organismoComprador) LIKE LOWER(CONCAT('%', :texto, '%'))) "
            + "AND c.detalleCompleto = true "
            + "AND (:region IS NULL OR c.region = :region) "
            + "ORDER BY c.fechaPublicacion DESC")
    List<CompraAgilEntity> buscarPorTextoYRegion(@Param("texto") String texto, @Param("region") Integer region);
}
