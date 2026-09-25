package cl.zona_ti.auth_service.Repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import cl.zona_ti.auth_service.Model.Asignacion;
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

    // El panel GLOBAL de "todo el sistema" (ver AsignacionService.listarTodas)
    // usa findAll(), ya heredado de JpaRepository -- no hace falta un metodo
    // propio. usuario/empresa se resuelven LAZY al mapear, mismo patron
    // seguro que findByUsuario_EmpresaId (open-in-view=true por defecto).
}
