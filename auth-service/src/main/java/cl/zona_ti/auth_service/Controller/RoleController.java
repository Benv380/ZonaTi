package cl.zona_ti.auth_service.Controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cl.zona_ti.auth_service.Dto.RoleResponse;
import cl.zona_ti.auth_service.Model.Role;
import cl.zona_ti.auth_service.Repository.RoleRepository;

// Catalogo de roles -- exclusivo GLOBAL, lo usa el panel de administracion
// (el selector de rol al crear/editar un usuario). No hace falta CRUD:
// los roles se siembran en data.sql, no se crean dinamicamente desde la
// app (agregar un rol nuevo es un cambio de codigo/permisos, no un dato
// de negocio).
@RestController
@RequestMapping("/auth/roles")
@PreAuthorize("hasRole('GLOBAL')")
public class RoleController {

    private final RoleRepository roleRepository;

    public RoleController(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    @GetMapping
    public ResponseEntity<List<RoleResponse>> listar() {
        List<RoleResponse> roles = roleRepository.findAll().stream()
                .map(r -> new RoleResponse(r.getId(), r.getNombre(), r.getAlcance(), r.getDescripcion()))
                .toList();
        return ResponseEntity.ok(roles);
    }
}
