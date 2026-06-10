package com.slackmsg.client;

import com.slackmsg.util.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Base REST client for inter-service communication.
 * Each service creates typed clients extending this.
 */
@Slf4j
public abstract class ServiceClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    protected ServiceClient(RestTemplate restTemplate, String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    protected <T> T get(String path, Class<T> responseType) {
        String url = baseUrl + path;
        log.debug("Service call: GET {}", url);
        return restTemplate.getForObject(url, responseType);
    }

    protected <T> T get(String path, ParameterizedTypeReference<T> responseType) {
        String url = baseUrl + path;
        log.debug("Service call: GET {}", url);
        ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.GET, null, responseType);
        return response.getBody();
    }

    protected <T> T post(String path, Object body, Class<T> responseType) {
        String url = baseUrl + path;
        log.debug("Service call: POST {}", url);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        return restTemplate.postForObject(url, entity, responseType);
    }

    protected String getBaseUrl() {
        return baseUrl;
    }
}
