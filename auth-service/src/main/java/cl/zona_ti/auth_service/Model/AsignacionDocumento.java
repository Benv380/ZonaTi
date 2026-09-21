package cl.zona_ti.auth_service.Model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Documento subido a mano en la fase ANALISIS de una asignacion (ver
// CotizacionCompraAgil.jsx, "Documentación necesaria") -- una asignacion
// puede tener varios. Sin @Lob en "contenido" a proposito: con @Lob,
// Hibernate mapea byte[] como OID (large object) en vez de BYTEA y la
// escritura revienta -- mismo bug ya pisado con adjuntos/logo.
@Entity
@Table(name = "asignacion_documento")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AsignacionDocumento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // LAZY: al listar/descargar no hace falta la Asignacion completa, solo
    // se usa para el chequeo de permiso al momento de subir (ver
    // AsignacionDocumentoService).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asignacion_id", nullable = false)
    private Asignacion asignacion;

    @Column(name = "nombre_archivo", nullable = false)
    private String nombreArchivo;

    @Column(name = "tipo_contenido")
    private String tipoContenido;

    @Column(name = "tamano_bytes")
    private Integer tamanoBytes;

    @Column(nullable = false)
    private byte[] contenido;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private LocalDateTime creadoEn;
}
