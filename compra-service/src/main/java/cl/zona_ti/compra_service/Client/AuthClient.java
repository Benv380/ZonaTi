package cl.zona_ti.compra_service.Client;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import cl.zona_ti.compra_service.Dto.AuthDto;
import tools.jackson.databind.ObjectMapper;

@Component
public class AuthClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthClient(@Value("${mercado-publico.auth.url}") String baseUrl) {
        // Sin timeouts, una request colgada aca bloqueaba para siempre a
        // TokenCacheService.getToken() (synchronized) -- y con el, a TODOS
        // los hilos del sync de adjuntos que esperan el token. Cliente del
        // JDK (el mismo que usa el builder por defecto, y el que ya usa
        // AdjuntoClient por la renegociacion TLS de Mercado Publico).
        HttpClient jdkHttpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkHttpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(15));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                })
                .build();
    }

    public AuthDto.AuthPublicoPayload obtenerTokenPublico() {
        String raw = restClient.get()
                .uri("/v1/auth/publico")
                .retrieve()
                .body(String.class);

        try {
            AuthDto.AuthPublicoResponse respuesta = objectMapper.readValue(raw, AuthDto.AuthPublicoResponse.class);
            return respuesta.payload();
        } catch (Exception e) {
            throw new RuntimeException("Respuesta inesperada de auth/publico: " + raw, e);
        }
    }
}
