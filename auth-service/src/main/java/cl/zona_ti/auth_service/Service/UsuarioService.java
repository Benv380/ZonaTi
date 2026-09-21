package cl.zona_ti.auth_service.Service;

import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import cl.zona_ti.auth_service.Dto.ActualizarUsuarioRequest;
import cl.zona_ti.auth_service.Dto.CompaneroResponse;
import cl.zona_ti.auth_service.Dto.CrearUsuarioRequest;
import cl.zona_ti.auth_service.Dto.UsuarioResponse;
import cl.zona_ti.auth_service.Model.Alcance;
import cl.zona_ti.auth_service.Model.Empresa;
import cl.zona_ti.auth_service.Model.Role;
import cl.zona_ti.auth_service.Model.User;
import cl.zona_ti.auth_service.Repository.EmpresaRepository;
import cl.zona_ti.auth_service.Repository.RoleRepository;
import cl.zona_ti.auth_service.Repository.UserRepository;
import cl.zona_ti.auth_service.Security.AuthenticatedPrincipal;
import jakarta.persistence.EntityNotFoundException;

@Service
public class UsuarioService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final EmpresaRepository empresaRepository;
    private final PasswordEncoder passwordEncoder;

    public UsuarioService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            EmpresaRepository empresaRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.empresaRepository = empresaRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // Solo lo llama UsuarioController, restringido a GLOBAL/EMPRESA (ver
    // @PreAuthorize alli). "creador" es quien esta autenticado (viene del
    // JWT, no del body) -- es la pieza clave para que un ADMIN_EMPRESA no
    // pueda auto-escalarse privilegios:
    //
    //   - GLOBAL:  puede crear un usuario con cualquier rol y en
    //              cualquier empresa (o ninguna, para otro rol GLOBAL) --
    //              usa lo que venga en el request tal cual.
    //   - EMPRESA: el rol y la empresa del request se IGNORAN por
    //              completo -- el nuevo usuario queda forzado a rol USER
    //              y a la MISMA empresa del creador. Asi un ADMIN_EMPRESA
    //              jamas puede crear otro ADMIN_EMPRESA, un rol GLOBAL, ni
    //              un usuario en una empresa que no es la suya, ni
    //              mandando esos campos a mano en el body.
    public UsuarioResponse crear(CrearUsuarioRequest request, AuthenticatedPrincipal creador) {
        Role rolAsignado;
        Empresa empresaAsignada;

        if (creador.alcance() == Alcance.GLOBAL) {
            rolAsignado = resolverRol(request.getRolNombre());
            empresaAsignada = resolverEmpresa(request.getEmpresaId());
        } else {
            if (creador.empresaId() == null) {
                // No deberia pasar nunca en la practica (un ADMIN_EMPRESA
                // siempre tiene empresa asignada), pero si pasara, mejor
                // fallar explicito que crear un usuario sin empresa.
                throw new IllegalStateException("El usuario autenticado no tiene empresa asignada");
            }
            rolAsignado = resolverRol("USER");
            empresaAsignada = resolverEmpresa(creador.empresaId());
        }

        User nuevo = new User();
        nuevo.setName(request.getName());
        nuevo.setLastName(request.getLastName());
        nuevo.setEmail(request.getEmail());
        nuevo.setUsername(request.getUsername());
        nuevo.setPassword(passwordEncoder.encode(request.getPassword()));
        nuevo.setRole(rolAsignado);
        nuevo.setEmpresa(empresaAsignada);

        return toResponse(userRepository.save(nuevo));
    }

    // GLOBAL ve cualquier empresa; EMPRESA solo la propia (aunque pida el
    // id de otra, se rechaza -- ver verificarAccesoEmpresa).
    public List<UsuarioResponse> listarPorEmpresa(Long empresaId, AuthenticatedPrincipal solicitante) {
        verificarAccesoEmpresa(empresaId, solicitante);
        return userRepository.findByEmpresaId(empresaId).stream()
                .map(this::toResponse)
                .toList();
    }

    // Perfil propio -- el JWT solo trae username/rol/alcance/empresaId (ver
    // JwtUtil), no el nombre completo ni el correo. Hace falta para cosas
    // como el "Vendedor" en una cotizacion generada (ver
    // generarCotizacionExcel.js en el front), que necesita el nombre real
    // de quien esta conectado, no solo su username de login.
    public UsuarioResponse miPerfil(AuthenticatedPrincipal principal) {
        User usuario = userRepository.findByUsername(principal.username())
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado: " + principal.username()));
        // OJO: NO se usa toResponse() aca a proposito -- ese incluye
        // requiereSupervision (para el panel admin), que el propio usuario
        // marcado NUNCA debe poder ver de si mismo. Se arma a mano,
        // siempre en false, sin importar el valor real guardado.
        return new UsuarioResponse(
                usuario.getId(),
                usuario.getName(),
                usuario.getLastName(),
                usuario.getEmail(),
                usuario.getUsername(),
                usuario.getRole().getNombre(),
                usuario.getEmpresa() != null ? usuario.getEmpresa().getId() : null,
                false);
    }

    // Gente de la MISMA empresa que el que pregunta -- lo usa el selector
    // de "a quien recomendar" (ver AsignacionController.recomendar). Sin
    // empresa (rol GLOBAL) no tiene companeros en este sentido, devuelve
    // vacio en vez de fallar.
    public List<CompaneroResponse> listarCompaneros(AuthenticatedPrincipal principal) {
        if (principal.empresaId() == null) {
            return List.of();
        }
        return userRepository.findByEmpresaId(principal.empresaId()).stream()
                .map(u -> new CompaneroResponse(u.getId(), u.getUsername(), u.getRole().getNombre()))
                .toList();
    }

    // Exclusivo GLOBAL (ver @PreAuthorize en UsuarioController) -- todos
    // los usuarios del sistema, de cualquier empresa, incluidos los otros
    // roles GLOBAL. Es lo que alimenta el panel de administracion general.
    public List<UsuarioResponse> listarTodos() {
        return userRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    // name/lastName/email/password los puede tocar cualquiera con permiso
    // sobre el usuario (ver verificarPuedeGestionar). rolNombre/empresaId
    // SOLO los aplica si "actor" es GLOBAL -- si los manda un EMPRESA se
    // ignoran en silencio (ni siquiera llega a intentar tocar el rol,
    // porque verificarPuedeGestionar ya lo hubiera bloqueado si el target
    // no fuera USER de su propia empresa).
    public UsuarioResponse actualizar(Long userId, ActualizarUsuarioRequest request, AuthenticatedPrincipal actor) {
        User usuario = obtenerUsuario(userId);
        verificarPuedeGestionar(usuario, actor);

        if (request.getName() != null) {
            usuario.setName(request.getName());
        }
        if (request.getLastName() != null) {
            usuario.setLastName(request.getLastName());
        }
        if (request.getEmail() != null) {
            usuario.setEmail(request.getEmail());
        }
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            usuario.setPassword(passwordEncoder.encode(request.getPassword()));
        }
        // A diferencia de rol/empresa (exclusivo GLOBAL), cualquier admin
        // con permiso sobre este usuario puede marcarlo/desmarcarlo -- ya
        // llegar hasta aca implica que verificarPuedeGestionar() lo dejo
        // pasar (GLOBAL, o EMPRESA de la misma empresa).
        if (request.getRequiereSupervision() != null) {
            usuario.setRequiereSupervision(request.getRequiereSupervision());
        }

        if (actor.alcance() == Alcance.GLOBAL) {
            aplicarCambioRolEmpresa(usuario, request);
        }

        return toResponse(userRepository.save(usuario));
    }

    // Solo se llama cuando el actor ya es GLOBAL (ver actualizar()). Si
    // el rol nuevo (o el que ya tenia, si no se manda uno nuevo) requiere
    // empresa (alcance EMPRESA/USUARIO), exige que quede una asignada --
    // un USER/ADMIN_EMPRESA sin empresa rompe todo el filtrado aguas
    // abajo (perfil_busqueda, asignaciones).
    private void aplicarCambioRolEmpresa(User usuario, ActualizarUsuarioRequest request) {
        Role rolFinal = request.getRolNombre() != null ? resolverRol(request.getRolNombre()) : usuario.getRole();
        Empresa empresaFinal = request.getEmpresaId() != null
                ? resolverEmpresa(request.getEmpresaId())
                : usuario.getEmpresa();

        if (rolFinal.getAlcance() != Alcance.GLOBAL && empresaFinal == null) {
            throw new IllegalArgumentException("Un usuario con rol " + rolFinal.getNombre() + " necesita una empresa asignada");
        }

        usuario.setRole(rolFinal);
        usuario.setEmpresa(empresaFinal);
    }

    // Mismo criterio de permiso que actualizar(). No se deja borrar la
    // propia cuenta (evita quedarse sin acceso por accidente).
    public void eliminar(Long userId, AuthenticatedPrincipal actor) {
        User usuario = obtenerUsuario(userId);
        verificarPuedeGestionar(usuario, actor);

        if (usuario.getUsername().equals(actor.username())) {
            throw new IllegalArgumentException("No podes eliminar tu propia cuenta");
        }

        userRepository.delete(usuario);
    }

    private User obtenerUsuario(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado: " + userId));
    }

    private void verificarAccesoEmpresa(Long empresaId, AuthenticatedPrincipal solicitante) {
        if (solicitante.alcance() == Alcance.GLOBAL) {
            return;
        }
        if (!empresaId.equals(solicitante.empresaId())) {
            throw new AccessDeniedException("No podes ver los usuarios de otra empresa");
        }
    }

    // GLOBAL puede gestionar a cualquiera. EMPRESA solo a usuarios rol
    // USER de su propia empresa -- asi nunca puede tocar a otro
    // ADMIN_EMPRESA (ni a si mismo via este camino) ni a nadie de otra
    // empresa.
    private void verificarPuedeGestionar(User usuarioObjetivo, AuthenticatedPrincipal actor) {
        if (actor.alcance() == Alcance.GLOBAL) {
            return;
        }
        Long empresaObjetivo = usuarioObjetivo.getEmpresa() != null ? usuarioObjetivo.getEmpresa().getId() : null;
        boolean mismaEmpresa = empresaObjetivo != null && empresaObjetivo.equals(actor.empresaId());
        boolean esUser = "USER".equals(usuarioObjetivo.getRole().getNombre());
        if (!mismaEmpresa || !esUser) {
            throw new AccessDeniedException("No podes gestionar ese usuario");
        }
    }

    // Usado por crear/listarPorEmpresa/listarTodos/actualizar -- todos
    // admin-only (ver @PreAuthorize en UsuarioController), por eso es
    // seguro incluir requiereSupervision aca. miPerfil() NO usa este
    // helper, ver el comentario alli.
    private UsuarioResponse toResponse(User usuario) {
        return new UsuarioResponse(
                usuario.getId(),
                usuario.getName(),
                usuario.getLastName(),
                usuario.getEmail(),
                usuario.getUsername(),
                usuario.getRole().getNombre(),
                usuario.getEmpresa() != null ? usuario.getEmpresa().getId() : null,
                usuario.isRequiereSupervision());
    }

    private Role resolverRol(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("rolNombre es obligatorio");
        }
        return roleRepository.findByNombre(nombre)
                .orElseThrow(() -> new EntityNotFoundException("Rol no encontrado: " + nombre));
    }

    private Empresa resolverEmpresa(Long empresaId) {
        if (empresaId == null) {
            return null;
        }
        return empresaRepository.findById(empresaId)
                .orElseThrow(() -> new EntityNotFoundException("Empresa no encontrada: " + empresaId));
    }
}
