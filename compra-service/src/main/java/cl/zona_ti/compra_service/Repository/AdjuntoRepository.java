package cl.zona_ti.compra_service.Repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import cl.zona_ti.compra_service.Model.AdjuntoEntity;

public interface AdjuntoRepository extends JpaRepository<AdjuntoEntity, String> {

    List<AdjuntoEntity> findByCompraAgilCodigo(String compraAgilCodigo);

    // Usado por LimpiezaScheduler -- "compra_agil_codigo" NO tiene FK real
    // en la BD (ver schema.sql, a diferencia de adjunto_licitacion que si
    // tiene ON DELETE CASCADE), asi que borrar una fila de compras_agiles
    // NO se lleva sus adjuntos solo: hay que borrarlos a mano ANTES/aparte.
    void deleteByCompraAgilCodigoIn(Collection<String> codigos);
}
