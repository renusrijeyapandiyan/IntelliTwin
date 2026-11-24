package com.intellitwin.auth;

import com.intellitwin.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final SecretKey key;
    private final long ttlMinutes;

    public JwtService(AppProperties props) {
        this.key = Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
        this.ttlMinutes = props.getJwt().getExpirationMinutes();
    }

    public Instant expiryFromNow() { return Instant.now().plusSeconds(ttlMinutes * 60); }

    public String issue(String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder().subject(username).claim("role", role)
                .issuedAt(Date.from(now)).expiration(Date.from(expiryFromNow()))
                .signWith(key).compact();
    }

    /** @throws io.jsonwebtoken.JwtException if the token is invalid or expired */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
