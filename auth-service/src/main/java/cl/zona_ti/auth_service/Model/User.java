package cl.zona_ti.auth_service.Model;

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

//anotaciones para no tener que construir todos los constructores, getters y setters manualmente
@AllArgsConstructor
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String lastName;
    private String email;
    private String username;
    private String password;

    // EAGER a proposito: el rol (y su alcance) se necesita SIEMPRE que se
    // arma el JWT o se autoriza algo -- evitar el N+1 de un LAZY que igual
    // se va a resolver enseguida en el 100% de los casos.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id")
    private Role role;

    // NULL para roles GLOBAL (ADMIN) -- no pertenecen a una
    // empresa puntual. Obligatorio en la practica para ADMIN_EMPRESA/USER
    // (se valida en UsuarioService, no con NOT NULL en la BD).
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "empresa_id")
    private Empresa empresa;

    // Marca administrativa ("es nuevo en el sistema", tipicamente) --
    // exclusiva de ADMIN_EMPRESA/GLOBAL, nunca se expone al propio usuario
    // marcado. NO agregar este campo a UsuarioResponse cuando el que pide
    // sus propios datos es un USUARIO llano, ni al JWT -- ver
    // AsignacionService.actualizarEstado para donde efectivamente se usa.
    @Column(name = "requiere_supervision")
    private boolean requiereSupervision;
}
