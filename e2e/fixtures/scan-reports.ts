import { lifecycle, shiftDate } from "./stack.ts";

/**
 * Scan Report fixtures for the scenarios. Every name in them is invented (the CVE ids are of the year 2099 on
 * purpose) and nothing is the output of a real scan. They are made when a scenario asks, with the scan dated the day
 * before the app's business date, because the app refuses a report dated in the future and blocks one more than 90
 * days old, so a file with a fixed date would stop working.
 */

export interface FixtureFinding {
  /** What the report calls it; the Findings list shows it as the Finding. */
  identifier: string;
  severity: "CRITICAL" | "HIGH" | "MEDIUM" | "LOW";
  score: number;
  /** The package it is reported in; the list shows it under Package or rule. */
  packageId: string;
}

const ALPHA = "pkg:maven/test.e2e/e2e-alpha@1.0.0";
const BETA = "pkg:maven/test.e2e/e2e-beta@2.3.4";

/** The five Findings of the standard report: two Critical or High, three below. */
export const FIVE_FINDINGS: readonly FixtureFinding[] = [
  { identifier: "CVE-2099-10001", severity: "CRITICAL", score: 9.8, packageId: ALPHA },
  { identifier: "CVE-2099-10002", severity: "HIGH", score: 8.1, packageId: ALPHA },
  { identifier: "CVE-2099-10003", severity: "MEDIUM", score: 5.3, packageId: ALPHA },
  { identifier: "CVE-2099-20001", severity: "MEDIUM", score: 4.3, packageId: BETA },
  { identifier: "CVE-2099-20002", severity: "LOW", score: 3.1, packageId: BETA },
];

/** A report of two Findings, for a scenario that deals with each Finding by hand. */
export const TWO_FINDINGS: readonly FixtureFinding[] = [FIVE_FINDINGS[0]!, FIVE_FINDINGS[3]!];

/** A report long enough that its list of Findings does not fit on one screen: Medium Findings of one package. */
export function manyFindings(count: number): FixtureFinding[] {
  return Array.from({ length: count }, (_, index) => ({
    identifier: `CVE-2099-${30001 + index}`,
    severity: "MEDIUM" as const,
    score: 5.0,
    packageId: ALPHA,
  }));
}

/** An OWASP Dependency-Check JSON report (reportSchema 1.1) of the given Findings, as a file to choose in the upload. */
export function dependencyCheckReport(findings: readonly FixtureFinding[] = FIVE_FINDINGS): {
  name: string;
  mimeType: string;
  buffer: Buffer;
} {
  const scannedOn = shiftDate(lifecycle().baseline.businessDate, -1);
  const packages = [...new Set(findings.map((finding) => finding.packageId))];
  const report = {
    provenance: "Written for the Annual VA browser tests. Not the output of a real scan; every name is invented.",
    reportSchema: "1.1",
    scanInfo: { engineVersion: "9.0.9", dataSource: [{ name: "NVD CVE Checked", timestamp: `${scannedOn}T09:00:00` }] },
    projectInfo: { name: "e2e-throwaway-project", reportDate: `${scannedOn}T10:00:00.000+0800` },
    dependencies: packages.map((packageId) => {
      const jar = `${packageId.slice(packageId.lastIndexOf("/") + 1).replace("@", "-")}.jar`;
      return {
        isVirtual: false,
        fileName: jar,
        filePath: `/opt/e2e/lib/${jar}`,
        packages: [{ id: packageId, confidence: "HIGH" }],
        vulnerabilities: findings
          .filter((finding) => finding.packageId === packageId)
          .map((finding) => ({
            source: "NVD",
            name: finding.identifier,
            severity: finding.severity,
            cvssv3: { baseScore: finding.score, baseSeverity: finding.severity },
          })),
      };
    }),
  };
  return {
    name: "dependency-check-report.json",
    mimeType: "application/json",
    buffer: Buffer.from(JSON.stringify(report), "utf8"),
  };
}
