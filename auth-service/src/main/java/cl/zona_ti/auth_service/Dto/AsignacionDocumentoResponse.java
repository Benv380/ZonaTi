package cl.zona_ti.auth_service.Dto;

// Metadata para listar -- nunca el binario (ver
// AsignacionDocumentoRepository.listarPorAsignacion, que lo excluye a
// proposito de la consulta).
public record AsignacionDocumentoResponse(Long id, String nombreArchivo, Integer tamanoBytes) {
}
