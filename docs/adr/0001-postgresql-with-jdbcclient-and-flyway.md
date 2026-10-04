# PostgreSQL 17 with JdbcClient and Flyway, not JPA or Liquibase

Status: accepted

The data is small (about 48 Systems a year, about 200 Findings per VA Submission) and relational, the audit log needs a database trigger that forbids updates and deletes, and Seam 1 tests never touch repositories, so an ORM buys nothing. We use plain SQL through Spring's JdbcClient with named parameters and constant SQL strings, with the schema owned by Flyway versioned SQL migrations.

Consequences: hand-written row mappers; an architecture test confines JdbcClient to classes named `*Store` and a compiler flag makes string building visible so the "no SQL built at run time" rule (IM8 as-2) can be checked; migrations are the only schema path, so the append-only trigger is changed deliberately (retention) and never by accident.
