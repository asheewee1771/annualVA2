import { download } from "./client.ts";
import type { Download } from "./files.ts";

/** The fixed Excel template for the System Register (GET /api/system-register/template, VA POC only). */
export function fetchRegisterTemplate(): Promise<Download> {
  return download("/api/system-register/template", "system-register-template.xlsx");
}
