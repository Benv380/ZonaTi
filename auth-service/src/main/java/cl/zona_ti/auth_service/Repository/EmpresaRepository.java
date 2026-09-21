package cl.zona_ti.auth_service.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import cl.zona_ti.auth_service.Model.Empresa;

@Repository
public interface EmpresaRepository extends JpaRepository<Empresa, Long> {
}
