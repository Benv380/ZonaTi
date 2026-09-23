package cl.zona_ti.auth_service.Repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import cl.zona_ti.auth_service.Model.PerfilBusqueda;

@Repository
public interface PerfilBusquedaRepository extends JpaRepository<PerfilBusqueda, Long> {

    // Todos los filtros de una empresa -- ver PerfilBusquedaService.listar/
    // misPerfiles. Ordenados por id (orden de creacion) para que la lista
    // no "salte" de orden entre recargas.
    List<PerfilBusqueda> findByEmpresaIdOrderById(Long empresaId);
}
