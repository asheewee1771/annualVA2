package sg.annualva.export;

import java.util.List;

/**
 * One table of an export, whatever file it is written to: its name, its columns and its rows of cells. A renderer walks
 * it with one loop: the workbook writes a {@link Cell.Count} as a number and a {@link Cell.Text} as a string cell, the
 * PDF writes each as it is {@linkplain Cell#shown() shown}.
 *
 * @param name the sheet's name in the workbook, the heading above it in the PDF
 * @param rows one list of cells per row, one cell per column
 */
record ExportTable(String name, List<Column> columns, List<List<Cell>> rows) {

    ExportTable {
        for (List<Cell> row : rows) {
            if (row.size() != columns.size()) {
                throw new IllegalArgumentException("a row of " + name + " has " + row.size() + " cells for "
                        + columns.size() + " columns");
            }
        }
    }

    /**
     * One column.
     *
     * @param width its width in characters, as the workbook sets it; the PDF shares the page's width out in the same
     *     proportions
     */
    record Column(String header, double width) {}

    /** What one cell holds. */
    sealed interface Cell {

        /** The cell as a person reads it. */
        String shown();

        /** A text, written as a string cell, never a formula. */
        record Text(String value) implements Cell {
            @Override
            public String shown() {
                return value;
            }
        }

        /** A count, written as a number. */
        record Count(long value) implements Cell {
            @Override
            public String shown() {
                return Long.toString(value);
            }
        }
    }

    List<String> headers() {
        return columns.stream().map(Column::header).toList();
    }
}
