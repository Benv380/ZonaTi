package cl.zona_ti.compra_service.Repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import cl.zona_ti.compra_service.Model.CompraAgilProveedorCotizandoEntity;

@Repository
public interface CompraAgilProveedorCotizandoRepository extends JpaRepository<CompraAgilProveedorCotizandoEntity, Long> {

    // Panel "perfil del ganador" (ver CompraAgilService.perfilVendedor) --
    // TODAS las cotizaciones de un mismo proveedor (por RUT) a lo largo de
    // TODAS las compras agiles cacheadas, no solo la que se esta viendo.
    // Ojo: solo existen filas aca para compras cuyo DETALLE completo se
    // llego a sincronizar (ver comentario en CompraAgilRepository sobre
    // detalle_completo) -- el perfil de un proveedor va a estar mas
    // incompleto que el de un comprador mientras eso siga siendo la
    // minoria de los casos.
    List<CompraAgilProveedorCotizandoEntity> findByRutProveedor(String rutProveedor);
}
