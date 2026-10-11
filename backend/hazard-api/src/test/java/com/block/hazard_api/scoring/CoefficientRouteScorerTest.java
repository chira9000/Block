package com.block.hazard_api.scoring;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Java v1 scorer must give EXACTLY the model owner's answers on every file in
 * model/export/test_cases/ (we only read those files, never change them).
 */
class CoefficientRouteScorerTest {

    private static final Path EXPORT = Path.of("../../model/export");
    private static final Path TEST_CASES = EXPORT.resolve("test_cases");
    // From model/export/VERIFICATION.md
    private static final String MODEL_SHA256 = "d06a7412a6384b17c75d2561a665cb11ed1e97885a498ef3f9f88e196ff3c390";

    private final JsonMapper json = JsonMapper.builder().build();
    private final CoefficientRouteScorer scorer = new CoefficientRouteScorer();

    /** One test per valid_*.request.json: our response must equal valid_*.expected.json. */
    @TestFactory
    Stream<DynamicTest> validCasesMatchExactly() throws Exception {
        return casesMatching("valid_", ".request.json").map(req -> DynamicTest.dynamicTest(req.getFileName().toString(), () -> {
            Path expectedFile = req.resolveSibling(req.getFileName().toString().replace(".request.json", ".expected.json"));
            ScoreRequest request = json.readValue(Files.readString(req), ScoreRequest.class);
            ScoreResponse expected = json.readValue(Files.readString(expectedFile), ScoreResponse.class);

            ScoreResponse actual = scorer.score(request);

            assertEquals(expected.modelVersion(), actual.modelVersion());
            assertEquals(expected.routes().size(), actual.routes().size());
            for (int i = 0; i < expected.routes().size(); i++) {
                // Records compare every field, and the doubles are already rounded, so this is exact.
                assertEquals(expected.routes().get(i), actual.routes().get(i), "route " + i);
            }
        }));
    }

    /** One test per invalid_*.request.json: it must be rejected, with the same message where we can. */
    @TestFactory
    Stream<DynamicTest> invalidCasesAreRejected() throws Exception {
        return casesMatching("invalid_", ".request.json").map(req -> DynamicTest.dynamicTest(req.getFileName().toString(), () -> {
            Path errorFile = req.resolveSibling(req.getFileName().toString().replace(".request.json", ".expected_error.json"));
            String expectedMessage = json.readTree(Files.readString(errorFile)).get("message").asString();
            String body = Files.readString(req);

            if (req.getFileName().toString().contains("unknown_bucket")) {
                // Our weather_bucket is a Java enum, so an unknown bucket ("hail") can't even be
                // read into a ScoreRequest. Rejected one step earlier than in Python.
                Exception e = assertThrows(Exception.class, () -> json.readValue(body, ScoreRequest.class));
                assertTrue(e.getMessage().contains("hail"), e.getMessage());
                return;
            }
            ScoreRequest request = json.readValue(body, ScoreRequest.class);
            var e = assertThrows(IllegalArgumentException.class, () -> scorer.score(request));
            assertEquals(expectedMessage, e.getMessage());
        }));
    }

    @Test
    void thereAreTenTestCases() throws Exception {
        assertEquals(6, casesMatching("valid_", ".request.json").count());
        assertEquals(4, casesMatching("invalid_", ".request.json").count());
    }

    @Test
    void ourModelCopyIsIdenticalToTheModelOwners() throws Exception {
        byte[] ours = getClass().getResourceAsStream(CoefficientRouteScorer.MODEL_RESOURCE).readAllBytes();
        byte[] theirs = Files.readAllBytes(EXPORT.resolve("v1_model.json"));
        assertArrayEquals(theirs, ours,
                "src/main/resources/model/v1_model.json is out of date: copy model/export/v1_model.json again");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ours));
        assertEquals(MODEL_SHA256, sha);
    }

    @Test
    void shelfLifeTableInterpolatesAndClamps() {
        assertEquals(9.0, scorer.shelfLifeDaysAt(-5), 1e-12);  // below range: hold 0 °C value
        assertEquals(8.0, scorer.shelfLifeDaysAt(2.5), 1e-12); // halfway between 9 (0 °C) and 7 (5 °C)
        assertEquals(5.0, scorer.shelfLifeDaysAt(10), 1e-12);
        assertEquals(1.0 / 3, scorer.shelfLifeDaysAt(40), 1e-12); // above range: hold 30 °C value
    }

    @Test
    void stepsAreFifteenMinutesWithAShorterLastOne() {
        List<double[]> steps = scorer.splitIntoSteps(40);
        assertEquals(3, steps.size());
        assertArrayEquals(new double[] {30, 10}, steps.get(2));
    }

    private static Stream<Path> casesMatching(String prefix, String suffix) throws Exception {
        try (Stream<Path> files = Files.list(TEST_CASES)) {
            List<Path> list = files.filter(p -> p.getFileName().toString().startsWith(prefix)
                            && p.getFileName().toString().endsWith(suffix))
                    .sorted().toList();
            return list.stream();
        }
    }
}
