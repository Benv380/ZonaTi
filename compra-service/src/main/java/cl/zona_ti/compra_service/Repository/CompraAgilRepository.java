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
    @Query("SELECT DISTINCT c FROM CompraAgilEntity c LEFT JOIN FETCH c.documentos "
            + "WHERE c.fechaPublicacion >= :desde ORDER BY c.fechaPublicacion DESC")
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
            + "WHERE c.fechaPublicacion >= :desde "
            + "AND c.fechaCierreSegundoLlamado IS NOT NULL AND c.fechaCierreSegundoLlamado > :ahora "
            + "AND (c.fechaCierrePrimerLlamado IS NULL OR c.fechaCierrePrimerLlamado <= :ahora) "
            + "ORDER BY c.fechaCierreSegundoLlamado ASC")
    List<CompraAgilEntity> findEnSegundoLlamadoDesde(@Param("desde") LocalDateTime desde, @Param("ahora") LocalDateTime ahora);
}
