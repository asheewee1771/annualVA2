# Burp Suite sample export

`issues-export.xml` is a schema-faithful reconstruction of what Burp Suite writes (an `issues` XML export, with its own
DOCTYPE of element and attribute declarations) for a scan of a throwaway web application, written by hand for the parser
tests. It is not the output of a real scan, and every name in it (type ids, serial numbers, hosts, addresses, issue texts)
is invented; the hosts end in `.invalid` and the addresses are in the documentation range 192.0.2.0/24. Real Burp exports are
untested until ticket 58: regenerate the file from a real scan of a throwaway target before release.

What it holds, so a test can say what it expects:

- `exportTime` is `Tue Jul 20 10:00:00 SGT 2027` (2027-07-20T02:00:00Z), when the file was exported and not when the scan was
  made.
- Five issues, with their requests and responses (base64, as Burp writes them) and descriptions, which the parser skips.
- Two reflected cross-site scripting issues on `https://throwaway-app.invalid/search`, one in the `q` parameter (Certain) and
  one in `filter` (Firm): one Finding, High, two occurrences, confidence Certain (the most sure of the two).
- A SQL injection on `https://api.throwaway-app.invalid`, path `/v1/items;jsessionid=INVENTED?id=5`, which is keyed on
  `/v1/items` (High, Tentative).
- A cookie without the secure flag at the root of the first host (Low, Firm).
- An email address disclosure (Information, which is Informational; Certain).

So four Findings, in the order the list gives them: the cross-site scripting (High, two places, Certain), the SQL injection
(High, one place, Tentative), the cookie (Low, one place, Firm) and the email address disclosure (Informational, one place,
Certain). The target is the two hosts.
