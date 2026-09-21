package cl.zona_ti.auth_service.Model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Catalogo de roles (ver schema.sql). "alcance" es lo que determina los
// permisos reales, no el nombre puntual -- asi, si en el futuro se agrega
// otro nombre con el mismo nivel, no hace falta duplicar logica de
// autorizacion en cada endpoint.
@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    @Enumerated(EnumType.STRING)
    private Alcance alcance;

    private String descripcion;
}
