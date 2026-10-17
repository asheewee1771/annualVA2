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
}

export class ApiError extends Error {
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title);
    this.name = "ApiError";
    this.problem = problem;
  }
}

export function problemFrom(status: number, body: unknown): Problem {
  if (typeof body === "object" && body !== null && "title" in body && "status" in body) {
    const candidate = body as Partial<Problem>;
    return {
      type: candidate.type ?? "about:blank",
      title: String(candidate.title),
      status: Number(candidate.status),
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
