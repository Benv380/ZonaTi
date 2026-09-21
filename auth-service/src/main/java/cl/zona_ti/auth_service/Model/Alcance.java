package cl.zona_ti.auth_service.Model;

// Nivel de permiso real de un rol -- ver comentario en Role y en
// schema.sql. El nombre del rol (ADMIN, etc.) es solo una
// etiqueta; esto es lo que se chequea en el codigo de autorizacion.
public enum Alcance {
    // Acceso total, cualquier empresa (ADMIN).
    GLOBAL,
    // Acotado a la propia empresa (ADMIN_EMPRESA).
    EMPRESA,
    // Solo lectura/busqueda con el filtro heredado de su empresa (USER).
    USUARIO
}
