package cl.zona_ti.auth_service.Dto;

// Un filtro de busqueda guardado -- desde el multi-filtro (2026-09-23) una
// empresa tiene una LISTA de estos, no uno solo (ver
// PerfilBusquedaService.listar/misPerfiles). "id" identifica cada uno para
// poder editarlo/borrarlo por separado.
public record PerfilBusquedaResponse(
        Long id,
        Long empresaId,
        String nombre,
        String rubro,
        String palabrasClave,
        String regionCodigo,
        String regionNombre
) {
}
