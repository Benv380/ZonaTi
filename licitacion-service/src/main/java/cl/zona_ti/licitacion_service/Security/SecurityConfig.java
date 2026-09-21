package cl.zona_ti.licitacion_service.Security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// Antes este servicio estaba en permitAll() total (sin validar nada) --
// eso es lo que hacia posible pedir /compra/licitacion/listar sin login y
// ver TODO. Ahora exige un JWT valido (emitido por auth-service, este
// servicio solo lo valida -- ver JwtUtil/JwtAuthenticationFilter) para
// cualquier endpoint, y ademas filtra el contenido segun el rol dentro de
// LicitacionService/CompraAgilService (un USER solo ve sus asignaciones).
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                // Mismo fix que auth-service (ver el comentario alli): sin
                // esto, Spring Security devuelve 403 para CUALQUIER request
                // sin autenticar (token vencido incluido), y el front solo
                // reintenta renovar la sesion cuando ve un 401 -- con el
                // default, el refresh nunca se disparaba.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
