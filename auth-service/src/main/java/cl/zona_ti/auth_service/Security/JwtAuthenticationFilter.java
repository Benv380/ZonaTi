package cl.zona_ti.auth_service.Security;

import java.io.IOException;
import java.util.List;

import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import cl.zona_ti.auth_service.Model.Alcance;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Sin esto, "anyRequest().authenticated()" en SecurityConfig no tiene forma
// de autenticar nada (httpBasic/formLogin estan deshabilitados a proposito
// -- el unico mecanismo soportado es JWT), asi que TODO lo que no sea
// /auth/login quedaria inaccesible. Este filtro lee el header
// "Authorization: Bearer <token>", lo valida contra JwtUtil, y si es
// valido arma el SecurityContext con un AuthenticatedPrincipal (username +
// rol + alcance + empresaId, todo ya presente en el token, sin volver a
// pegarle a la BD)

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader(HEADER);

        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length());
            Claims claims = jwtUtil.parseClaims(token);

            // Token invalido/expirado: se deja la request como no
            // autenticada (no se corta aca) -- que decida SecurityConfig
            // segun la ruta (permitAll sigue pasando igual, el resto cae
            // en 403 por "authenticated()")
            if (claims != null) {
                setAuthentication(claims);
            }
        }

        filterChain.doFilter(request, response);
    }

    private void setAuthentication(Claims claims) {
        String username = claims.getSubject();
        String rol = claims.get("role", String.class);
        Alcance alcance = Alcance.valueOf(claims.get("alcance", String.class));

        // Los claims numericos de JJWT pueden llegar como Integer o Long
        // segun el tamaño -- se lee como Object y se convierte a mano en
        // vez de castear directo, para no reventar con ClassCastException.
        Object empresaIdClaim = claims.get("empresaId");
        Long empresaId = empresaIdClaim != null ? Long.valueOf(empresaIdClaim.toString()) : null;

        var principal = new AuthenticatedPrincipal(username, rol, alcance, empresaId);

        // 2 authorities: por alcance (lo que se usa en @PreAuthorize,
        // hasRole('GLOBAL')/hasRole('EMPRESA')/hasRole('USUARIO')) y por
        // nombre de rol especifico, por si hace falta distinguir
        // un nombre de rol puntual en algun endpoint futuro.
        var authorities = List.of(
                new SimpleGrantedAuthority("ROLE_" + alcance.name()),
                new SimpleGrantedAuthority("ROLE_" + rol));

        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
