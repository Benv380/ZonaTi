package cl.zona_ti.auth_service.Dto;

import cl.zona_ti.auth_service.Model.TipoAsignacion;

// Lo que necesita la extensión de navegador para inyectar una cotización en
// Mercado Público (reemplaza al viejo flujo de Power Automate Desktop). Lo
// devuelve AsignacionService.prepararEnvio, que ANTES valida que la fila esté
// lista para enviar (COMPLETADO, aprobada y no enviada todavía).
//
// "cotizacionJson" es el mismo blob opaco que arma CotizacionCompraAgil.jsx y
// se guarda en Asignacion.cotizacionJson -- acá viaja tal cual; la extensión
// es la que lo mapea al formato que espera el portal de Mercado Público.
//
// "rutEmpresa"/"nombreEmpresa": la empresa a nombre de la cual se cotiza. En
// Mercado Público una persona puede representar a varias empresas y elige
// con cuál opera después de iniciar sesión; como la extensión envía sola, la
// usa para verificar que la empresa activa en MP sea esta ANTES de enviar.
public record PrepararEnvioResponse(
        Long asignacionId,
        String codigoCompra,
        TipoAsignacion tipo,
        String cotizacionJson,
        String rutEmpresa,
        String nombreEmpresa
) {
}
