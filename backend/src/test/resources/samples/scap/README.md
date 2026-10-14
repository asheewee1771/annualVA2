# SCAP sample exports

`cis-cat-xccdf-results.xml` and `openscap-arf.xml` are schema-faithful reconstructions of what CIS-CAT Pro (an XCCDF 1.2
results file) and OpenSCAP (an ARF with two hosts' results) write for a throwaway target, written by hand for the parser
tests. They are not the output of a real scan, and every name in them (hosts, addresses, rule ids) is invented; the hosts are
under the reserved `.invalid` domain and the addresses are in the documentation range 192.0.2.0/24. Regenerate both from a
real scan of a throwaway target before release (ticket 58).

What they hold, so a test can say what it expects:

- `cis-cat-xccdf-results.xml`: one host, eight rule results: two `pass`, one `notapplicable`, one `notchecked`, two `fail`,
  one `error` and one `unknown`. CIS-CAT writes every severity as `unknown`, so every Finding here is the unrated MEDIUM.
- `openscap-arf.xml`: two hosts (`throwaway-host-02` and `throwaway-host-03`), each its own `arf:report`, and a data stream
  collection in `arf:report-requests` that carries the benchmark's rules (with their own severities) and must never be
  read as results.
