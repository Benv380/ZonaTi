package cl.zona_ti.licitacion_service.Security;

import java.io.IOException;
import java.util.List;

import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import cl.zona_ti.licitacion_service.Model.Alcance;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Copia deliberada de auth-service/Security/JwtAuthenticationFilter.java --
// ver Alcance.java para el porque de la duplicacion. Valida el JWT que ya
// emitio auth-service (nunca emite uno propio) y arma el SecurityContext
// con un AuthenticatedPrincipal para que LicitacionService/CompraAgilService
// puedan filtrar el listado segun el alcance/rol.
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

        Object empresaIdClaim = claims.get("empresaId");
        Long empresaId = empresaIdClaim != null ? Long.valueOf(empresaIdClaim.toString()) : null;

        var principal = new AuthenticatedPrincipal(username, rol, alcance, empresaId);

        var authorities = List.of(
                new SimpleGrantedAuthority("ROLE_" + alcance.name()),
                new SimpleGrantedAuthority("ROLE_" + rol));

        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
