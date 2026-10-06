package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.RegisterXlsx;
import tools.jackson.databind.JsonNode;

/**
 * A cell can hold what no JSON document or database column may: a NUL, a lone surrogate, or a pair cut in half by a
 * length limit. Such a row is Blocked like any other bad row, the preview is stored and read back, and none of the cell
 * reaches the log, even through a database error (lm-19).
 */
@ExtendWith(OutputCaptureExtension.class)
class RegisterHostileCellsTest extends RegisterSeam1 {

    private static final String SENTINEL = "CVE-9999-SENTINEL";
    private static final String MANAGER = "hostile.cm@ci.annualva.test";
    private static final String EMOJI = "\uD83D\uDE00";

    @Test
    void aNulOrALoneSurrogateInAnyColumnIsABlockedRowNotAServerError(CapturedOutput output) {
        ApiClient poc = vaPoc();

        var response = upload(poc, RegisterXlsx.register()
                .row("SYS-" + SENTINEL + RegisterXlsx.NUL, "Name" + SENTINEL + RegisterXlsx.LONE_SURROGATE,
                        "smo" + RegisterXlsx.NUL + SENTINEL + "@ci.annualva.test",
                        "cm" + RegisterXlsx.LONE_SURROGATE + SENTINEL + "@ci.annualva.test",
                        "Q" + RegisterXlsx.NUL)
                .row("SYS-OK", "Fine", "fine@ci.annualva.test", MANAGER, "Q1")
                .row("SYS-NAME", "N" + RegisterXlsx.NUL, "n@ci.annualva.test", MANAGER, "Q2")
                .row("SYS-SURR" + RegisterXlsx.LONE_SURROGATE, "Surrogate id", "s@ci.annualva.test", MANAGER, "Q3")
                .build());

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertCleanText(response.body());
        JsonNode preview = ApiClient.json(response);
        JsonNode hostile = preview.get("rows").get(0);
        assertThat(blocked(hostile)).isTrue();
        assertThat(hostile.get("reasons")).isNotEmpty();
        assertThat(blocked(preview.get("rows").get(1))).isFalse();
        assertThat(blocked(preview.get("rows").get(2))).isTrue();
        assertThat(blocked(preview.get("rows").get(3))).isTrue();
        assertThat(preview.get("canApply").asBoolean()).isFalse();

        var readBack = poc.get(PREVIEWS + preview.get("id").asString());
        assertThat(readBack.status()).isEqualTo(200);
        assertCleanText(readBack.body());
        assertThat(ApiClient.json(readBack)).isEqualTo(preview);
        assertThat(output.getAll().toLowerCase()).doesNotContain("sentinel").doesNotContain("cve-9999")
                .doesNotContain("unsupported unicode").doesNotContain("surrogate must follow");
    }

    @Test
    void aLongNameOfEmojiIsClippedWholeAndBlockedNotSplitMidPair(CapturedOutput output) {
        ApiClient poc = vaPoc();

        var response = upload(poc, RegisterXlsx.register()
                .row("SYS-LONG", "N" + EMOJI.repeat(200), "long@ci.annualva.test", MANAGER, "Q1")
                .row("SYS-FIT", EMOJI.repeat(200), "fit@ci.annualva.test", MANAGER, "Q1")
                .row("SYS-HUGE", EMOJI.repeat(900) + SENTINEL, "huge@ci.annualva.test", MANAGER, "Q1")
                .build());

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertCleanText(response.body());
        JsonNode preview = ApiClient.json(response);
        JsonNode tooLong = rowOf(preview, "SYS-LONG");
        assertThat(blocked(tooLong)).as("201 code points is one too many").isTrue();
        assertThat(tooLong.get("reasons").get(0).asString()).isEqualTo("SYSTEM_NAME_INVALID");
        String shown = tooLong.get("after").get("name").asString();
        assertThat(shown.codePointCount(0, shown.length())).isEqualTo(201);
        JsonNode fits = rowOf(preview, "SYS-FIT");
        assertThat(blocked(fits)).as("200 code points of emoji is within the limit").isFalse();
        assertThat(fits.get("after").get("name").asString()).isEqualTo(EMOJI.repeat(200));
        JsonNode huge = rowOf(preview, "SYS-HUGE");
        assertThat(blocked(huge)).isTrue();
        String hugeShown = huge.get("after").get("name").asString();
        assertThat(hugeShown.codePointCount(0, hugeShown.length())).isEqualTo(254);
        assertThat(poc.get(PREVIEWS + preview.get("id").asString()).status()).isEqualTo(200);
        assertThat(output.getAll().toLowerCase()).doesNotContain("sentinel");
    }

    /** Valid text throughout: no NUL, no lone surrogate, and it survives a strict UTF-8 round trip. */
    private static void assertCleanText(String body) {
        assertThat(body).doesNotContain("\u0000");
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertThat(i + 1 < body.length() && Character.isLowSurrogate(body.charAt(i + 1))).as("pair at " + i).isTrue();
                i++;
            } else {
                assertThat(Character.isLowSurrogate(c)).as("lone low surrogate at " + i).isFalse();
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)));
        } catch (CharacterCodingException e) {
            throw new AssertionError("not valid UTF-8", e);
        }
    }
}
