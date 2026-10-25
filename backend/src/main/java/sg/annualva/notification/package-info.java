/**
 * The notifications (stories 92 and 69, ADR 0043): what the workflow, the Overdue sweep and the System Register did,
 * told to whoever acts next once it has committed, and each person's in-app list ({@code GET /api/notifications},
 * {@code POST /api/notifications/{id}/read}). {@link sg.annualva.notification.Notifications} is the one port every
 * sender uses: a {@link sg.annualva.notification.Notice} holds only the System, the state, the action, the due date and a
 * link, so no Finding detail can reach a notification. Items for the VA POCs go to both as a pair that clears for both,
 * marked "handled by (name)", once either makes the decision it awaits. A required message also goes out by email: it is
 * queued with the notification and sent through the mail-relay port by {@link sg.annualva.notification.MailOutbox} with
 * allow-listed values only (ADR 0046). Every morning {@link sg.annualva.notification.DeadlineReminders} reminds whoever's
 * step it is of their deadline, and copies the weekly Overdue reminder to the next person up (ADR 0051). It sits above
 * the modules it listens to and nothing reads it. Depends on access, boundary, cycle, register, submission and web only.
 */
package sg.annualva.notification;
