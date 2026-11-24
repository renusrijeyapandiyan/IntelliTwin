package com.intellitwin.config;

import com.intellitwin.auth.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SecurityFilterChain chain(HttpSecurity http, JwtService jwt, AppProperties props) throws Exception {
        http.csrf(c -> c.disable())                       // stateless token API, no cookies
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/auth/login", "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/error").permitAll()          // Spring Security 6 also guards the ERROR dispatch; without this a 404/500 would show as 401
                .requestMatchers(HttpMethod.POST, "/api/sensors/ingest").hasAuthority("INGEST")
                .requestMatchers(HttpMethod.POST, "/api/machines").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/maintenance").hasAnyRole("ADMIN", "ENGINEER")
                .requestMatchers(HttpMethod.POST, "/api/models/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/models/**", "/api/audit/**").hasAnyRole("ADMIN", "ENGINEER")
                .requestMatchers("/api/users", "/api/users/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/sim/**").hasAnyRole("ADMIN", "ENGINEER")
                .requestMatchers(HttpMethod.DELETE, "/api/sim/**").hasAnyRole("ADMIN", "ENGINEER")
                .requestMatchers("/api/**").hasAnyRole("ADMIN", "ENGINEER", "OPERATOR")
                .anyRequest().denyAll())
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(new ApiKeyFilter(props.getIngestApiKey()), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new JwtFilter(jwt), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        List<String> origins = Arrays.stream(props.getCorsOrigins().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        cfg.setAllowedOrigins(origins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", cfg);
        return src;
    }

    /** Machine-to-machine auth for the simulator: X-API-Key on POST /api/sensors/ingest only. */
    static final class ApiKeyFilter extends OncePerRequestFilter {
        private final byte[] key;

        ApiKeyFilter(String key) { this.key = key.getBytes(StandardCharsets.UTF_8); }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            if ("POST".equals(req.getMethod()) && "/api/sensors/ingest".equals(req.getRequestURI())) {
                String given = req.getHeader("X-API-Key");
                if (given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), key)) {
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                            "simulator", null, List.of(new SimpleGrantedAuthority("INGEST"))));
                }
            }
            chain.doFilter(req, res);
        }
    }

    /** Bearer-token authentication for people. */
    static final class JwtFilter extends OncePerRequestFilter {
        private final JwtService jwt;

        JwtFilter(JwtService jwt) { this.jwt = jwt; }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            String h = req.getHeader("Authorization");
            if (h != null && h.startsWith("Bearer ") && SecurityContextHolder.getContext().getAuthentication() == null) {
                try {
                    var claims = jwt.parse(h.substring(7));
                    String role = claims.get("role", String.class);
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                            claims.getSubject(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
                } catch (Exception ignored) {
                    // invalid/expired token: leave unauthenticated -> 401
                }
            }
            chain.doFilter(req, res);
        }
    }
}
