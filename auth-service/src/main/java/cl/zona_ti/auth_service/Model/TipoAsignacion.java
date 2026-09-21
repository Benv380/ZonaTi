package cl.zona_ti.auth_service.Model;

// A que dominio pertenece un codigo_externo asignado -- compra-service
// tiene 2 entidades separadas (LicitacionEntity/CompraAgilEntity) con sus
// propios codigos, este enum evita que se mezclen al filtrar.
public enum TipoAsignacion {
    LICITACION,
    COMPRA_AGIL
}
