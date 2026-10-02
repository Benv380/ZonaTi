package cl.zona_ti.licitacion_service.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cl.zona_ti.licitacion_service.Dto.AdjuntoLicitacionDto.AdjuntoLicitacionArchivo;
import cl.zona_ti.licitacion_service.Model.AdjuntoLicitacionEntity;

import java.util.Collection;
import java.util.List;

public interface AdjuntoLicitacionRepository extends JpaRepository<AdjuntoLicitacionEntity, Long> {

    List<AdjuntoLicitacionEntity> findByCodigoLicitacion(String codigoLicitacion);

    // Usado por LimpiezaScheduler -- ON DELETE CASCADE con licitaciones (ver
    // schema.sql) cubre el borrado al eliminar la fila, pero igual hace
    // falta este metodo para borrar SOLO los adjuntos sin borrar la
    // licitacion todavia (primer paso de la limpieza en 2 etapas).
    void deleteByCodigoLicitacionIn(Collection<String> codigos);

    // Proyección liviana para listar: no trae la columna "contenido" (el
    // binario puede pesar varios MB), a diferencia de findByCodigoLicitacion.
    @Query("SELECT new cl.zona_ti.licitacion_service.Dto.AdjuntoLicitacionDto$AdjuntoLicitacionArchivo("
            + "CAST(e.id AS string), e.nombreArchivo) "
            + "FROM AdjuntoLicitacionEntity e WHERE e.codigoLicitacion = :codigo")
    List<AdjuntoLicitacionArchivo> listarPorCodigo(@Param("codigo") String codigoLicitacion);
}
