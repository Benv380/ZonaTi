package cl.zona_ti.auth_service.Model;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
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

// Filtro de busqueda de una empresa (rubro/palabras clave/region) -- N:1
// con Empresa (desde 2026-09-23; antes era 1:1, un unico filtro por
// empresa). "nombre" identifica cada uno para el usuario (ej. "Construcción
// RM", "Electricidad Valparaíso") -- Compra Agil ("Ver mi filtro", ver
// CompraAgilService.buscarPorPerfil en compra-service) busca la UNION de
// TODOS los filtros de la empresa, no uno solo.
@Entity
@Table(name = "perfil_busqueda")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PerfilBusqueda {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "empresa_id", nullable = false)
    private Empresa empresa;

    private String nombre;

    private String rubro;

    private String palabrasClave;

    private String regionCodigo;

    private String regionNombre;

    // Sin uso real desde el multi-filtro (una fila que existe ya esta
    // "completa" por definicion) -- se deja en true siempre al crear/editar,
    // no se borra la columna (mismo criterio de siempre en este proyecto).
    private Boolean perfilCompletado = true;

    private LocalDateTime actualizadoEn;
}
