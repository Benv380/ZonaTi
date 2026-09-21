package cl.zona_ti.auth_service.Model;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Filtro de busqueda generado por el LLM en el onboarding (rubro/palabras
// clave/region) -- 1:1 con Empresa, no con User: todos los usuarios de una
// empresa heredan el mismo filtro (ver decision de diseño en el chat).
@Entity
@Table(name = "perfil_busqueda")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PerfilBusqueda {

    // Mismo id que la empresa duena (relacion 1:1 real, no solo logica) --
    // @MapsId hace que empresaId se llene solo desde "empresa" al guardar.
    @Id
    private Long empresaId;

    @OneToOne
    @MapsId
    @JoinColumn(name = "empresa_id")
    private Empresa empresa;

    private String rubro;

    private String palabrasClave;

    private String regionCodigo;

    private String regionNombre;

    private Boolean perfilCompletado = false;

    private LocalDateTime actualizadoEn;
}
