package cl.zona_ti.auth_service.Security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
// Habilita @PreAuthorize("hasRole('GLOBAL')") etc. en los controllers
// (EmpresaController, UsuarioController) -- el "rol" que se chequea ahi es
// en realidad el ALCANCE del rol real (ver Alcance.java/JwtAuthenticationFilter),
// no el nombre puntual del rol.
@EnableMethodSecurity
public class SecurityConfig {

    // Factor de costo mas alto que el default (10) -- hace el hash mas lento
    // a proposito, para dificultar ataques de fuerza bruta/rainbow table si
    // la base de datos llegara a filtrarse.
    private static final int BCRYPT_STRENGTH = 12;

    // 1 año, en segundos -- valor recomendado para HSTS.
    private static final long HSTS_MAX_AGE_SECONDS = 31_536_000L;

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // API stateless basada en JWT: no hay sesion de servidor ni
                // cookies, por lo que CSRF (pensado para flujos con cookies)
                // no aplica aca.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/login").permitAll()
                        // Por definicion se llaman SIN un access token
                        // valido (el access token ya vencio, o se esta
                        // cerrando sesion) -- se autentican con el refresh
                        // token de la cookie httpOnly, no con el header
                        // Authorization (ver AuthController).
                        .requestMatchers("/auth/refresh", "/auth/logout").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                // Sin esto, Spring Security usa su entry point default
                // (Http403ForbiddenEntryPoint) para CUALQUIER request sin
                // autenticar -- devuelve 403 tanto si el token vencio como
                // si directamente falta un rol. El front (authFetch, ver
                // lib/api.js) solo intenta renovar el access token cuando
                // ve un 401, asi que con el default nunca se disparaba: el
                // usuario se quedaba pegado con un 403 seco apenas el
                // token de 1h vencia, en vez de renovarse solo. Con esto,
                // "no autenticado" = 401 (dispara el refresh) y "autenticado
                // pero sin el rol que hace falta" sigue siendo 403 (el
                // AccessDeniedHandler default no se toca).
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                // Sin login por formulario ni Basic Auth: el unico mecanismo
                // de autenticacion soportado es el JWT emitido por /auth/login,
                // validado por JwtAuthenticationFilter (ver abajo).
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                // Antes de UsernamePasswordAuthenticationFilter para que el
                // SecurityContext ya tenga la autenticacion resuelta cuando
                // le toca el turno al resto de la cadena.
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .headers(headers -> headers
                        .contentTypeOptions(withDefaults -> {})
                        .frameOptions(frame -> frame.deny())
                        // Fuerza HTTPS en el navegador durante 1 año una vez que
                        // el trafico llega por TLS (terminado en el gateway/proxy
                        // reverso de produccion, que es donde debe vivir el
                        // certificado -- este servicio no expone puerto publico).
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(HSTS_MAX_AGE_SECONDS)));

        return http.build();
    }
}
