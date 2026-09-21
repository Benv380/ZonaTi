package cl.zona_ti.auth_service.Service;

import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import cl.zona_ti.auth_service.Dto.ActualizarCotizacionRequest;
import cl.zona_ti.auth_service.Dto.ActualizarDetalleDesarrolloRequest;
import cl.zona_ti.auth_service.Dto.ActualizarEstadoRequest;
import cl.zona_ti.auth_service.Dto.AsignacionGlobalResponse;
import cl.zona_ti.auth_service.Dto.AsignacionResponse;
import cl.zona_ti.auth_service.Dto.CrearAsignacionRequest;
import cl.zona_ti.auth_service.Model.Alcance;
import cl.zona_ti.auth_service.Model.Asignacion;
import cl.zona_ti.auth_service.Model.EstadoAsignacion;
import cl.zona_ti.auth_service.Model.OrigenAsignacion;
import cl.zona_ti.auth_service.Model.TipoAsignacion;
import cl.zona_ti.auth_service.Model.User;
import cl.zona_ti.auth_service.Repository.AsignacionRepository;
import cl.zona_ti.auth_service.Repository.UserRepository;
import cl.zona_ti.auth_service.Security.AuthenticatedPrincipal;
import jakarta.persistence.EntityNotFoundException;

@Service
public class AsignacionService {

    private final AsignacionRepository asignacionRepository;
    private final UserRepository userRepository;

    public AsignacionService(AsignacionRepository asignacionRepository, UserRepository userRepository) {
        this.asignacionRepository = asignacionRepository;
        this.userRepository = userRepository;
    }

    // Lo llama compra-service EN VIVO en cada listado (ver
    // AsignacionClient/LicitacionService alli -- Opcion B ya decidida, sin
    // cachear en el JWT) para saber que codigos le tocan al usuario
    // autenticado. Solo devuelve los codigos -- no expone nada mas del
    // usuario.
    public List<String> misCodigos(AuthenticatedPrincipal principal, TipoAsignacion tipo) {
        User usuario = obtenerUsuarioPorUsername(principal.username());
        return asignacionRepository.findByUsuarioIdAndTipo(usuario.getId(), tipo).stream()
                .map(Asignacion::getCodigoExterno)
                .toList();
    }

    // Solo la llama AsignacionController, restringido a GLOBAL/EMPRESA (ver
    // @PreAuthorize alli). GLOBAL puede asignar a cualquier usuario;
    // EMPRESA solo a usuarios de su propia empresa (chequeado en
    // verificarPermiso) -- evita que un ADMIN_EMPRESA asigne codigos a
    // usuarios que no administra. Origen queda en ADMIN (default de la
    // entidad) -- es el flujo "de arriba hacia abajo".
    public AsignacionResponse asignar(Long userId, CrearAsignacionRequest request, AuthenticatedPrincipal creador) {
        User usuario = obtenerUsuario(userId);
        verificarPermiso(usuario, creador);
        verificarNoDuplicada(usuario, request.getCodigoExterno(), request.getTipo());

        Asignacion asignacion = new Asignacion();
        asignacion.setUsuario(usuario);
        asignacion.setCodigoExterno(request.getCodigoExterno());
        asignacion.setTipo(request.getTipo());

        return toResponse(asignacionRepository.save(asignacion));
    }

    // "De abajo hacia arriba": el usuario autenticado recomienda una
    // licitacion/compra a alguien -- sin paso de aprobacion (ya decidido),
    // queda visible directo. Si "destinatarioUserId" viene null, se
    // recomienda a si mismo (compatibilidad con el flujo simple). Si viene
    // con un valor, tiene que ser alguien de la MISMA empresa (ver
    // verificarMismaEmpresa) -- no se puede recomendar a nadie de otra
    // compañía. Origen=USER es lo unico que la distingue de una asignada
    // por un admin -- mismo flujo de estados de ahi en mas.
    public AsignacionResponse recomendar(CrearAsignacionRequest request, AuthenticatedPrincipal principal) {
        User recomendador = obtenerUsuarioPorUsername(principal.username());

        User destinatario = recomendador;
        boolean paraOtraPersona = request.getDestinatarioUserId() != null
                && !request.getDestinatarioUserId().equals(recomendador.getId());
        if (paraOtraPersona) {
            destinatario = obtenerUsuario(request.getDestinatarioUserId());
            verificarMismaEmpresa(recomendador, destinatario);
        }

        verificarNoDuplicada(destinatario, request.getCodigoExterno(), request.getTipo());

        Asignacion asignacion = new Asignacion();
        asignacion.setUsuario(destinatario);
        asignacion.setCodigoExterno(request.getCodigoExterno());
        asignacion.setTipo(request.getTipo());
        asignacion.setOrigen(OrigenAsignacion.USER);
        if (paraOtraPersona) {
            asignacion.setRecomendadoPor(recomendador);
        }

        return toResponse(asignacionRepository.save(asignacion));
    }

    // Todo el detalle (no solo los codigos) de MIS propias asignaciones/
    // recomendaciones -- alimenta el dashboard "Mis activas" del front. A
    // diferencia de listarPorUsuario(), no hace falta chequear permiso:
    // cada quien siempre puede ver lo suyo.
    public List<AsignacionResponse> misAsignacionesDetalle(AuthenticatedPrincipal principal) {
        User usuario = obtenerUsuarioPorUsername(principal.username());
        return asignacionRepository.findByUsuarioId(usuario.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    public List<AsignacionResponse> listarPorUsuario(Long userId, AuthenticatedPrincipal solicitante) {
        User usuario = obtenerUsuario(userId);
        verificarPermiso(usuario, solicitante);

        return asignacionRepository.findByUsuarioId(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    // Todas las asignaciones/recomendaciones de una empresa completa (no
    // acotado a un usuario puntual) -- es donde un ADMIN_EMPRESA/GLOBAL ve
    // de un vistazo lo que sus usuarios recomendaron, sin entrar
    // usuario por usuario.
    public List<AsignacionResponse> listarPorEmpresa(Long empresaId, AuthenticatedPrincipal solicitante) {
        verificarAccesoEmpresa(empresaId, solicitante);
        return asignacionRepository.findByUsuario_EmpresaId(empresaId).stream()
                .map(this::toResponse)
                .toList();
    }

    // Exclusivo GLOBAL (ver @PreAuthorize en el controller) -- TODAS las
    // asignaciones/recomendaciones de TODAS las empresas juntas, para el
    // panel "ver todas las empresas y filtrar" de Administracion.jsx. A
    // diferencia de listarPorEmpresa, incluye empresaId/empresaNombre en
    // cada fila (ver AsignacionGlobalResponse) -- sin eso no se podria
    // filtrar client-side por empresa en una tabla que mezcla varias.
    public List<AsignacionGlobalResponse> listarTodas() {
        return asignacionRepository.findAll().stream()
                .map(this::toResponseGlobal)
                .toList();
    }

    // Puede tocar el estado: GLOBAL (cualquiera), el ADMIN_EMPRESA dueño
    // de la empresa del usuario asignado (supervision), o el propio
    // usuario dueño de la fila (avanzando su propio trabajo). Al pasar a
    // DESCARTADO, exige un motivo y guarda en que estado estaba justo
    // antes -- para no perder esa historia.
    //
    // Compuerta de supervision: no frena el flujo de estados (el estado
    // pedido se aplica siempre tal cual). Si el dueño de la fila tiene
    // User.requiereSupervision en true y es EL MISMO quien pasa su propia
    // asignacion a COMPLETADO (no un admin), ademas se prende
    // "pendienteRevision" -- el usuario nunca ve nada distinto, pero la
    // fila aparece en la lista de "Pendientes de revisión" de su empresa
    // hasta que un ADMIN_EMPRESA/GLOBAL la revise (ver aprobarRevision).
    //
    // Si un admin la devuelve a otro estado (rechazo desde la revision,
    // ver PendientesRevision.jsx "Devolver a Desarrollo") mientras seguia
    // pendiente, se apaga la marca de una vez -- si no, quedaria pegada
    // en la lista de pendientes para siempre aunque ya no este COMPLETADO.
    public AsignacionResponse actualizarEstado(Long asignacionId, ActualizarEstadoRequest request, AuthenticatedPrincipal actor) {
        Asignacion asignacion = asignacionRepository.findById(asignacionId)
                .orElseThrow(() -> new EntityNotFoundException("Asignación no encontrada: " + asignacionId));
        verificarPuedeActualizarEstado(asignacion, actor);

        EstadoAsignacion estadoFinal = request.getEstado();
        boolean pideCompletar = estadoFinal == EstadoAsignacion.COMPLETADO;
        boolean actorEsAdmin = actor.alcance() != Alcance.USUARIO;
        if (pideCompletar && asignacion.getUsuario().isRequiereSupervision() && !actorEsAdmin) {
            asignacion.setPendienteRevision(true);
        } else if (!pideCompletar && actorEsAdmin && asignacion.isPendienteRevision()) {
            asignacion.setPendienteRevision(false);
        }

        if (estadoFinal == EstadoAsignacion.DESCARTADO) {
            if (request.getMotivoDescarte() == null || request.getMotivoDescarte().isBlank()) {
                throw new IllegalArgumentException("motivoDescarte es obligatorio para descartar una asignación");
            }
            asignacion.setEtapaDescarte(asignacion.getEstado());
            asignacion.setMotivoDescarte(request.getMotivoDescarte());
        }
        asignacion.setEstado(estadoFinal);

        return toResponse(asignacionRepository.save(asignacion));
    }

    // Apaga "pendienteRevision" una vez que un admin ya la reviso --
    // exclusivo GLOBAL/EMPRESA (ver verificarPermiso), a diferencia de
    // actualizarEstado NO lo puede hacer el propio dueño de la fila: seria
    // el mismo aprobandose a si mismo, contradice el motivo de la marca.
    public AsignacionResponse aprobarRevision(Long asignacionId, AuthenticatedPrincipal actor) {
        Asignacion asignacion = asignacionRepository.findById(asignacionId)
                .orElseThrow(() -> new EntityNotFoundException("Asignación no encontrada: " + asignacionId));
        verificarPermiso(asignacion.getUsuario(), actor);
        asignacion.setPendienteRevision(false);
        return toResponse(asignacionRepository.save(asignacion));
    }

    // Apaga "enviadoMercadoPublico" -- lo prende (no lo apaga) el flujo
    // externo de Power Automate Desktop una vez que efectivamente cargo y
    // envio la cotizacion en Mercado Publico. Mismo criterio de permiso
    // que aprobarRevision: exclusivo GLOBAL/EMPRESA, nunca el propio dueño
    // de la fila (no es una accion que el vaya a disparar).
    public AsignacionResponse marcarEnviada(Long asignacionId, AuthenticatedPrincipal actor) {
        Asignacion asignacion = asignacionRepository.findById(asignacionId)
                .orElseThrow(() -> new EntityNotFoundException("Asignación no encontrada: " + asignacionId));
        verificarPermiso(asignacion.getUsuario(), actor);
        asignacion.setEnviadoMercadoPublico(true);
        return toResponse(asignacionRepository.save(asignacion));
    }

    // Guarda el borrador de cotizacion armado en la fase ANALISIS (ver
    // CotizacionCompraAgil.jsx en el front) -- mismo criterio de permiso
    // que actualizarEstado: quien puede avanzar el estado de la
    // asignacion tambien puede editar su cotizacion. El contenido es JSON
    // opaco para auth-service, se guarda/devuelve tal cual sin validar su
    // estructura.
    public AsignacionResponse actualizarCotizacion(Long asignacionId, ActualizarCotizacionRequest request, AuthenticatedPrincipal actor) {
        Asignacion asignacion = asignacionRepository.findById(asignacionId)
                .orElseThrow(() -> new EntityNotFoundException("Asignación no encontrada: " + asignacionId));
        verificarPuedeActualizarEstado(asignacion, actor);
        asignacion.setCotizacionJson(request.getCotizacionJson());
        return toResponse(asignacionRepository.save(asignacion));
    }

    // Guarda el detalle de texto libre de la fase DESARROLLO (ver
    // DetalleDesarrollo.jsx en el front) -- mismo criterio de permiso que
    // actualizarCotizacion/actualizarEstado. A diferencia de la
    // cotizacion, este campo SI se valida (ver
    // ActualizarDetalleDesarrolloRequest) porque es texto real, no un
    // blob que arma el front.
    public AsignacionResponse actualizarDetalleDesarrollo(Long asignacionId, ActualizarDetalleDesarrolloRequest request, AuthenticatedPrincipal actor) {
        Asignacion asignacion = asignacionRepository.findById(asignacionId)
                .orElseThrow(() -> new EntityNotFoundException("Asignación no encontrada: " + asignacionId));
        verificarPuedeActualizarEstado(asignacion, actor);
        asignacion.setDetalleDesarrollo(request.getDetalleDesarrollo());
        return toResponse(asignacionRepository.save(asignacion));
    }

    public void eliminar(Long asignacionId, AuthenticatedPrincipal solicitante) {
        Asignacion asignacion = asignacionRepository.findById(asignacionId)
                .orElseThrow(() -> new EntityNotFoundException("Asignacion no encontrada: " + asignacionId));
        verificarPermiso(asignacion.getUsuario(), solicitante);
        asignacionRepository.delete(asignacion);
    }

    private User obtenerUsuario(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado: " + userId));
    }

    private User obtenerUsuarioPorUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado: " + username));
    }

    // GLOBAL: sin restriccion. EMPRESA: solo si el usuario objetivo
    // pertenece a la misma empresa que el creador -- si no, 403 en vez de
    // dejarlo tocar usuarios de otra empresa.
    private void verificarPermiso(User usuarioObjetivo, AuthenticatedPrincipal solicitante) {
        if (solicitante.alcance() == Alcance.GLOBAL) {
            return;
        }
        Long empresaObjetivo = usuarioObjetivo.getEmpresa() != null ? usuarioObjetivo.getEmpresa().getId() : null;
        if (empresaObjetivo == null || !empresaObjetivo.equals(solicitante.empresaId())) {
            throw new AccessDeniedException("No podes administrar usuarios de otra empresa");
        }
    }

    // No se puede recomendar a alguien de OTRA empresa -- ambos "usuario"
    // (getEmpresa()) tienen que coincidir. Roles GLOBAL no tienen empresa,
    // asi que tampoco pueden ser destinatarios de una recomendacion (no
    // tendria sentido: ya ven todo).
    private void verificarMismaEmpresa(User recomendador, User destinatario) {
        Long empresaRecomendador = recomendador.getEmpresa() != null ? recomendador.getEmpresa().getId() : null;
        Long empresaDestinatario = destinatario.getEmpresa() != null ? destinatario.getEmpresa().getId() : null;
        if (empresaRecomendador == null || !empresaRecomendador.equals(empresaDestinatario)) {
            throw new AccessDeniedException("Solo podes recomendarle a alguien de tu misma empresa");
        }
    }

    // Evita el 409 generico de la constraint unica (uq_asignaciones_user_
    // codigo_tipo) con un mensaje que explica de quien es el conflicto --
    // usado tanto por asignar() (admin -> usuario) como por recomendar()
    // (usuario -> si mismo/compañero): en ambos casos el destinatario ya
    // podria tener este mismo codigo asignado por otra via.
    private void verificarNoDuplicada(User destinatario, String codigoExterno, TipoAsignacion tipo) {
        if (asignacionRepository.existsByUsuarioIdAndCodigoExternoAndTipo(destinatario.getId(), codigoExterno, tipo)) {
            throw new IllegalStateException(
                    destinatario.getUsername() + " ya tiene asignado el código " + codigoExterno);
        }
    }

    private void verificarAccesoEmpresa(Long empresaId, AuthenticatedPrincipal solicitante) {
        if (solicitante.alcance() == Alcance.GLOBAL) {
            return;
        }
        if (!empresaId.equals(solicitante.empresaId())) {
            throw new AccessDeniedException("No podes ver las asignaciones de otra empresa");
        }
    }

    // Distinto de verificarPermiso: ademas del admin de la empresa, el
    // propio DUEÑO de la fila puede actualizar su estado (es quien hace
    // el trabajo, tiene sentido que el mismo lo vaya avanzando).
    private void verificarPuedeActualizarEstado(Asignacion asignacion, AuthenticatedPrincipal actor) {
        if (actor.alcance() == Alcance.GLOBAL) {
            return;
        }
        if (asignacion.getUsuario().getUsername().equals(actor.username())) {
            return;
        }
        Long empresaAsignacion = asignacion.getUsuario().getEmpresa() != null
                ? asignacion.getUsuario().getEmpresa().getId()
                : null;
        if (actor.alcance() == Alcance.EMPRESA && empresaAsignacion != null && empresaAsignacion.equals(actor.empresaId())) {
            return;
        }
        throw new AccessDeniedException("No podes modificar esta asignación");
    }

    private AsignacionResponse toResponse(Asignacion asignacion) {
        return new AsignacionResponse(
                asignacion.getId(),
                asignacion.getUsuario().getId(),
                asignacion.getUsuario().getUsername(),
                asignacion.getCodigoExterno(),
                asignacion.getTipo(),
                asignacion.getOrigen(),
                asignacion.getEstado(),
                asignacion.getMotivoDescarte(),
                asignacion.getEtapaDescarte(),
                asignacion.getRecomendadoPor() != null ? asignacion.getRecomendadoPor().getUsername() : null,
                asignacion.getCotizacionJson(),
                asignacion.getDetalleDesarrollo(),
                asignacion.isPendienteRevision(),
                asignacion.isEnviadoMercadoPublico());
    }

    // Igual que toResponse(), mas empresaId/empresaNombre -- solo para
    // listarTodas(). "usuario.getEmpresa()" es null para usuarios GLOBAL
    // (no pertenecen a ninguna empresa), asi que ahi queda null tambien.
    private AsignacionGlobalResponse toResponseGlobal(Asignacion asignacion) {
        var empresa = asignacion.getUsuario().getEmpresa();
        return new AsignacionGlobalResponse(
                asignacion.getId(),
                asignacion.getUsuario().getId(),
                asignacion.getUsuario().getUsername(),
                empresa != null ? empresa.getId() : null,
                empresa != null ? empresa.getNombre() : null,
                asignacion.getCodigoExterno(),
                asignacion.getTipo(),
                asignacion.getOrigen(),
                asignacion.getEstado(),
                asignacion.getMotivoDescarte(),
                asignacion.getEtapaDescarte(),
                asignacion.getRecomendadoPor() != null ? asignacion.getRecomendadoPor().getUsername() : null,
                asignacion.isPendienteRevision(),
                asignacion.isEnviadoMercadoPublico());
    }
}
