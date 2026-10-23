/** RFC 9457 problem details as the backend sends them. Report content never appears in one. */
export interface Problem {
  type: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  traceId?: string;
  /** Only on `step-up-required`: the server path that starts the fresh sign-in. */
  stepUpPath?: string;
  /** Only on `invalid-field`: the name of the request field that is wrong, one of the server's own names. */
  field?: string;
  /** Only on a refusal for several reasons at once (`not-ready` on submit): every reason, in the server's order. */
  reasons?: Reason[];
}

/** A file a reason names: the name the person gave it is text, never markup. */
export interface ReasonFile {
  fileId: string;
  name: string;
  reportType: string;
}

/**
 * One reason a VA Submission cannot be submitted yet: a fixed code and sentence of the server's, the Scan Report it is
 * about (for one that is not provided) and the files it is about.
 */
export interface Reason {
  reason: string;
  message: string;
  reportType: string | null;
  files: ReasonFile[];
}

export class ApiError extends Error {
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title);
    this.name = "ApiError";
    this.problem = problem;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function fileFrom(value: unknown): ReasonFile[] {
  if (!isRecord(value)) return [];
  const { fileId, name, reportType } = value;
  return typeof fileId === "string" && typeof name === "string" && typeof reportType === "string"
    ? [{ fileId, name, reportType }]
    : [];
}

/** Only well-formed reasons are kept; anything else in the list is dropped, never shown. */
function reasonsFrom(value: unknown): Reason[] | undefined {
  if (!Array.isArray(value)) return undefined;
  return value.flatMap((item: unknown) => {
    if (!isRecord(item) || typeof item.reason !== "string" || typeof item.message !== "string") return [];
    return [
      {
        reason: item.reason,
        message: item.message,
        reportType: typeof item.reportType === "string" ? item.reportType : null,
        files: Array.isArray(item.files) ? item.files.flatMap(fileFrom) : [],
      },
    ];
  });
}

/** The reasons of a refusal for several reasons at once, when the body lists any. */
function withReasons(body: object): Pick<Problem, "reasons"> {
  const reasons = reasonsFrom((body as { reasons?: unknown }).reasons);
  return reasons === undefined ? {} : { reasons };
}

export function problemFrom(status: number, body: unknown): Problem {
  if (typeof body === "object" && body !== null && "title" in body && "status" in body) {
    const candidate = body as Partial<Problem>;
    return {
      type: candidate.type ?? "about:blank",
      title: String(candidate.title),
      status: Number(candidate.status),
      ...withReasons(body),
      ...(candidate.detail !== undefined ? { detail: candidate.detail } : {}),
      ...(candidate.instance !== undefined ? { instance: candidate.instance } : {}),
      ...(candidate.traceId !== undefined ? { traceId: candidate.traceId } : {}),
      ...(typeof candidate.field === "string" ? { field: candidate.field } : {}),
      // Only a path of the server's own step-up endpoint is ever followed.
      ...(typeof candidate.stepUpPath === "string" && candidate.stepUpPath.startsWith("/auth/step-up?")
        ? { stepUpPath: candidate.stepUpPath }
        : {}),
    };
  }
  return { type: "about:blank", title: `Request failed (${status})`, status };
}
