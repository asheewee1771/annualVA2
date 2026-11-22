/**
 * The key signals an operator watches (IM8 lm-16, ADR 0060): one place ({@link sg.annualva.monitoring.KeySignals}) names
 * every meter and registers it at start, so each is on the scrape at zero before anything happens. A meter is a count or a
 * gauge with a fixed tag from a closed set; nothing taken from a report, a request or a person can be a name or a tag
 * (lm-19). Depends on no other module, so any module may record a signal.
 */
package sg.annualva.monitoring;
