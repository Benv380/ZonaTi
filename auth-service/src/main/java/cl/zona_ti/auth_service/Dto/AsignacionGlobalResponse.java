package cl.zona_ti.auth_service.Dto;

import cl.zona_ti.auth_service.Model.EstadoAsignacion;
import cl.zona_ti.auth_service.Model.OrigenAsignacion;
import cl.zona_ti.auth_service.Model.TipoAsignacion;

// Igual que AsignacionResponse, mas empresaId/empresaNombre -- exclusivo
// del panel GLOBAL de "todo el sistema" (ver
// AsignacionController.listarTodas), donde una sola tabla mezcla usuarios
// de distintas empresas y hace falta saber de cual es cada fila para
// poder filtrar. El resto de los endpoints (por usuario, por empresa)
// siguen usando AsignacionResponse -- ahi la empresa ya es implicita
// (todo el panel es de UNA empresa) o no aplica.
public record AsignacionGlobalResponse(
        Long id,
        Long usuarioId,
        String username,
        Long empresaId,
        String empresaNombre,
        String codigoExterno,
        TipoAsignacion tipo,
        OrigenAsignacion origen,
        EstadoAsignacion estado,
        String motivoDescarte,
        EstadoAsignacion etapaDescarte,
        String recomendadoPorUsername,
        boolean pendienteRevision,
        boolean enviadoMercadoPublico
) {
}
