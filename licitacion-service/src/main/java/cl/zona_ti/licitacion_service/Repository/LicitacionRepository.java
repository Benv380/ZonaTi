package cl.zona_ti.licitacion_service.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cl.zona_ti.licitacion_service.Model.LicitacionEntity;

public interface LicitacionRepository extends JpaRepository<LicitacionEntity, String> {

    // Trae la licitacion con sus items ya inicializados (JOIN FETCH) en la
    // misma consulta. Necesario porque sincronizarUltimosDias() resuelve las
    // candidatas con parallelStream(): el mapeo a DTO (que recorre
    // entity.getItems(), coleccion LAZY) corre en hilos del ForkJoinPool, no
    // en el hilo de la request, asi que ahi no esta disponible la sesion de
    // Hibernate que abre "open-in-view" -- usar findById() + acceder a
    // getItems() despues revienta con LazyInitializationException. Con el
    // JOIN FETCH la coleccion ya viene cargada, sin importar en que hilo se
    // lea despues.
    @Query("SELECT l FROM LicitacionEntity l LEFT JOIN FETCH l.items WHERE l.codigoExterno = :codigo")
    Optional<LicitacionEntity> findByIdConItems(@Param("codigo") String codigo);

    // Usada por listarUltimosDiasCacheado() (lectura rapida para el
    // usuario, sin pegarle a la API externa). DISTINCT + JOIN FETCH: evita
    // filas duplicadas por licitacion cuando tiene varios items (Hibernate
    // dedupe las entidades repetidas en memoria).
    @Query("SELECT DISTINCT l FROM LicitacionEntity l LEFT JOIN FETCH l.items "
            + "WHERE l.fechaPublicacion >= :desde ORDER BY l.fechaPublicacion DESC")
    List<LicitacionEntity> findByFechaPublicacionDesde(@Param("desde") LocalDateTime desde);
}
