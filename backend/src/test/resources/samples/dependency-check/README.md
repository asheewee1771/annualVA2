# Dependency-Check sample exports

`dependency-check-report.xml` and `dependency-check-report.json` are schema-faithful reconstructions of an OWASP
Dependency-Check 9.x report for a throwaway project, written by hand for the parser tests. They are not the output of a real
scan, and every name in them is invented. Regenerate both from a real scan of a throwaway target before release (ticket 58).
The two describe the same findings, so a test can show both formats read to the same result.
