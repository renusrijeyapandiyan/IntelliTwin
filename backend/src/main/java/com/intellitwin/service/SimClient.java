package com.intellitwin.service;

import com.intellitwin.config.AppProperties;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** Proxy to the simulator control API (demo only; role-checked and audited by the controller). */
@Service
public class SimClient {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private final RestClient http;

    public SimClient(AppProperties props) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(2000);
        f.setReadTimeout(3000);
        RestClient.Builder b = RestClient.builder().baseUrl(props.getSimulator().getUrl()).requestFactory(f);
        if (!props.getSimulator().getControlToken().isBlank()) b.defaultHeader("X-Control-Token", props.getSimulator().getControlToken());
        this.http = b.build();
    }

    public Map<String, Object> status() { return http.get().uri("/status").retrieve().body(MAP); }

    public Map<String, Object> inject(Map<String, Object> body) { return http.post().uri("/faults").body(body).retrieve().body(MAP); }

    public Map<String, Object> clear(String machineId, String fault) {
        return http.delete().uri(u -> {
            var b = u.path("/faults/{m}");
            if (fault != null) b.queryParam("fault", fault);
            return b.build(machineId);
        }).retrieve().body(MAP);
    }

    public Map<String, Object> power(String machineId, String action) {
        return http.post().uri("/machines/{m}/{a}", machineId, action).retrieve().body(MAP);
    }
}
