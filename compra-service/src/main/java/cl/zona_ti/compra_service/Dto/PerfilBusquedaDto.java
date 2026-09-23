package cl.zona_ti.compra_service.Dto;

// Copia deliberada de la forma de PerfilBusquedaResponse en auth-service --
// ver Model/Alcance.java (mismo paquete conceptual) para el porque no se
// comparte codigo entre servicios (database-per-service). Un filtro
// puntual -- desde el multi-filtro (2026-09-23) PerfilClient.misPerfiles()
// devuelve una LISTA de estos, no uno solo.
public record PerfilBusquedaDto(
        Long id,
        Long empresaId,
        String nombre,
        String rubro,
        String palabrasClave,
        String regionCodigo,
        String regionNombre
) {
}
