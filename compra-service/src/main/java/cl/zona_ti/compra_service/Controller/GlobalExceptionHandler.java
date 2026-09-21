package cl.zona_ti.compra_service.Controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Mapea el 403 de "no tenes acceso a esta licitacion/compra" (ver
// LicitacionService.getLicitacionByCodigo / CompraAgilService.getDetalleByCodigo)
// a un mensaje claro, en vez del cuerpo generico/vacio que devuelve Spring
// Security por defecto.
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<String> handleForbidden(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
    }
}
