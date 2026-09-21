package cl.zona_ti.auth_service.Controller;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.persistence.EntityNotFoundException;

// Mapea las excepciones de negocio de EmpresaService/UsuarioService a
// codigos HTTP con mensaje, en vez de dejar que Spring devuelva un 500
// generico sin explicacion.
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<String> handleNotFound(EntityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    @ExceptionHandler({ IllegalArgumentException.class, IllegalStateException.class })
    public ResponseEntity<String> handleBadRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    // Un @Valid fallido (ej. CrearUsuarioRequest.password con menos de 8
    // caracteres) por defecto cae en el resolver generico de Spring, que
    // devuelve un JSON grande y poco legible (o directamente el front
    // termina mostrando solo el codigo de estado, ver authFetch -- eso fue
    // justamente lo que paso). Acá se agarra el PRIMER error de campo y se
    // devuelve su mensaje tal cual (los DTOs ya traen el texto en español
    // via @NotBlank(message=...)/@Size(message=...)), mismo formato de
    // texto plano que el resto de los handlers de esta clase.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handleValidacion(MethodArgumentNotValidException e) {
        FieldError primerError = e.getBindingResult().getFieldError();
        String mensaje = primerError != null ? primerError.getDefaultMessage() : "Datos inválidos";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mensaje);
    }

    // Ej: un ADMIN_EMPRESA tratando de tocar un usuario de otra empresa
    // (ver AsignacionService.verificarPermiso).
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<String> handleForbidden(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
    }

    // Choca contra uq_users_username o uq_empresas_rut (ver schema.sql).
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<String> handleConflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body("El registro ya existe o viola una restriccion unica");
    }
}
