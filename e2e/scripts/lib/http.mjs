// A small HTTPS client for the lifecycle script: it talks only to this machine (the dev certificates are self-signed,
// so they are not verified, and any host that is not loopback is refused), keeps the session cookie and sends the CSRF
// token the way the app's own client does.
import https from "node:https";
import crypto from "node:crypto";

const LOOPBACK = new Set(["localhost", "127.0.0.1", "[::1]", "::1"]);

function send(url, { method = "GET", headers = {}, body } = {}) {
  const target = new URL(url);
  if (target.protocol !== "https:" || !LOOPBACK.has(target.hostname)) {
    throw new Error(`The harness only talks to this machine over HTTPS, not ${target.origin}`);
  }
  return new Promise((resolve, reject) => {
    const request = https.request(
      target,
      { method, headers, rejectUnauthorized: false, timeout: 15000 },
      (response) => {
        const chunks = [];
        response.on("data", (chunk) => chunks.push(chunk));
        response.on("end", () =>
          resolve({ status: response.statusCode ?? 0, headers: response.headers, body: Buffer.concat(chunks) }),
        );
      },
    );
    request.on("timeout", () => request.destroy(new Error(`${method} ${target.pathname} timed out`)));
    request.on("error", reject);
    if (body) request.write(body);
    request.end();
  });
}

/** One unauthenticated GET; `null` when nothing answers. */
export async function probe(url) {
  try {
    const response = await send(url);
    return { status: response.status, text: response.body.toString("utf8") };
  } catch {
    return null;
  }
}

/** A signed-in conversation with the app, as one browser would have it. */
export class AppSession {
  #base;
  #cookies = new Map();

  constructor(base) {
    this.#base = base;
  }

  async #call(method, path, { headers = {}, body } = {}) {
    const cookie = [...this.#cookies].map(([name, value]) => `${name}=${value}`).join("; ");
    const response = await send(this.#base + path, {
      method,
      headers: { Accept: "application/json", ...(cookie ? { Cookie: cookie } : {}), ...headers },
      body,
    });
    for (const line of response.headers["set-cookie"] ?? []) {
      const [pair] = line.split(";");
      const at = pair.indexOf("=");
      this.#cookies.set(pair.slice(0, at).trim(), pair.slice(at + 1).trim());
    }
    return response;
  }

  async #csrf() {
    const { headerName, token } = this.#answer("GET /api/csrf", await this.#call("GET", "/api/csrf"), 200);
    return { [headerName]: token };
  }

  /**
   * The one contract of every call below: the parsed JSON of the answer (null when it has none), or an error naming the
   * call and the status when the status is not the one expected. With nothing expected, any 2xx will do.
   */
  #answer(what, response, expected) {
    const fine =
      expected === undefined ? response.status >= 200 && response.status < 300 : response.status === expected;
    if (!fine)
      throw new Error(`${what} answered ${response.status}${expected === undefined ? "" : `, not ${expected}`}`);
    return parse(response.body);
  }

  async get(path) {
    return this.#answer(`GET ${path}`, await this.#call("GET", path), 200);
  }

  async post(path, json, expected) {
    const response = await this.#call("POST", path, {
      headers: { ...(await this.#csrf()), "Content-Type": "application/json" },
      body: JSON.stringify(json ?? {}),
    });
    return this.#answer(`POST ${path}`, response, expected);
  }

  async upload(path, fileName, contentType, content, expected) {
    const boundary = `----annualva-e2e-${crypto.randomUUID()}`;
    // Multipart lines end in CR LF, written as escapes: a line break typed into the source would be saved as LF alone.
    const CRLF = "\r\n";
    const head =
      `--${boundary}${CRLF}Content-Disposition: form-data; name="file"; filename="${fileName}"${CRLF}` +
      `Content-Type: ${contentType}${CRLF}${CRLF}`;
    const tail = `${CRLF}--${boundary}--${CRLF}`;
    const body = Buffer.concat([Buffer.from(head, "utf8"), content, Buffer.from(tail, "utf8")]);
    const response = await this.#call("POST", path, {
      headers: {
        ...(await this.#csrf()),
        "Content-Type": `multipart/form-data; boundary=${boundary}`,
        "Content-Length": String(body.length),
      },
      body,
    });
    return this.#answer(`POST ${path}`, response, expected);
  }
}

function parse(buffer) {
  try {
    return JSON.parse(buffer.toString("utf8"));
  } catch {
    return null;
  }
}
