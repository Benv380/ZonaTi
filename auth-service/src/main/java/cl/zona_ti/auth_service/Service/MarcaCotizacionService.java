package cl.zona_ti.auth_service.Service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Set;

import javax.imageio.ImageIO;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import cl.zona_ti.auth_service.Dto.ActualizarMarcaCotizacionRequest;
import cl.zona_ti.auth_service.Dto.MarcaCotizacionResponse;
import cl.zona_ti.auth_service.Model.Alcance;
import cl.zona_ti.auth_service.Model.Empresa;
import cl.zona_ti.auth_service.Repository.EmpresaRepository;
import cl.zona_ti.auth_service.Security.AuthenticatedPrincipal;
import jakarta.persistence.EntityNotFoundException;

// "Base de cotizacion" de una empresa (logo, condiciones comerciales,
// direccion y telefono) -- mismo patron de permiso que
// PerfilBusquedaService: GLOBAL puede cualquier empresa, EMPRESA
// (ADMIN_EMPRESA) solo la propia. A diferencia
// de ese, el GET tambien lo puede pedir un USUARIO de la misma empresa
// (solo lectura, ver verificarPuedeVer) -- necesita saber que membrete va
// a llevar la cotizacion que el mismo puede terminar generando.
@Service
public class MarcaCotizacionService {

    // "Muy bajo" a proposito -- esto es un membrete chico dentro de un
    // PDF, no una imagen cualquiera. Si hace falta mas resolucion despues,
    // se sube este numero, no se saca el limite.
    private static final int ANCHO_MAX_PX = 400;
    private static final int ALTO_MAX_PX = 200;
    private static final int PESO_MAX_BYTES = 300_000; // 300 KB

    // Paleta fija -- no un selector de color libre ("nada especial", pidio
    // el usuario). Mismos valores que el <select> de MiEmpresa.jsx; si se
    // agrega/saca un color hay que tocar los 2 lugares.
    private static final Set<String> COLORES_PERMITIDOS = Set.of(
            "#A61928", // rojo (default si no se elige nada)
            "#15438A", // azul
            "#1B5E20", // verde
            "#333333", // gris oscuro
            "#6A1B9A"  // morado
    );

    private final EmpresaRepository empresaRepository;

    public MarcaCotizacionService(EmpresaRepository empresaRepository) {
        this.empresaRepository = empresaRepository;
    }

    public MarcaCotizacionResponse obtener(Long empresaId, AuthenticatedPrincipal solicitante) {
        verificarPuedeVer(empresaId, solicitante);
        return toResponse(obtenerEmpresa(empresaId));
    }

    public MarcaCotizacionResponse actualizar(Long empresaId, ActualizarMarcaCotizacionRequest request, AuthenticatedPrincipal actor) {
        verificarPuedeEditar(empresaId, actor);
        Empresa empresa = obtenerEmpresa(empresaId);

        if (request.getLogoBase64() != null && !request.getLogoBase64().isBlank()) {
            byte[] bytes = decodificarYValidarLogo(request.getLogoBase64());
            empresa.setLogo(bytes);
            empresa.setLogoTipoContenido(request.getLogoTipoContenido());
        }
        empresa.setCondicionesComerciales(request.getCondicionesComerciales());
        empresa.setDireccion(request.getDireccion());
        empresa.setTelefono(request.getTelefono());
        empresa.setColorPrincipal(validarColor(request.getColorPrincipal()));
        empresa.setBanco(request.getBanco());
        empresa.setTipoCuenta(request.getTipoCuenta());
        empresa.setNumeroCuenta(request.getNumeroCuenta());

        return toResponse(empresaRepository.save(empresa));
    }

    // Solo se acepta uno de la paleta fija, o null/vacio (usa el default
    // del generador de PDF) -- nunca un hex arbitrario, aunque alguien le
    // pegue directo a la API sin pasar por el <select>.
    private String validarColor(String colorPrincipal) {
        if (colorPrincipal == null || colorPrincipal.isBlank()) {
            return null;
        }
        if (!COLORES_PERMITIDOS.contains(colorPrincipal)) {
            throw new IllegalArgumentException("Ese color no está disponible. Hay que elegir uno de la paleta");
        }
        return colorPrincipal;
    }

    public Empresa obtenerParaLogo(Long empresaId, AuthenticatedPrincipal solicitante) {
        verificarPuedeVer(empresaId, solicitante);
        return obtenerEmpresa(empresaId);
    }

    // Decodifica el base64 y valida tamaño de archivo Y dimensiones reales
    // (decodificando la imagen, no solo confiando en el peso del archivo
    // -- un PNG chico en bytes puede igual tener miles de pixeles).
    private byte[] decodificarYValidarLogo(String logoBase64) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(logoBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("El logo no es un archivo válido");
        }
        if (bytes.length > PESO_MAX_BYTES) {
            throw new IllegalArgumentException(
                    "El logo pesa demasiado (máx. " + (PESO_MAX_BYTES / 1000) + " KB)");
        }
        BufferedImage imagen;
        try {
            imagen = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new IllegalArgumentException("No se pudo leer el logo como imagen");
        }
        if (imagen == null) {
            throw new IllegalArgumentException("El archivo no es una imagen válida");
        }
        if (imagen.getWidth() > ANCHO_MAX_PX || imagen.getHeight() > ALTO_MAX_PX) {
            throw new IllegalArgumentException(
                    "El logo es demasiado grande (máx. " + ANCHO_MAX_PX + "x" + ALTO_MAX_PX + " píxeles, "
                            + "esta imagen es " + imagen.getWidth() + "x" + imagen.getHeight() + ")");
        }
        return bytes;
    }

    // GLOBAL o cualquier alcance de la MISMA empresa (EMPRESA o USUARIO) --
    // ver el comentario de la clase.
    private void verificarPuedeVer(Long empresaId, AuthenticatedPrincipal solicitante) {
        if (solicitante.alcance() == Alcance.GLOBAL) {
            return;
        }
        if (!empresaId.equals(solicitante.empresaId())) {
            throw new AccessDeniedException("No podes ver la base de cotización de otra empresa");
        }
    }

    // GLOBAL o el ADMIN_EMPRESA (alcance EMPRESA) de esa MISMA empresa --
    // un USUARIO puede ver pero no editar (ademas ya lo bloquea
    // @PreAuthorize en el controller, esto es la segunda capa).
    private void verificarPuedeEditar(Long empresaId, AuthenticatedPrincipal actor) {
        if (actor.alcance() == Alcance.GLOBAL) {
            return;
        }
        if (actor.alcance() != Alcance.EMPRESA || !empresaId.equals(actor.empresaId())) {
            throw new AccessDeniedException("No podes editar la base de cotización de otra empresa");
        }
    }

    private Empresa obtenerEmpresa(Long empresaId) {
        return empresaRepository.findById(empresaId)
                .orElseThrow(() -> new EntityNotFoundException("Empresa no encontrada: " + empresaId));
    }

    private MarcaCotizacionResponse toResponse(Empresa empresa) {
        return new MarcaCotizacionResponse(
                empresa.getId(),
                empresa.getNombre(),
                empresa.getRut(),
                empresa.getDireccion(),
                empresa.getTelefono(),
                empresa.getLogo() != null,
                empresa.getCondicionesComerciales(),
                empresa.getColorPrincipal(),
                empresa.getBanco(),
                empresa.getTipoCuenta(),
                empresa.getNumeroCuenta());
    }
}
