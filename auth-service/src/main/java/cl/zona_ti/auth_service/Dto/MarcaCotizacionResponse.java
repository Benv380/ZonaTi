package cl.zona_ti.auth_service.Dto;

// "Base de cotizacion" de una empresa: lo que se usa para armar el
// encabezado/pie de una cotizacion en PDF (ver generarCotizacionPdf.js).
// nombre/rut son de solo lectura desde aca (se reusan de la ficha oficial
// de la empresa, exclusiva GLOBAL en Administracion) -- direccion/telefono
// SI son editables desde este endpoint (ver
// MarcaCotizacionService.actualizar), ademas de logo, condiciones
// comerciales y el color principal.
public record MarcaCotizacionResponse(
        Long empresaId,
        String nombre,
        String rut,
        String direccion,
        String telefono,
        // Igual que EmpresaResponse.tieneLogo -- el binario se sirve
        // aparte (ver MarcaCotizacionController.obtenerLogo), no tiene
        // sentido mandarlo en cada GET de este endpoint.
        boolean tieneLogo,
        String condicionesComerciales,
        String colorPrincipal,
        // Datos bancarios para el pie de la cotizacion en PDF -- mismo
        // criterio de edicion que direccion/telefono (ver
        // generarCotizacionPdf.js).
        String banco,
        String tipoCuenta,
        String numeroCuenta
) {
}
