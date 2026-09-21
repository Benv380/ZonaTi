package cl.zona_ti.auth_service.Model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

// Asignacion manual de una licitacion/compra agil puntual a un usuario --
// capa adicional sobre perfil_busqueda (ver schema.sql para el porque).
// Ademas de la asignacion "de arriba hacia abajo" original (un admin se la
// da a un usuario), esta misma tabla cubre las recomendaciones "de abajo
// hacia arriba" (un usuario la propone) -- ver "origen". No son 2
// conceptos separados: como una recomendacion no necesita aprobacion (ver
// AsignacionService.recomendar), en la practica es lo mismo registro con
// el mismo flujo de estados, solo cambia quien la origino.
@Entity
@Table(name = "asignaciones")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Asignacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // LAZY a proposito (a diferencia de User.role/User.empresa): esta
    // entidad se lee siempre en listas (findByUsuarioIdAndTipo) donde no
    // hace falta el User completo, solo el codigo_externo/tipo.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User usuario;

    @Column(name = "codigo_externo", nullable = false)
    private String codigoExterno;

    @Enumerated(EnumType.STRING)
    private TipoAsignacion tipo;

    @Enumerated(EnumType.STRING)
    private OrigenAsignacion origen = OrigenAsignacion.ADMIN;

    @Enumerated(EnumType.STRING)
    private EstadoAsignacion estado = EstadoAsignacion.ASIGNADO;

    // Solo se llenan cuando estado = DESCARTADO (ver
    // AsignacionService.actualizarEstado). "etapaDescarte" guarda el
    // estado en el que estaba justo ANTES de descartarla, para no perder
    // esa historia (ej. se descarto estando en ANALISIS vs en DESARROLLO
    // cuenta una historia distinta).
    @Column(name = "motivo_descarte")
    private String motivoDescarte;

    @Enumerated(EnumType.STRING)
    @Column(name = "etapa_descarte")
    private EstadoAsignacion etapaDescarte;

    // Solo se llena cuando origen=USER y el destinatario ("usuario" de
    // arriba) es OTRA persona -- quien la recomendo, si no es la misma
    // que la recibe. Ver AsignacionService.recomendar.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recomendado_por")
    private User recomendadoPor;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private LocalDateTime creadoEn;

    // Borrador de cotizacion armado en la fase ANALISIS (ver
    // CotizacionCompraAgil.jsx en el front) -- JSON opaco para esta
    // entidad, lo arma y calcula el front. Null mientras no se haya
    // guardado nada todavia.
    @Column(name = "cotizacion_json", columnDefinition = "TEXT")
    private String cotizacionJson;

    // Texto libre de la fase DESARROLLO (ver DetalleDesarrollo.jsx en el
    // front) -- distinto de la cotizacion de ANALISIS, independiente de
    // ella. 255 caracteres, mismo limite que el campo real de Mercado
    // Publico en el que se basa.
    @Column(name = "detalle_desarrollo", length = 255)
    private String detalleDesarrollo;

    // Marca aparte del flujo de estados (no bloquea el paso a COMPLETADO,
    // ver AsignacionService.actualizarEstado) -- se prende cuando el dueño
    // de la fila tiene User.requiereSupervision en true y es EL MISMO quien
    // completa su propio trabajo. Mientras este en true aparece en la lista
    // de "Pendientes de revisión" de su empresa; un ADMIN_EMPRESA/GLOBAL la
    // apaga al revisarla (ver AsignacionService.aprobarRevision).
    @Column(name = "pendiente_revision", nullable = false)
    private boolean pendienteRevision;

    // Se prende cuando un flujo externo (Power Automate Desktop) ya subio
    // esta compra agil a Mercado Publico -- ver
    // AsignacionService.marcarEnviada. Ese flujo solo debe tomar filas
    // COMPLETADO con pendienteRevision=false (ya aprobadas) y esta marca en
    // false (todavia no enviadas), para no reenviar la misma dos veces.
    @Column(name = "enviado_mercado_publico", nullable = false)
    private boolean enviadoMercadoPublico;
}
