# Nessus sample export

`compliance-audit.nessus` is a schema-faithful reconstruction of what Nessus writes (a `NessusClientData_v2` export) for a
scan with a Unix compliance policy of two throwaway hosts, written by hand for the parser tests. It is not the output of a
real scan, and every name in it (hosts, addresses, check ids and names) is invented; the hosts have throwaway names and the
addresses are in the documentation range 192.0.2.0/24. Real Nessus exports are
untested until ticket 58: regenerate the file from a real scan of a throwaway target before release.

What it holds, so a test can say what it expects:

- `throwaway-host-04` (`HOST_END` Tue Jul 20 10:03:44 2027): two items that are not compliance checks (a scan-information
  plugin and a vulnerability), one compliance summary with no result of its own, and five compliance checks: `PASSED`
  (cramfs), `FAILED` (/tmp separate partition, nodev on /tmp), `WARNING` (SSH access) and `ERROR` (/etc/passwd).
- `throwaway-host-05` (`HOST_END` Tue Jul 20 11:20:40 2027, the latest): one scan-information item and six compliance checks:
  `PASSED` (cramfs, nodev on /tmp), `FAILED` (/tmp separate partition), `WARNING` (SSH access, one level worse than on the
  other host), `ERROR` (/etc/passwd, as on the other host) and `ERROR` (IP forwarding).

So five Findings: the /tmp partition (High, two hosts), nodev on /tmp (High, one host), SSH access (High, two hosts), IP
forwarding (Medium, one host, did not complete) and /etc/passwd (Low, two hosts, did not complete).
