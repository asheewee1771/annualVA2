package sg.annualva.register;

/**
 * One data row of the uploaded sheet, as text: every cell trimmed, nothing validated yet. {@code sheetRow} is the row
 * number in the spreadsheet (the first data row is 2), so a person can find it.
 */
record RegisterRow(int sheetRow, String systemId, String name, String smoEmail, String cyberManagerEmail, String quarter) {}
