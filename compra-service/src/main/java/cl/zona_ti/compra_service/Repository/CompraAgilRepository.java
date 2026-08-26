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
}
