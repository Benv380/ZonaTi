package cl.zona_ti.auth_service.Repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import cl.zona_ti.auth_service.Model.Asignacion;
import cl.zona_ti.auth_service.Model.EstadoAsignacion;
import cl.zona_ti.auth_service.Model.TipoAsignacion;

@Repository
public interface AsignacionRepository extends JpaRepository<Asignacion, Long> {

    // Usado por AsignacionController para exponer "que codigos le tocan a
    // este usuario" -- lo consulta compra-service en vivo en cada listado
    // (Opcion B ya decidida: nunca cacheado en el JWT).
    List<Asignacion> findByUsuarioIdAndTipo(Long usuarioId, TipoAsignacion tipo);

    List<Asignacion> findByUsuarioId(Long usuarioId);

    // Chequeo previo a crear una fila nueva (ver AsignacionService.asignar/
    // recomendar) -- evita depender solo de la constraint unica de la BD
    // (uq_asignaciones_user_codigo_tipo), que de otra forma tira un 409
    // generico sin explicar de quien es el conflicto.
    boolean existsByUsuarioIdAndCodigoExternoAndTipo(Long usuarioId, String codigoExterno, TipoAsignacion tipo);

    // Usado por AsignacionService.eliminarMia -- ubica la fila propia (sin
    // pasar por un id) para el boton "Quitarme" (self-unassign, simetrico
    // a recomendar()/asignarme() que tampoco necesitan un id).
    Optional<Asignacion> findByUsuarioIdAndCodigoExternoAndTipo(Long usuarioId, String codigoExterno, TipoAsignacion tipo);

    // Todas las asignaciones/recomendaciones de TODOS los usuarios de una
    // empresa -- lo usa el panel del ADMIN_EMPRESA/GLOBAL para ver de un
    // vistazo lo que sus usuarios recomendaron, sin tener que entrar
    // usuario por usuario. "Usuario_EmpresaId" navega la relacion
    // Asignacion -> usuario -> empresa -> id (Spring Data la resuelve
    // sola a partir del nombre del metodo).
    List<Asignacion> findByUsuario_EmpresaId(Long empresaId);

    // Usado por AsignacionService.misCodigosEmpresa -- cruce de datos entre
    // usuarios/admins de una misma empresa: cualquiera (no solo admin) puede
    // ver que codigos ya estan tomados por un compañero, para no duplicar
    // trabajo. Acotado por tipo (a diferencia de findByUsuario_EmpresaId,
    // que trae todo mezclado) porque el front lo pide por pantalla
    // (COMPRA_AGIL o LICITACION), no las dos juntas.
    List<Asignacion> findByUsuario_EmpresaIdAndTipo(Long empresaId, TipoAsignacion tipo);

    // El panel GLOBAL de "todo el sistema" (ver AsignacionService.
    // listarTodasPaginado) -- antes usaba findAll() sin paginar, cargando
    // la tabla ENTERA en cada visita (crece sin limite con el tiempo, cada
    // asignacion/recomendacion que se crea queda ahi para siempre, incluso
    // COMPLETADO/DESCARTADO). Filtros opcionales (":x IS NULL OR ...",
    // mismo patron que CompraAgilRepository.buscarPorTextoYRegion) +
    // Pageable -- se filtra Y pagina en la BD, no en memoria.
    @Query("SELECT a FROM Asignacion a WHERE "
            + "(:empresaId IS NULL OR a.usuario.empresa.id = :empresaId) AND "
            + "(:usuarioId IS NULL OR a.usuario.id = :usuarioId) AND "
            + "(:tipo IS NULL OR a.tipo = :tipo) AND "
            + "(:estado IS NULL OR a.estado = :estado) "
            + "ORDER BY a.creadoEn DESC")
    Page<Asignacion> buscarPaginado(
            @Param("empresaId") Long empresaId,
            @Param("usuarioId") Long usuarioId,
            @Param("tipo") TipoAsignacion tipo,
            @Param("estado") EstadoAsignacion estado,
            Pageable pageable);

    // Usado por AsignacionService.resumenGlobal -- StatCards de Home.jsx
    // para un usuario GLOBAL ("Licitaciones activas"/"Compras Ágiles
    // activas", sumando TODAS las empresas). Separado de buscarPaginado a
    // proposito: un conteo no necesita traer las filas completas a
    // memoria, mucho mas liviano que pedir una pagina grande solo para
    // contar.
    long countByEstadoInAndTipo(List<EstadoAsignacion> estados, TipoAsignacion tipo);

    // Panel GLOBAL "Pendientes de revision" (ver AsignacionService.
    // pendientesRevisionGlobal) -- a proposito SIN paginar ni filtrar,
    // separado de buscarPaginado: este conjunto se autolimita solo (son
    // items ESPERANDO una accion puntual de un admin, se vacian al
    // aprobarse -- no crecen sin fin como el resto de la tabla).
    List<Asignacion> findByPendienteRevisionTrue();

    // Seccion "Compras Ágiles listas" de Home.jsx, para GLOBAL -- mismo
    // motivo que findByPendienteRevisionTrue (conjunto acotado por
    // naturaleza: solo compra-agil + completado, no toda la tabla).
    List<Asignacion> findByTipoAndEstado(TipoAsignacion tipo, EstadoAsignacion estado);

    // Uso EXCLUSIVO de InternalController (compra-service lo consulta para
    // decidir si una compra/licitacion cerrada "tuvo intervencion" antes de
    // purgarla -- ver LimpiezaScheduler en compra-service). A diferencia de
    // TODOS los demas metodos de este repositorio, esto es a proposito
    // GLOBAL/sin filtro de usuario ni empresa: alcanza con que exista
    // CUALQUIER asignacion (de cualquier empresa, cualquier estado, incluso
    // DESCARTADO) para considerar que "alguien la miro".
    @Query("SELECT DISTINCT a.codigoExterno FROM Asignacion a WHERE a.tipo = :tipo")
    List<String> findCodigosConAsignacion(@Param("tipo") TipoAsignacion tipo);
}
