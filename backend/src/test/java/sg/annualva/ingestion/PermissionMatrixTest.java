package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.AuditEntries;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import tools.jackson.databind.JsonNode;

/**
 * Ticket 50, IM8 ac-1, ac-2 and lm-4 (implementation plan sections 2.6 and 5): every endpoint against every role, and the
 * step-up list stale and fresh. Each row of {@link #MATRIX} is one endpoint with what each person gets: {@code OK} (the
 * access rules let the request through, whatever the command then answers), 401, 403, 404, or {@code STEP} (403
 * {@code step-up-required} with the row's intent). Every 401 and 403 must be in the audit log as a
 * {@code REQUEST_REFUSED} entry with the request's correlation id, its status and reason, and the person who asked.
 *
 * <p>The people: nobody signed in; SYS-F1's SMO/Tech Lead and Cyber Manager; SYS-F2's (the "other" ones); the first VA
 * POC, signed in 10 minutes ago, so a step-up action asks for a fresh sign-in; and an Auditor (Release 2 builds what an
 * Auditor reads, so in Release 1 the role opens only what every role may read). SYS-F1's VA Submission is In Review with
 * a Bulk-set batch; SYS-F2's is Exempted on a conditional Exemption. Q3 of VA Cycle 2027, 15 September.
 */
class PermissionMatrixTest extends ExemptionSeam1 {

    static final String AUDITOR_EMAIL = "matrix.auditor@ci.annualva.test";

    enum Person { ANONYMOUS, SMO, OTHER_SMO, CM, OTHER_CM, VA_POC, AUDITOR }

    /** What one person gets from one endpoint, as a cell of the matrix writes it. */
    enum Answer {
        /** The access rules let it through, whatever the command then answers (never a server error). */
        OK("OK"),
        UNAUTHENTICATED("401"),
        FORBIDDEN("403"),
        NOT_FOUND("404"),
        /** 403 {@code step-up-required}, with the row's intent. */
        STEP_UP("STEP");

        final String cell;

        Answer(String cell) {
            this.cell = cell;
        }

        static Answer of(String cell) {
            return Arrays.stream(values()).filter(answer -> answer.cell.equals(cell)).findFirst().orElseThrow();
        }
    }

    /** How a request is sent: a multipart upload is an HTTP POST. */
    enum Verb {
        GET, POST, PUT, MULTIPART;

        String http() {
            return this == MULTIPART ? "POST" : name();
        }
    }

    /** One endpoint: its verb and path, the body sent, what each person gets, and its step-up intent if it has one. */
    record Endpoint(Verb verb, String path, @Nullable String body, Map<Person, Answer> answers,
            @Nullable String intent) {

        Endpoint stepUp(String code) {
            return new Endpoint(verb, path, body, answers, code);
        }

        @Override
        public String toString() {
            return verb.http() + " " + path;
        }
    }

    /** Who gets what, written {@code ANONYMOUS SMO OTHER_SMO CM OTHER_CM VA_POC AUDITOR} in that order. */
    static Map<Person, Answer> answers(String row) {
        String[] cells = row.trim().split("\\s+");
        assertThat(cells).hasSize(Person.values().length);
        Map<Person, Answer> answers = new EnumMap<>(Person.class);
        for (Person person : Person.values()) {
            answers.put(person, Answer.of(cells[person.ordinal()]));
        }
        return answers;
    }

    static Endpoint get(String path, String row) {
        return new Endpoint(Verb.GET, path, null, answers(row), null);
    }

    static Endpoint post(String path, @Nullable String body, String row) {
        return new Endpoint(Verb.POST, path, body, answers(row), null);
    }

    static Endpoint put(String path, String body, String row) {
        return new Endpoint(Verb.PUT, path, body, answers(row), null);
    }

    static Endpoint multipart(String path, String row) {
        return new Endpoint(Verb.MULTIPART, path, null, answers(row), null);
    }

    //                                  ANONYMOUS SMO OTHER_SMO CM OTHER_CM VA_POC AUDITOR
    static final String PUBLIC = "      OK        OK  OK        OK OK       OK     OK";
    static final String SIGNED_IN = "   401       OK  OK        OK OK       OK     OK";
    static final String NO_SUCH = "     401       404 404       404 404     404    404";
    static final String SEES_F1 = "     401       OK  404       OK 404      OK     403";
    static final String SMO_F1 = "      401       OK  404       403 403     403    403";
    static final String SMO_F2 = "      401       404 OK        403 403     403    403";
    static final String CM_F1 = "       401       403 403       OK 404      403    403";
    static final String RETURNS_F1 = "  401       403 403       OK 404      STEP   403";
    static final String SMO_ROLE = "    401       OK  OK        403 403     403    403";
    static final String CM_ROLE = "     401       403 403       OK OK       403    403";
    static final String CM_OR_POC = "   401       403 403       OK OK       OK     403";
    static final String PREPARERS = "   401       OK  OK        OK OK       OK     403";
    static final String POC = "         401       403 403       403 403     OK     403";
    static final String POC_STEP = "    401       403 403       403 403     STEP   403";

    static final List<Endpoint> MATRIX = List.of(
            get("/api/session", PUBLIC),
            get("/api/csrf", PUBLIC),
            get("/.well-known/jwks.json", PUBLIC),
            get("/auth/step-up?intent=probe-sensitive", SIGNED_IN),
            get("/api/cycles", SIGNED_IN),
            get("/api/cycles/{year}", SIGNED_IN),
            get("/api/notifications", SIGNED_IN),
            post("/api/notifications/{random}/read", null, NO_SUCH),
            get("/api/declaration-text", PREPARERS),
            get("/api/systems", PREPARERS),
            get("/api/audit-entries", CM_OR_POC),
            get("/api/dashboards/smo-inbox", SMO_ROLE),
            get("/api/dashboards/cm-inbox", CM_ROLE),
            get("/api/dashboards/quarter-board", CM_OR_POC),
            get("/api/dashboards/cycle-matrix", POC),
            get("/api/dashboards/exemption-queue", POC),
            post("/api/submissions", "{\"systemId\":\"{f1system}\"}", SMO_F1),
            get("/api/submissions/{f1}", SEES_F1),
            post("/api/submissions/{f1}/submit", "{\"declarationAccepted\":false}", SMO_F1),
            post("/api/submissions/{f1}/return", "{\"comment\":\"Rework.\"}", RETURNS_F1).stepUp("submission-return"),
            post("/api/submissions/{f1}/pass-to-endorsement", "{}", CM_F1),
            post("/api/submissions/{f1}/endorse", "{}", POC_STEP).stepUp("submission-endorse"),
            get("/api/submissions/{f1}/returns", SEES_F1),
            get("/api/submissions/{f1}/findings", SEES_F1),
            get("/api/submissions/{f1}/readiness", SEES_F1),
            post("/api/submissions/{f1}/findings/bulk-proposals",
                    "{\"findingIds\":[\"{finding}\"],\"status\":\"FALSE_POSITIVE\",\"justification\":\"Not reachable.\"}",
                    SMO_F1),
            post("/api/submissions/{f1}/findings/manual", "{}", SMO_F1),
            get("/api/submissions/{f1}/batches", SEES_F1),
            get("/api/submissions/{f1}/rework-queue", SMO_F1),
            get("/api/submissions/{f1}/reports", SEES_F1),
            put("/api/submissions/{f1}/reports/{type}/mode", "{\"mode\":\"UPLOAD\"}", SMO_F1),
            post("/api/submissions/{f1}/reports/{type}/review",
                    "{\"decision\":\"CONFIRMED\",\"justificationVersion\":\"0\"}", CM_F1),
            multipart("/api/submissions/{f1}/files", SMO_F1),
            get("/api/submissions/{f1}/files", SEES_F1),
            get("/api/submissions/{f1}/files/{file}", SEES_F1),
            multipart("/api/submissions/{f1}/files/{file}/replace", SMO_F1),
            get("/api/submissions/{f1}/files/{file}/metadata", SEES_F1),
            put("/api/submissions/{f1}/files/{file}/metadata", "{\"scanDate\":\"2027-07-20\"}", SMO_F1),
            get("/api/submissions/{f1}/files/{file}/content", SEES_F1),
            get("/api/findings/{finding}/proposals", SEES_F1),
            post("/api/findings/{finding}/proposals", "{\"status\":\"FALSE_POSITIVE\",\"justification\":\"No.\"}",
                    SMO_F1),
            post("/api/findings/{finding}/reviews", "{\"decision\":\"CONFIRMED\",\"proposalId\":\"{random}\"}", CM_F1),
            post("/api/batches/{batch}/reviews", "{\"decision\":\"CONFIRMED\"}", CM_F1),
            post("/api/systems/{f1system}/exemptions", "{}", SMO_F1),
            post("/api/exemptions/{exemption}/approve", "{}", POC_STEP).stepUp("exemption-decide"),
            post("/api/exemptions/{exemption}/reject", "{}", POC_STEP).stepUp("exemption-decide"),
            post("/api/exemptions/{exemption}/va-happened", "{}", SMO_F2),
            post("/api/exemptions/{exemption}/confirm-va", "{}", POC_STEP).stepUp("exemption-decide"),
            post("/api/cycles/{year}/close", "{}", POC_STEP).stepUp("cycle-close"),
            get("/api/system-register/template", POC),
            multipart("/api/system-register/uploads", POC),
            get("/api/system-register/previews/{preview}", POC),
            post("/api/system-register/previews/{random}/apply", "{}", POC_STEP).stepUp("register-apply"),
            get("/api/role-holders", POC),
            post("/api/role-holders/reviews", "{}", POC),
            post("/api/role-holders/{random}/suspend", "{}", POC_STEP).stepUp("role-suspend"),
            post("/api/role-holders/{random}/restore", "{}", POC_STEP).stepUp("role-restore"),
            post("/api/exports", "{\"type\":\"MGMT_SUMMARY\",\"format\":\"XLSX\"}", POC_STEP)
                    .stepUp("export-mgmt-summary"),
            get("/api/exports", POC),
            get("/api/exports/{export}", POC),
            get("/api/exports/{export}/download", POC),
            get("/api/disposal-lists", POC),
            post("/api/disposal-lists/{random}/approve", "{}", POC_STEP).stepUp("disposal-approve"));

    /** The ids the rows' paths name, by placeholder. */
    private final Map<String, String> ids = new HashMap<>();

    /** Every endpoint the application serves, as Spring MVC maps them (class prefixes and every path included). */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    void everyEndpointTheApplicationHasHasARow() {
        Set<String> rows = MATRIX.stream().map(row -> row.verb().http() + " " + template(row.path()))
                .collect(Collectors.toSet());
        Set<String> endpoints = new HashSet<>();
        mappings.getHandlerMethods().forEach((mapping, handler) -> {
            if (handler.getBeanType().getPackageName().startsWith("sg.annualva")
                    && !handler.getBeanType().getPackageName().startsWith("sg.annualva.harness")) {
                for (String pattern : mapping.getPatternValues()) {
                    for (var method : mapping.getMethodsCondition().getMethods()) {
                        endpoints.add(method.name() + " " + template(pattern));
                    }
                }
            }
        });
        assertThat(endpoints).as("Spring MVC lists the endpoints").isNotEmpty();
        assertThat(rows).as("one row per endpoint, and none for an endpoint that does not exist")
                .containsExactlyInAnyOrderElementsOf(endpoints);
    }

    /** The path with every placeholder or path variable written {@code {}}, and no query. */
    private static String template(String path) {
        return path.replaceAll("\\?.*$", "").replaceAll("\\{[^}]+}", "{}");
    }

    @Test
    void everyRoleGetsWhatTheMatrixSaysFromEveryEndpointAndEveryRefusalIsAudited() {
        arrangeTheSystems();
        Map<Person, ApiClient> people = signedIn(Duration.ofMinutes(10));
        Map<Person, String> userIds = new EnumMap<>(Person.class);
        people.forEach((person, client) -> {
            if (person != Person.ANONYMOUS) {
                userIds.put(person, userId(client));
            }
        });

        List<String> wrong = new ArrayList<>();
        Map<String, Refused> refusals = new HashMap<>();
        for (Endpoint endpoint : MATRIX) {
            for (Person person : Person.values()) {
                Answer expected = endpoint.answers().get(person);
                var response = send(people.get(person), endpoint);
                if (!matches(expected, endpoint, response)) {
                    wrong.add(endpoint + " as " + person + ": expected " + expected + ", got " + response.status() + " "
                            + response.body());
                } else if (expected != Answer.OK && expected != Answer.NOT_FOUND) {
                    refusals.put(response.header("x-correlation-id"), new Refused(endpoint + " as " + person,
                            userIds.get(person), expected, endpoint.intent()));
                }
            }
        }
        assertThat(wrong).as("cells that do not match the matrix").isEmpty();

        Map<String, JsonNode> audited = new HashMap<>();
        AuditEntries.all(otherVaPoc(), "action=REQUEST_REFUSED&from=2027-09-15&to=2027-09-15")
                .forEach(entry -> audited.put(entry.get("correlationId").asString(), entry));
        refusals.forEach((correlationId, refused) -> {
            JsonNode entry = audited.get(correlationId);
            assertThat(entry).as("the refusal of " + refused.what() + " is audited").isNotNull();
            assertThat(entry.get("outcome").asString()).isEqualTo("REFUSED");
            assertThat(entry.get("detail").get("status").asInt()).as(refused.what())
                    .isEqualTo(refused.answer() == Answer.UNAUTHENTICATED ? 401 : 403);
            assertThat(entry.get("detail").get("reason").asString()).as(refused.what()).isEqualTo(switch (refused.answer()) {
                case UNAUTHENTICATED -> "unauthenticated";
                case STEP_UP -> "step-up-required";
                default -> "forbidden";
            });
            if (refused.answer() == Answer.STEP_UP) {
                assertThat(entry.get("detail").get("intent").asString()).isEqualTo(refused.intent());
            }
            if (refused.userId() == null) {
                assertThat(entry.get("actorId").isNull()).as(refused.what()).isTrue();
            } else {
                assertThat(entry.get("actorId").asString()).as(refused.what()).isEqualTo(refused.userId());
            }
        });
    }

    private record Refused(String what, @Nullable String userId, Answer answer, @Nullable String intent) {}

    @Test
    void everyStepUpActionAsksForAFreshSignInOnlyWhenTheLastOneIsStale() {
        arrangeTheSystems();
        List<Endpoint> stepUps = MATRIX.stream().filter(endpoint -> endpoint.intent() != null).toList();
        assertThat(stepUps.stream().map(Endpoint::intent).collect(Collectors.toSet())).as("the step-up list (ac-2)")
                .containsExactlyInAnyOrder("submission-endorse", "submission-return", "exemption-decide", "cycle-close",
                        "register-apply", "role-suspend", "role-restore", "export-mgmt-summary", "disposal-approve");

        ApiClient stale = vaPocSignedIn(Duration.ofMinutes(5).plusSeconds(1));
        for (Endpoint endpoint : stepUps) {
            var refused = send(stale, endpoint);
            assertThat(refused.status()).as(endpoint + " with a stale sign-in").isEqualTo(403);
            assertThat(ApiClient.json(refused).get("stepUpPath").asString()).as(endpoint.toString())
                    .isEqualTo("/auth/step-up?intent=" + endpoint.intent());
        }
        ApiClient fresh = vaPoc();
        for (Endpoint endpoint : stepUps) {
            var answered = send(fresh, endpoint);
            assertThat(answered.status() == 403 && answered.body().contains("step-up-required"))
                    .as(endpoint + " with a fresh sign-in: " + answered.status() + " " + answered.body()).isFalse();
            assertThat(answered.status()).as(endpoint + " is let through, and answered").isNotIn(401, 403)
                    .isLessThan(500);
        }
    }

    // ---- arranging ----

    /**
     * On 15 September 2027: SYS-F1 In Review, its Finding entered by hand in a Bulk-set batch; SYS-F2 Exempted on a
     * scheduled VA; a Register preview and a management summary the first VA POC asked for; an Auditor.
     */
    private void arrangeTheSystems() {
        today("2027-09-15");
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        List<String> findings = findingIds(draft.smo(), sub);
        String batch = BatchReviewSeam1.bulkSet(draft, List.of(findings.get(findings.size() - 1)),
                falsePositive("Compensated by SSO."));
        submitted(draft.smo(), sub);
        ApiClient smo = signedInAs(SMO_A);
        ids.put("f1", sub);
        ids.put("f1system", draft.systemId());
        ids.put("finding", findings.get(0));
        ids.put("batch", batch);
        ids.put("file", ApiClient.json(smo.get(filesOf(sub))).get(0).get("id").asString());
        String exemption = requested(signedInAs(SMO_B), idOf("SYS-F2"), scheduledVa()).get("id").asString();
        ApiClient poc = vaPoc();
        approved(poc, exemption);
        ids.put("exemption", exemption);
        ids.put("preview", preview(poc, file(List.of(listed("SYS-F1", "File one", SMO_A, CM, "Q3"),
                listed("SYS-F2", "File two", SMO_B, CM_OTHER, "Q3")))).get("id").asString());
        var export = poc.postJson("/api/exports", "{\"type\":\"MGMT_SUMMARY\",\"format\":\"XLSX\"}");
        assertThat(export.status()).as(export.body()).isEqualTo(202);
        arrange.exports();
        ids.put("export", ApiClient.json(export).get("id").asString());
        ids.put("random", UUID.randomUUID().toString());
        ids.put("year", "2027");
        ids.put("type", BURP_SUITE);
        arrange.grant("AUDITOR", AUDITOR_EMAIL, null, "GRANT", clock.instant().plus(Duration.ofDays(30)).toString());
    }

    /** Everyone signed in now, the first VA POC with a sign-in this old; nobody with a CSRF token only. */
    private Map<Person, ApiClient> signedIn(Duration vaPocSignInAge) {
        Map<Person, ApiClient> people = new EnumMap<>(Person.class);
        people.put(Person.ANONYMOUS, anotherBrowser().withCsrf());
        people.put(Person.SMO, signedInAs(SMO_A));
        people.put(Person.OTHER_SMO, signedInAs(SMO_B));
        people.put(Person.CM, signedInAs(CM));
        people.put(Person.OTHER_CM, signedInAs(CM_OTHER));
        people.put(Person.AUDITOR, signedInAs(AUDITOR_EMAIL));
        people.put(Person.VA_POC, vaPocSignedIn(vaPocSignInAge));
        return people;
    }

    /** The first VA POC, whose identity provider authenticated them this long ago. */
    private ApiClient vaPocSignedIn(Duration age) {
        ApiClient poc = anotherBrowser();
        Instant authTime = clock.instant().minus(age);
        SignIn.signedIn(poc, issuer, TestIdentity.of("af-5001", POC_ONE, "POC One").withAuthTime(authTime));
        return poc.withCsrf();
    }

    // ---- sending ----

    private ApiClient.Response send(ApiClient client, Endpoint endpoint) {
        String path = filled(endpoint.path());
        return switch (endpoint.verb()) {
            case GET -> client.get(path);
            case POST -> client.postJson(path, endpoint.body() == null ? "" : filled(endpoint.body()));
            case PUT -> client.putJson(path, filled(endpoint.body()));
            case MULTIPART -> client.postMultipart(path, Map.of("reportType", DEPENDENCY_CHECK, "kind",
                    MACHINE_READABLE), "file", "file.json", "application/octet-stream",
                    "{}".getBytes(StandardCharsets.UTF_8));
        };
    }

    private String filled(String text) {
        String filled = text;
        for (var id : ids.entrySet()) {
            filled = filled.replace("{" + id.getKey() + "}", id.getValue());
        }
        return filled;
    }

    private static boolean matches(Answer expected, Endpoint endpoint, ApiClient.Response response) {
        int status = response.status();
        return switch (expected) {
            case OK -> status < 500 && status != 401 && status != 403 && status != 404;
            case UNAUTHENTICATED -> status == 401;
            case NOT_FOUND -> status == 404;
            case FORBIDDEN -> status == 403 && type(response).endsWith("/forbidden");
            case STEP_UP -> status == 403 && type(response).endsWith("/step-up-required")
                    && ApiClient.json(response).get("stepUpPath").asString().endsWith("intent=" + endpoint.intent());
        };
    }

    private static String type(ApiClient.Response response) {
        return ApiClient.json(response).path("type").asString("");
    }
}
