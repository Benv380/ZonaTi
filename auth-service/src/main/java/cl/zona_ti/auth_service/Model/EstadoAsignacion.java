package cl.zona_ti.auth_service.Model;

// Flujo de trabajo sobre una asignacion/recomendacion puntual. DESCARTADO
// se puede alcanzar desde cualquiera de los otros estados (se guarda en
// "etapaDescarte" en que estado estaba justo antes, ver Asignacion.java),
// el resto avanza en orden directo hasta COMPLETADO.
//
// No hay un estado "EN_REVISION": la supervision de un usuario marcado
// (ver User.requiereSupervision) no frena el flujo de estados, solo prende
// aparte "Asignacion.pendienteRevision" -- ver
// AsignacionService.actualizarEstado/aprobarRevision.
public enum EstadoAsignacion {
    ASIGNADO,
    ANALISIS,
    DESARROLLO,
    COMPLETADO,
    DESCARTADO
}
