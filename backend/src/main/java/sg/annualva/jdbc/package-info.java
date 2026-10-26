/**
 * What every store shares about talking to PostgreSQL through {@code JdbcClient}: how an instant is written and read
 * ({@link sg.annualva.jdbc.Timestamps}). A leaf below every module that has a store; depends on no module.
 */
package sg.annualva.jdbc;
