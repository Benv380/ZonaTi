package cl.zona_ti.auth_service.Security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // Factor de costo mas alto que el default (10) -- hace el hash mas lento
    // a proposito, para dificultar ataques de fuerza bruta/rainbow table si
    // la base de datos llegara a filtrarse.
    private static final int BCRYPT_STRENGTH = 12;

    // 1 año, en segundos -- valor recomendado para HSTS.
    private static final long HSTS_MAX_AGE_SECONDS = 31_536_000L;

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
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                // Sin login por formulario ni Basic Auth: el unico mecanismo
                // de autenticacion soportado es el JWT emitido por /auth/login.
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
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
