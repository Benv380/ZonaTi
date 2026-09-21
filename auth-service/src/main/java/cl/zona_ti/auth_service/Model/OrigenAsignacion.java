package cl.zona_ti.auth_service.Model;

// Quien creo la fila -- ADMIN: la asigno un ADMIN_EMPRESA/GLOBAL de
// arriba hacia abajo (flujo original). USER: el propio usuario la
// recomendo a su supervisor, sin paso de aprobacion -- queda visible
// directo (ver AsignacionService.recomendar).
public enum OrigenAsignacion {
    ADMIN,
    USER
}
