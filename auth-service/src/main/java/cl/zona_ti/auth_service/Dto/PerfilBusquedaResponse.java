package cl.zona_ti.auth_service.Dto;

// perfilCompletado en false + el resto null significa "todavia no se
// configuro nada" (ni por LLM -- pendiente -- ni a mano). compra-service
// lo usa para decidir si arma la busqueda en vivo o no (ver PerfilClient
// alli): si no esta completo, NO se pega a Mercado Publico sin filtro.
public record PerfilBusquedaResponse(
        Long empresaId,
        String rubro,
        String palabrasClave,
        String regionCodigo,
        String regionNombre,
        boolean perfilCompletado
) {
    public static PerfilBusquedaResponse vacio(Long empresaId) {
        return new PerfilBusquedaResponse(empresaId, null, null, null, null, false);
    }
}
