package cl.zona_ti.compra_service.Model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Metadato + binario de un adjunto de Compra Agil (AdjuntoDto.AdjuntoArchivo).
// El binario lo baja y guarda CompraAgilSyncScheduler en background (via
// AdjuntoService.sincronizarBinarios); AdjuntoService.descargar() sirve
// primero desde "contenido" si ya esta, y solo si no hay nada cacheado cae
// al streaming en vivo desde adjunto.mercadopublico.cl (el camino viejo).
@AllArgsConstructor
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "adjuntos")
public class AdjuntoEntity {

    @Id
    private String id;

    @Column(name = "compra_agil_codigo")
    private String compraAgilCodigo;

    @Column(name = "nombre_archivo")
    private String nombreArchivo;

    @Column(name = "tipo_contenido")
    private String tipoContenido;

    @Column(name = "tamano_bytes")
    private Integer tamanoBytes;

    // Sin @Lob a proposito -- ver comentario equivalente en
    // AdjuntoLicitacionEntity (mapea a OID/bigint en vez de BYTEA).
    private byte[] contenido;

    @Column(name = "fecha_sync")
    private LocalDateTime fechaSync;
}
