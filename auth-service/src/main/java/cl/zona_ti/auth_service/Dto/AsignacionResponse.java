package cl.zona_ti.auth_service.Dto;

import cl.zona_ti.auth_service.Model.EstadoAsignacion;
import cl.zona_ti.auth_service.Model.OrigenAsignacion;
import cl.zona_ti.auth_service.Model.TipoAsignacion;

public record AsignacionResponse(
        Long id,
        Long usuarioId,
        // Nombre de usuario incluido a proposito -- el panel de "toda la
        // empresa" (listarPorEmpresa) mezcla asignaciones de varios
        // usuarios distintos, y sin esto habria que resolverlo aparte
        // para saber de quien es cada fila.
        String username,
        String codigoExterno,
        TipoAsignacion tipo,
        OrigenAsignacion origen,
        EstadoAsignacion estado,
        String motivoDescarte,
        EstadoAsignacion etapaDescarte,
        // Solo distinto de null cuando origen=USER y alguien recomendo
        // esto para OTRA persona (no para si mismo) -- ver
        // AsignacionService.recomendar.
        String recomendadoPorUsername,
        // Borrador de cotizacion (ver Asignacion.cotizacionJson) -- viaja
        // tal cual en /auth/me/asignaciones/detalle para que el dashboard
        // no tenga que pedirlo aparte al abrir el modal.
        String cotizacionJson,
        // Texto libre de la fase DESARROLLO (ver Asignacion.detalleDesarrollo),
        // mismo criterio que cotizacionJson.
        String detalleDesarrollo,
        // true si el dueño de la fila la completo el mismo estando marcado
        // con requiere_supervision, y todavia no la revisa un admin (ver
        // Asignacion.pendienteRevision) -- alimenta la lista "Pendientes de
        // revisión" en Mi Empresa.
        boolean pendienteRevision,
        // true si un flujo externo (Power Automate Desktop) ya subio esta
        // compra agil a Mercado Publico (ver Asignacion.enviadoMercadoPublico).
        boolean enviadoMercadoPublico
) {
}
