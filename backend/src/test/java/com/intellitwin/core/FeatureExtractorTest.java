package com.intellitwin.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FeatureExtractorTest {
    private static Reading r(double t, double v, double p, double c, double s) {
        return new Reading("M-1", Instant.EPOCH, 0L, t, v, p, c, s, "RUNNING");
    }

    private static Path golden() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path f = p.resolve("shared/feature_golden.json");
            if (Files.exists(f)) return f;
        }
        throw new IllegalStateException("shared/feature_golden.json not found");
    }

    @SuppressWarnings("unchecked")
    @Test
    void matchesPythonGoldenFixture() throws Exception {
        Map<String, Object> g = (Map<String, Object>) MiniJson.parse(Files.readString(golden()));
        List<Reading> rows = new ArrayList<>();
        for (Object o : (List<Object>) g.get("readings")) {
            Map<String, Object> m = (Map<String, Object>) o;
            rows.add(r((Double) m.get("temperature"), (Double) m.get("vibration"), (Double) m.get("pressure"),
                    (Double) m.get("current"), (Double) m.get("speed")));
        }
        Map<String, Double> f = FeatureExtractor.extract(rows);
        Map<String, Object> expected = (Map<String, Object>) g.get("features");
        assertEquals(FeatureExtractor.featureNames().size(), expected.size());
        for (var e : expected.entrySet()) {
            assertEquals((Double) e.getValue(), f.get(e.getKey()), 1e-9, e.getKey());
        }
    }

    @Test
    void featureNamesMatchSpec() {
        var names = FeatureExtractor.featureNames();
        assertEquals(27, names.size());
        assertEquals("temperature_mean", names.get(0));
        assertEquals("vibration_crest", names.get(26));
    }

    @Test
    void knownValues() {
        List<Reading> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) rows.add(r(10 + i, 3, 5, 2, 100));
        Map<String, Double> f = FeatureExtractor.extract(rows);
        assertEquals(14.5, f.get("temperature_mean"), 1e-12);
        assertEquals(1.0, f.get("temperature_slope"), 1e-12);
        assertEquals(Math.sqrt(8.25), f.get("temperature_std"), 1e-12);
        assertEquals(3.0, f.get("vibration_rms"), 1e-12);
        assertEquals(1.0, f.get("vibration_crest"), 1e-12);
    }

    @Test
    void crestFactorRisesWithImpulse() {
        List<Reading> calm = new ArrayList<>(), spiky = new ArrayList<>();
        for (int i = 0; i < 20; i++) { calm.add(r(1, 2, 1, 1, 1)); spiky.add(r(1, i == 19 ? 8 : 2, 1, 1, 1)); }
        assertTrue(FeatureExtractor.extract(spiky).get("vibration_crest") > 2 * FeatureExtractor.extract(calm).get("vibration_crest"));
    }

    @Test
    void tooFewSamplesThrowsAndOnlyLastWindowUsed() {
        List<Reading> few = new ArrayList<>();
        for (int i = 0; i < Sensors.MIN_SAMPLES - 1; i++) few.add(r(1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> FeatureExtractor.extract(few));
        List<Reading> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) many.add(r(1000, 1, 1, 1, 1));
        for (int i = 0; i < 30; i++) many.add(r(5, 1, 1, 1, 1));
        assertEquals(5.0, FeatureExtractor.extract(many).get("temperature_mean"), 1e-12);
    }
}
