package cl.zona_ti.auth_service.Repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import cl.zona_ti.auth_service.Model.User;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    // Login ahora es por email (ver AuthService.authenticate) -- username
    // se mantiene como identificador de despliegue, no de acceso.
    Optional<User> findByEmail(String email);

    // Usado por UsuarioController (GET /auth/empresas/{id}/usuarios) para
    // el panel de gestion de un ADMIN_EMPRESA.
    List<User> findByEmpresaId(Long empresaId);
}