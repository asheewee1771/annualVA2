# The SMO inbox is a read model over the Register and the calendar, and the server decides what is next

Status: accepted

`GET /api/dashboards/smo-inbox?cycle=` (SMO/Tech Lead only) lists the caller's Systems for one VA Cycle with the state, the three due dates and the next action (stories 18 and 71). No VA Submission exists yet, so it is built from the Register and the calendar alone. Four decisions follow.

1. **A `dashboard` module of read models.** It stores nothing and changes nothing, and sits above `register`, `cycle` and `access` (the architecture test names the arrows). The Register hands it the active Systems the caller may reach for a need (`RegisterSystems.active(Need.SUBMIT)`, filtered by `AccessGuard.scope`, ADR 0013), so the module never reads `va_system` itself and an SMO sees nothing of other people's Systems. The endpoint is `hasRole('SMO')`: a Cyber Manager or VA POC is refused with 403 rather than shown an empty list. A VA POC who is also named as an SMO/Tech Lead passes the role check and sees only the Systems they are SMO for.
2. **The server decides the heading and the next action.** Each item carries `state`, `nextAction`, `nextActionDueOn` and `group` (Needs your action, Waiting on others, Done), so what a person should do is a business rule tested at the API and never worked out in the browser (ADR 0009). Today every System is Not Started, needs the SMO to start it, even before its Quarter begins (an SMO may start early), and is due on the Quarter's `submit_due`. The submission ticket adds the other states to the same three fields.
3. **One Cycle at a time, from the Cycles the app runs.** `cycle` is optional: the Cycle running today, or before the first live Cycle starts, that Cycle. Any other year in `GET /api/cycles` can be asked for (the older Cycle while two overlap); a year the app does not run is 404 and a value that is not a year is 400 without echoing it. Within the Cycle, a System whose Quarter is before the first live Quarter is listed apart under `handledOutsideApp` with its id, name and Quarter only: no state, dates or action, because nothing is expected of it in the app. A retired System is not listed.
4. **Overdue is not guessed.** The Overdue flag is a stored fact set by the sweep in the Overdue ticket (26), not a date comparison made at read time. The inbox response has no `overdue` field yet; that ticket adds it to each item, and the screen already shows the pill when it is true. A Not Started System past its Submission Window is therefore not flagged until then.

Consequences: the inbox is not audited (it names no report or Finding, like the Register list, ADR 0018); a System added partway through a Quarter shows the Quarter's dates until the Release 2 mid-cycle changes give it its own windows; the response is not paged (an SMO holds a handful of Systems).

Amended by ADR 0033: the inbox answers `overdue` on every item, Not Started included, from the flag the sweep stored.

Amended by ADR 0034: the inbox names every state; Awaiting Endorsement and Exemption Requested wait on the VA POCs, and a final state is Done with nothing next.

Amended by ADR 0034 (correction): the inbox lists its Systems through the one dashboard reader, and `nextActionDueOn` is the last day of the window the state waits on by the one Overdue rule (ADR 0033 item 2). A VA Submission Returned for Rework is therefore due on `review_due`, not `submit_due`; ticket 31 (the guided rework queue) may revisit that date, in the rule.
