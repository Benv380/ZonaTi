package cl.zona_ti.auth_service.Dto;

import java.time.LocalDateTime;

// "tieneLogo" en vez del binario -- mandar los bytes del logo en cada
// GET /auth/empresas sería pesado e innecesario para listar/tabular. El
// front, si tieneLogo=true, pide el binario aparte via
// GET /auth/empresas/{id}/logo (mismo patron que los adjuntos de
// compra-service: JSON liviano + binario bajo demanda).
public record EmpresaResponse(
        Long id,
        String nombre,
        String rut,
        String rubro,
        String representanteLegal,
        String direccion,
        String comuna,
        String region,
        String telefono,
        String email,
        String nombreFantasia,
        String giro,
        String rutRepresentanteLegal,
        String tamanoEmpresa,
        boolean chileproveedoresRegistrado,
        String chileproveedoresCodigo,
        String sitioWeb,
        String estado,
        boolean tieneLogo,
        LocalDateTime actualizadoEn) {
}
