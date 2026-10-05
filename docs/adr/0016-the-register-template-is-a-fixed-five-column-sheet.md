# The System Register template is a fixed five-column sheet that the upload will match by header

Status: accepted

`GET /api/system-register/template` (VA POC only) returns `system-register-template.xlsx`, built with Apache POI. It has one sheet, `System Register`, whose first row holds exactly these headers in this order: `System ID`, `System Name`, `SMO/Tech Lead email`, `Cyber Manager email`, `Quarter`. The second row is one example (`EXAMPLE-001`, a name that begins "Example:", two `example.org` addresses, `Q1`), in grey italics, to delete before uploading.

Four decisions follow.

1. **The headers are the contract with the upload.** Ticket 08 reads the file back and matches these header texts as written (`RegisterTemplate.HEADERS`); it does not guess at other spellings. Changing a header is a change to both tickets.
2. **A Quarter is `Q1`, `Q2`, `Q3` or `Q4`.** The Register assigns each System to a Quarter of the VA Cycle it is loaded for; the spec names the column but not its values, and the template is read by people. The upload rejects anything else as a Blocked row (ticket 08). If the cyber team wants a different notation, `RegisterTemplate.QUARTERS` and that parse change together.
3. **Hints, not enforcement.** Each header carries a comment saying what goes there and that it is required; Quarter has a drop-down; both email columns warn on a value with no `@` or with a space; every column is text so a System ID such as `007` keeps its zeros. These help the person in Excel and are not trusted: the upload validates every row again.
4. **No classification row, no second sheet.** The template holds no data and so no classification of its own; a banner row above the headers would move them and break the fixed header row the upload reads. The classification banner is on the Register screen that offers the download.

The download is an audit entry (`REGISTER_TEMPLATE_DOWNLOADED`, no System and no detail) written in the same transaction that builds the file, with the actor and roles from `AuditActor`. It names no System and holds no report content, so it needs no `AccessGuard` check (ADR 0013); the role rule `hasRole('VA_POC')` answers 403 to anyone else, which is itself recorded as a refused request (ADR 0014).

Consequences: the sample row is a trap if it is uploaded unchanged. Ticket 08 should refuse a System ID that starts `EXAMPLE-` as a Blocked row, or at least say so in the preview.
