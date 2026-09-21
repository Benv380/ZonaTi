package cl.zona_ti.auth_service.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import cl.zona_ti.auth_service.Model.PerfilBusqueda;

@Repository
public interface PerfilBusquedaRepository extends JpaRepository<PerfilBusqueda, Long> {
}
