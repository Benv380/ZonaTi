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

// Token opaco (string random, NO un JWT) que vive en la BD -- a
// diferencia del access token (JWT firmado, autocontenido, no se guarda
// en ningun lado), este necesita persistirse para poder revocarlo (logout,
// rotacion en cada refresh). Ver RefreshTokenService.
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User usuario;

    @Column(nullable = false)
    private String token;

    @Column(name = "expira_en", nullable = false)
    private LocalDateTime expiraEn;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private LocalDateTime creadoEn;

    private Boolean revocado = false;
}
