package com.intellitwin.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.intellitwin.config.AppProperties;
import com.intellitwin.core.TopFeature;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** HTTP client for the FastAPI AI service. */
@Service
public class AiClient {
    public record PredictRequest(String machineId, String ts, Map<String, Double> features,
                                 Map<String, Double> baselines, String quality) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Shadow(String modelVersion, double anomalyScore, double failureProbability) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PredictResponse(String machineId, String modelVersion, double anomalyScore, double anomalyRaw,
                                  double failureProbability, double confidence, List<TopFeature> topFeatures,
                                  double inferenceMs, Shadow shadow) {}

    private final RestClient http;

    public AiClient(AppProperties props) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(props.getAi().getTimeoutMs());
        f.setReadTimeout(props.getAi().getTimeoutMs());
        this.http = RestClient.builder().baseUrl(props.getAi().getUrl()).requestFactory(f)
                .defaultHeader("X-API-Key", props.getAi().getApiKey()).build();
    }

    public PredictResponse predict(PredictRequest req) {
        return http.post().uri("/predict").body(req).retrieve().body(PredictResponse.class);
    }

    public Map<String, Object> models() {
        return http.get().uri("/models").retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public void activate(String version) { http.post().uri("/models/{v}/activate", version).retrieve().toBodilessEntity(); }

    public void rollback() { http.post().uri("/models/rollback").retrieve().toBodilessEntity(); }
}
