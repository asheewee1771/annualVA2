# Business dates in Asia/Singapore, timestamps in UTC, time only through the Clock bean

Status: accepted

First-Monday Quarter starts, end-of-week-2 and end-of-week-4 deadlines and "the Quarter's last day" are calendar facts in Singapore. Computing them in UTC puts users a day behind between midnight and 08:00 on boundary days. All Quarter, Window and due-date arithmetic uses the configured business zone (`annualva.time-zone`); stored timestamps are UTC; code reads time only through the injected `java.time.Clock`, which an architecture test enforces, so Seam 1 tests can set "week N of Quarter Q" on the controllable clock.

Consequences: scheduled jobs declare the zone; dates in exports and emails are rendered in the business zone; tests express dates as business days, not instants.
