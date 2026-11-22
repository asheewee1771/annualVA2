import { allure } from "allure-playwright";
import { assertion, expect, flatText, test } from "../fixtures/test.ts";
import { askForManagementSummary, downloadWorkbook } from "../fixtures/journeys.ts";

const STORY_INTENT =
  "As anyone receiving an export, I want it watermarked with its classification (Confidential when it covers the whole set), so that its handling rules are obvious.";
const AC_DESCRIPTION =
  "AC1: A management summary that covers every System is marked Confidential where it is listed and in the file itself: the page header and footer and the first row of every sheet.";

test(AC_DESCRIPTION, async ({ page, signInAs, story }) => {
  await story("VA POC 1");
  await allure.description(`${STORY_INTENT}\n\n${AC_DESCRIPTION}`);
  await allure.attachment(
    "Test plan",
    [
      "Data:",
      "- The baseline Register of three Systems. The export is asked for as the page offers it: an Excel workbook of the whole VA Cycle and every System, which is the whole set.",
      "- An Excel workbook carries its classification as each sheet's page header and footer and its first row; the diagonal watermark belongs to the PDF and is proved at Seam 1.",
      "Isolation:",
      "- Isolated E2E stack; harness-owned reset before the scenario.",
      "Steps:",
      "1. Sign in as VA POC 1, open Exports and ask for the summary.",
      "2. Wait until it is Ready and read its row.",
      "3. Download it.",
      "4. Open the workbook and, sheet by sheet, read the first row and the page header and footer.",
      "Visible outcomes:",
      "- The export's row says Confidential.",
      "- Every sheet of the workbook has Confidential as its page header, as its page footer and as its first row.",
      "Acceptance mapping:",
      "- AC1 | Story clause: marked Confidential where it is listed | Actor: VA POC 1 | Visible outcome: The export's row says Confidential.",
      "- AC1 | Story clause: in the file itself: the page header and footer and the first row of every sheet | Actor: VA POC 1 | Visible outcome: Every sheet of the workbook has Confidential as its page header, as its page footer and as its first row.",
    ].join("\n"),
    "text/plain",
  );

  await signInAs("VA POC 1");
  const row = await askForManagementSummary(page);
  const classification = row.getByRole("cell", { name: "Confidential", exact: true });
  await row.scrollIntoViewIfNeeded();
  await assertion(
    "Assertion: [AC1] actor: VA POC 1 | the export is listed as Confidential",
    { expected: "Confidential", observed: () => flatText(row) },
    async () => {
      await expect(classification).toBeVisible();
    },
  );

  const { sheets } = await downloadWorkbook(page, row);
  const marks = sheets.map(
    (sheet) =>
      `${sheet.name}: first row "${sheet.firstRow.join(" | ")}", header "${sheet.header}", footer "${sheet.footer}"`,
  );
  await allure.attachment(
    "Classification marks of the downloaded workbook, sheet by sheet",
    marks.join("\n"),
    "text/plain",
  );
  await assertion(
    "Assertion: [AC1] actor: VA POC 1 | every sheet of the workbook is marked Confidential in its first row, header and footer",
    {
      expected: "on each sheet: first row Confidential, header Confidential, footer Confidential",
      observed: marks.join("; "),
    },
    async () => {
      expect(sheets.length).toBeGreaterThan(0);
      for (const sheet of sheets) {
        expect(sheet.firstRow, `first row of ${sheet.name}`).toEqual(["Confidential"]);
        // Excel stores a centred header as "&C" and the text; the footer also carries the page number on the right.
        expect(sheet.header, `page header of ${sheet.name}`).toBe("&CConfidential");
        expect(sheet.footer, `page footer of ${sheet.name}`).toMatch(/^&CConfidential(&R.*)?$/);
      }
    },
  );
});
