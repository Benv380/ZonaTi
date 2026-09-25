package cl.zona_ti.auth_service.Dto;

// Version minima de AsignacionResponse para el cruce de datos entre
// usuarios/admins de una misma empresa (ver AsignacionController.
// asignacionesDeMiEmpresa): a diferencia de ese record, este lo puede
// pedir CUALQUIER usuario (no solo GLOBAL/EMPRESA), asi que solo viaja lo
// minimo para saber "este codigo ya lo tiene fulano" -- nada de estado,
// cotizacion ni motivo de descarte de un compañero.
public record AsignacionEmpresaResumen(
        String codigoExterno,
        String username
) {
}
