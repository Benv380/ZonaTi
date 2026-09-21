package cl.zona_ti.auth_service.Model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "empresas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Empresa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    private String rut;

    private String rubro;

    @Column(name = "representante_legal")
    private String representanteLegal;

    private String direccion;

    private String comuna;

    private String region;

    private String telefono;

    private String email;

    @Column(name = "nombre_fantasia")
    private String nombreFantasia;

    // Distinto de "rubro": esto es la actividad economica oficial
    // registrada en el SII, no el rubro que se usa para filtrar
    // licitaciones (ver perfil_busqueda).
    private String giro;

    @Column(name = "rut_representante_legal")
    private String rutRepresentanteLegal;

    // "MICRO" | "PEQUENA" | "MEDIANA" | "GRANDE" (CHECK en schema.sql) --
    // clasificacion oficial por tamaño, relevante para reglas/beneficios
    // de Mercado Publico (EMT).
    @Column(name = "tamano_empresa")
    private String tamanoEmpresa;

    @Column(name = "chileproveedores_registrado")
    private boolean chileproveedoresRegistrado;

    @Column(name = "chileproveedores_codigo")
    private String chileproveedoresCodigo;

    @Column(name = "sitio_web")
    private String sitioWeb;

    // Sin @Lob a proposito -- con @Lob, Hibernate mapea esto como OID
    // (large object) en vez de BYTEA y la escritura revienta con "column
    // logo is of type bytea but expression is of type bigint". Mismo bug
    // ya pisado (y evitado) con los adjuntos de compra-service.
    private byte[] logo;

    @Column(name = "logo_tipo_contenido")
    private String logoTipoContenido;

    // Texto libre para el pie de una cotizacion generada (ver
    // GenerarCotizacionBoton.jsx / generarCotizacionPdf.js) -- cada
    // empresa define sus propios terminos, en vez del texto fijo que
    // tenia antes el generador. Editable solo por GLOBAL o por el
    // ADMIN_EMPRESA de esa empresa (ver MarcaCotizacionService),
    // visible para cualquiera de la empresa.
    @Column(name = "condiciones_comerciales", columnDefinition = "TEXT")
    private String condicionesComerciales;

    // Color principal de la cotizacion en PDF (hex, ej. "#A61928") --
    // elegido de una paleta fija chica en el front, no un selector libre.
    // Null usa el rojo por defecto (ver generarCotizacionPdf.js).
    @Column(name = "color_principal")
    private String colorPrincipal;

    // Datos bancarios para el pie de la cotizacion en PDF (ver
    // generarCotizacionPdf.js) -- mismo criterio de edicion que
    // condicionesComerciales/colorPrincipal (Mi Empresa, no Administracion).
    private String banco;

    @Column(name = "tipo_cuenta")
    private String tipoCuenta;

    @Column(name = "numero_cuenta")
    private String numeroCuenta;

    // Credenciales de Chile Compra (Mercado Publico) para el flujo
    // automatico de envio de cotizaciones (Power Automate) -- ver
    // ChileCompraCredencialesService. "chileCompraPasswordCifrada" NUNCA
    // se guarda en texto plano: llega ya cifrada con AES-256-GCM antes de
    // pasar por acá, y solo se descifra en el momento exacto que la
    // automatizacion la necesita (GET dedicado, nunca en EmpresaResponse
    // ni en ningun otro endpoint general).
    @Column(name = "chile_compra_usuario")
    private String chileCompraUsuario;

    @Column(name = "chile_compra_password_cifrada", columnDefinition = "TEXT")
    private String chileCompraPasswordCifrada;

    // "ACTIVA" | "INACTIVA" (CHECK en schema.sql) -- soft-disable, para
    // poder desactivar una empresa sin borrarla (a diferencia de
    // eliminar(), que ademas exige que no tenga usuarios).
    private String estado;

    // A diferencia de creado_en (la pone la BD), este SI se actualiza
    // desde el codigo en cada crear/actualizar -- ver
    // EmpresaService.aplicarDatos.
    @Column(name = "actualizado_en")
    private LocalDateTime actualizadoEn;

    // La pone la BD (DEFAULT now(), ver schema.sql) -- de solo lectura
    // desde la app.
    @Column(name = "creado_en", insertable = false, updatable = false)
    private LocalDateTime creadoEn;
}
