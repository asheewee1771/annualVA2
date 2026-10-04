# Session limits: 30 minutes idle, 8 hours absolute, one session per user

Status: accepted

The spec (IM8 as-11, Gate-1 approved) sets 30 minutes idle and 8 hours at most; the org SSO standard's default is 15 minutes idle. We follow the spec: SMOs work through 100 to 200 Findings with long reading pauses, and a 15-minute idle timeout would lose bulk-bar work. The compensating controls are step-up before every sensitive action, the 8-hour absolute limit, one concurrent session per user (the newest sign-in evicts the oldest) and `Clear-Site-Data` on sign-out.

Consequences: the deviation from the standard is recorded for the cyber team; the idle value is one configuration line if they insist on 15 minutes.
