import { useEffect, useState, type FormEvent } from "react";
import {
  fetchDeclarationVersions,
  MAX_DECLARATION_LENGTH,
  publishDeclarationVersion,
  type DeclarationVersion,
} from "../../api/declarations.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import { isStepUpRequired, takeRestoredDraft } from "../../api/stepUp.ts";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";

const INTENT = "declaration-publish";
const PUBLISH_PROBLEM_ID = "declaration-publish-problem";
/** Times as the rest of the app shows them, in Singapore time. */
const WHEN = new Intl.DateTimeFormat("en-SG", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Singapore" });
const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

function problemOf(failure: unknown): Problem {
  return failure instanceof ApiError ? failure.problem : UNREACHABLE;
}

/** The wording a VA POC was publishing when the server asked for a fresh sign-in; empty otherwise. */
function wordingLeftForSignIn(): string {
  const draft = takeRestoredDraft(INTENT);
  return typeof draft === "object" && draft !== null && "text" in draft && typeof draft.text === "string"
    ? draft.text
    : "";
}

/**
 * Read once as the page loads, as the other step-up forms read theirs: a step-up return is a page load, and the draft is
 * taken before anything renders. Forgotten once the form has used it.
 */
let leftForSignIn = wordingLeftForSignIn();

/**
 * The wording of the next version. Publishing needs a sign-in from the last five minutes: without one the app explains
 * the fresh sign-in, brings the VA POC back here with the wording filled in again, to press Publish again.
 */
function PublishForm({
  classification,
  onPublished,
}: {
  classification: string;
  onPublished: (published: DeclarationVersion) => void;
}) {
  const [text, setText] = useState(() => leftForSignIn);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);
  useEffect(() => {
    leftForSignIn = "";
  }, []);
  const flagged = problem?.field === "text";

  async function publish(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setProblem(undefined);
    try {
      const published = await publishDeclarationVersion(text);
      setText("");
      onPublished(published);
    } catch (failure) {
      const refused = problemOf(failure);
      // The app already explains a step-up and brings the VA POC back here with the wording filled in.
      if (!isStepUpRequired(refused)) setProblem(refused);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="card" aria-labelledby="declaration-publish-title" onSubmit={(event) => void publish(event)}>
      <h3 id="declaration-publish-title">Publish a new version</h3>
      <p className="muted">
        The wording you publish becomes the next version and is current at once: every SMO/Tech Lead accepts it from
        then on, and one who has the older wording open must read it again and tick it again before submitting. Earlier
        versions, and the wording each VA Submission accepted, stay as they are. Control and formatting characters are
        removed; new lines are kept. You need to have signed in within the last five minutes.
      </p>
      <ClassifiedField label="Declaration wording (required)" classification={classification} id="declaration-wording">
        <textarea
          id="declaration-wording"
          rows={8}
          required
          maxLength={MAX_DECLARATION_LENGTH}
          value={text}
          aria-invalid={flagged}
          aria-describedby={flagged ? PUBLISH_PROBLEM_ID : undefined}
          onChange={(event) => {
            setText(event.target.value);
            setProblem(undefined);
          }}
        />
      </ClassifiedField>
      {problem ? (
        <div id={PUBLISH_PROBLEM_ID}>
          <ErrorPanel problem={problem} />
        </div>
      ) : null}
      <p>
        <button type="submit" className="button" disabled={busy || text.trim() === ""}>
          Publish
        </button>
      </p>
    </form>
  );
}

/** Who published a version and when, or that it came with the app. */
function publishedBy(version: DeclarationVersion): string {
  if (version.publishedAt === null) return "Seeded with the app";
  const when = WHEN.format(new Date(version.publishedAt));
  return `${version.publishedByName ?? "A VA POC"}, ${when}`;
}

function VersionsTable({ versions }: { versions: DeclarationVersion[] }) {
  return (
    <div className="table-scroll">
      <table className="data-table">
        <caption className="visually-hidden">Every version of the Declaration, newest first</caption>
        <thead>
          <tr>
            <th scope="col">Version</th>
            <th scope="col">Wording</th>
            <th scope="col">Published</th>
            <th scope="col">Status</th>
            <th scope="col">VA Submissions that accepted it</th>
          </tr>
        </thead>
        <tbody>
          {versions.map((version) => (
            <tr key={version.version}>
              <td>{version.version}</td>
              <td>
                <p className="declaration-text">{version.text}</p>
              </td>
              <td>{publishedBy(version)}</td>
              <td>
                {version.current ? "Current" : "Superseded"}
                {version.placeholder ? (
                  <>
                    <br />
                    <span className="flag flag--warning">Placeholder wording, not yet agreed</span>
                  </>
                ) : null}
              </td>
              <td>{version.submissionCount}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * The Declaration text as a VA POC manages it (stories 40 and 41, ticket 61): every version with its wording, who
 * published it and when, whether it is current or superseded, and how many VA Submissions accepted it; and the form that
 * publishes the next version. Every wording is shown as text.
 */
export function DeclarationScreen({ classification }: { classification: string }) {
  const versions = useResource(fetchDeclarationVersions);
  const [published, setPublished] = useState<DeclarationVersion | undefined>(undefined);

  return (
    <div className="card">
      <h2>Declaration</h2>
      <PublishForm
        classification={classification}
        onPublished={(version) => {
          setPublished(version);
          versions.reload();
        }}
      />
      <section className="card" aria-labelledby="declaration-versions-title">
        <h3 id="declaration-versions-title">Versions</h3>
        {published ? (
          <p role="status">Version {published.version} is published and is now the current Declaration.</p>
        ) : null}
        {versions.error ? <ErrorPanel problem={versions.error.problem} /> : null}
        {versions.data ? <VersionsTable versions={versions.data} /> : null}
      </section>
    </div>
  );
}
