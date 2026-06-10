package com.slackmsg.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

/**
 * Base REST client for inter-service communication.
 * Each service creates typed clients extending this.
 * Supports GET, POST, PUT, PATCH, DELETE.
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
        HttpEntity<Object> entity = jsonEntity(body);
        return restTemplate.postForObject(url, entity, responseType);
    }

    protected <T> T put(String path, Object body, Class<T> responseType) {
        String url = baseUrl + path;
        log.debug("Service call: PUT {}", url);
        HttpEntity<Object> entity = jsonEntity(body);
        ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.PUT, entity, responseType);
        return response.getBody();
    }

    protected <T> T patch(String path, Object body, Class<T> responseType) {
        String url = baseUrl + path;
        log.debug("Service call: PATCH {}", url);
        HttpEntity<Object> entity = jsonEntity(body);
        ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.PATCH, entity, responseType);
        return response.getBody();
    }

    protected void delete(String path) {
        String url = baseUrl + path;
        log.debug("Service call: DELETE {}", url);
        restTemplate.delete(url);
    }

    protected <T> T delete(String path, Class<T> responseType) {
        String url = baseUrl + path;
        log.debug("Service call: DELETE {}", url);
        ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.DELETE, null, responseType);
        return response.getBody();
    }

    protected String getBaseUrl() {
        return baseUrl;
    }

    private HttpEntity<Object> jsonEntity(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
