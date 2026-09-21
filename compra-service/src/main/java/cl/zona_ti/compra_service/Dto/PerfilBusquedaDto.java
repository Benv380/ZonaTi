package cl.zona_ti.compra_service.Dto;

// Copia deliberada de la forma de PerfilBusquedaResponse en auth-service --
// ver Model/Alcance.java (mismo paquete conceptual) para el porque no se
// comparte codigo entre servicios (database-per-service).
public record PerfilBusquedaDto(
        Long empresaId,
        String rubro,
        String palabrasClave,
        String regionCodigo,
        String regionNombre,
        boolean perfilCompletado
) {
}
