package cl.zona_ti.compra_service.Client;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import cl.zona_ti.compra_service.Dto.CompraAgilDto.CompraAgilDetalleResponse;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.CompraAgilListadoResponse;

@Component
public class CompraAgilClient {

    private final RestClient restClient;

    public CompraAgilClient(
            @Value("${mercado-publico.compra-agil.url}") String baseUrl,
            @Value("${mercado-publico.compra-agil.ticket}") String ticket) {
        // Sin esto, una request colgada del lado de Mercado Publico se
        // queda esperando PARA SIEMPRE (RestClient sin request factory
        // propia no tiene timeout por defecto). Es lo que causaba el
        // "queda en timeout" al abrir el detalle de un item que solo se
        // habia sincronizado por el listado (sin detalle_completo=true en
        // cache): el front corta a los 15s (ver Front/.../lib/api.js),
        // pero el pedido seguia vivo del lado del backend y a veces
        // terminaba bastante despues -- el segundo click ya encontraba el
        // detalle recien cacheado y andaba al toque. Valores generosos
        // (a diferencia de los clientes internos entre microservicios,
        // que usan 3s/5s) porque esto es una API externa de terceros,
        // puede tardar de verdad.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(20));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("ticket", ticket)
                // La API responde el envelope {success, trace, payload, errors} incluso
                // en 4xx/429 (ver seccion 7 de la doc), asi que no debe lanzar excepcion
                // por status.
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                })
                .build();
    }

    // filtros admitidos (ver doc API Compra Agil v2, seccion 5.1): ttl_cambio_ms,
    // cambio_desde, cambio_hasta, publicado_desde, publicado_hasta, estado, region,
    // id, q, tamano_pagina, numero_pagina, ordenar_por.
    public CompraAgilListadoResponse listar(Map<String, String> filtros) {
        return restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/v2/compra-agil");
                    filtros.forEach(uriBuilder::queryParam);
                    return uriBuilder.build();
                })
                .retrieve()
                .body(CompraAgilListadoResponse.class);
    }

    public CompraAgilDetalleResponse getDetalleByCodigo(String codigo) {
        return restClient.get()
                .uri("/v2/compra-agil/{codigo}", codigo)
                .retrieve()
                .body(CompraAgilDetalleResponse.class);
    }

}
