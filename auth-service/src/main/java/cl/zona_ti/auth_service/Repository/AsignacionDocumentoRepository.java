package cl.zona_ti.auth_service.Repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import cl.zona_ti.auth_service.Dto.AsignacionDocumentoResponse;
import cl.zona_ti.auth_service.Model.AsignacionDocumento;

@Repository
public interface AsignacionDocumentoRepository extends JpaRepository<AsignacionDocumento, Long> {

    // Proyeccion liviana para listar: no trae "contenido" (el binario
    // puede pesar varios MB) -- mismo patron que
    // AdjuntoLicitacionRepository.listarPorCodigo en compra-service.
    @Query("SELECT new cl.zona_ti.auth_service.Dto.AsignacionDocumentoResponse("
            + "d.id, d.nombreArchivo, d.tamanoBytes) "
            + "FROM AsignacionDocumento d WHERE d.asignacion.id = :asignacionId ORDER BY d.creadoEn")
    List<AsignacionDocumentoResponse> listarPorAsignacion(@Param("asignacionId") Long asignacionId);
}
