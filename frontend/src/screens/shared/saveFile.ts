import type { Download } from "../../api/files.ts";

/** Hands a downloaded file to the browser's own save step, under the name the server gave it. */
export function saveFile(file: Download): void {
  const url = URL.createObjectURL(file.content);
  const link = document.createElement("a");
  link.href = url;
  link.download = file.fileName;
  document.body.append(link);
  link.click();
  link.remove();
  // Revoked on the next turn so the browser has started reading it.
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}
