package sg.annualva.register;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Reads the uploaded System Register workbook into rows of text (story 2). The file is untrusted data (as-1, as-12):
 * its type is decided from its bytes and never from its name or declared type, the archive is walked with a cap on
 * what it unpacks to before a spreadsheet library sees it, formulas are never evaluated, and nothing in the file
 * reaches an exception message or a log line. The header row must be the template's five headers, as written
 * ({@link RegisterTemplate#HEADERS}, ADR 0016), in the first row and in columns A to E.
 *
 * <p>This class only reads; whether a row is acceptable is decided by {@link PreviewBuilder}.
 */
final class RegisterSheetReader {

    /** The most a Register file may weigh (IM8 as-1): about 50 Systems a year, so 2 MB is generous. */
    static final int MAX_BYTES = 2 * 1024 * 1024;
    /** The type handed to the scanner as a hint; the file's type is decided from its bytes, not from this. */
    static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    /** The most System rows in one file. */
    static final int MAX_ROWS = 1000;

    private static final long MAX_UNPACKED_BYTES = 20L * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 200;
    /** Code points beyond this are cut when read; the validator sees a value that is already too long to be valid. */
    private static final int MAX_CELL_CHARS = 1000;
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 3, 4};
    private static final String CONTENT_TYPES = "[Content_Types].xml";
    private static final String WORKBOOK = "xl/workbook.xml";
    private static final String MACROS = "xl/vbaProject.bin";
    private static final int COLUMNS = RegisterTemplate.HEADERS.size();

    private final DataFormatter formatter = new DataFormatter();

    /** @throws RegisterFileException when the bytes are not a Register workbook; never carries file content */
    List<RegisterRow> read(byte[] content) {
        if (content.length == 0) {
            throw new RegisterFileException(UploadRejection.EMPTY);
        }
        if (content.length > MAX_BYTES) {
            throw new RegisterFileException(UploadRejection.TOO_LARGE);
        }
        requireWorkbookArchive(content);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = registerSheet(workbook);
            requireHeader(sheet.getRow(0));
            return dataRows(sheet);
        } catch (RegisterFileException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // The library's message may quote the file; it is dropped here and not logged.
            throw new RegisterFileException(UploadRejection.UNREADABLE);
        }
    }

    /**
     * Sniffs the bytes: a zip archive whose parts include a workbook and no macros, and that unpacks to a sane size,
     * so a zip bomb or a renamed file never reaches the spreadsheet library.
     */
    private static void requireWorkbookArchive(byte[] content) {
        if (content.length < ZIP_MAGIC.length || !startsWith(content, ZIP_MAGIC)) {
            throw new RegisterFileException(UploadRejection.NOT_XLSX);
        }
        boolean contentTypes = false;
        boolean workbook = false;
        boolean macros = false;
        int entries = 0;
        long unpacked = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > MAX_ARCHIVE_ENTRIES) {
                    throw new RegisterFileException(UploadRejection.UNREADABLE);
                }
                String name = entry.getName();
                contentTypes |= CONTENT_TYPES.equals(name);
                workbook |= WORKBOOK.equals(name);
                macros |= MACROS.equalsIgnoreCase(name);
                for (int read = zip.read(buffer); read > 0; read = zip.read(buffer)) {
                    unpacked += read;
                    if (unpacked > MAX_UNPACKED_BYTES) {
                        throw new RegisterFileException(UploadRejection.UNREADABLE);
                    }
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new RegisterFileException(UploadRejection.UNREADABLE);
        }
        if (!contentTypes || !workbook || macros) {
            throw new RegisterFileException(UploadRejection.NOT_XLSX);
        }
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (content[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** The sheet the template names, or the only sheet there is. */
    private static Sheet registerSheet(XSSFWorkbook workbook) {
        Sheet named = workbook.getSheet(RegisterTemplate.SHEET);
        if (named != null) {
            return named;
        }
        if (workbook.getNumberOfSheets() == 1) {
            return workbook.getSheetAt(0);
        }
        throw new RegisterFileException(UploadRejection.NO_SHEET);
    }

    private void requireHeader(Row header) {
        if (header == null) {
            throw new RegisterFileException(UploadRejection.HEADER_MISMATCH, 1);
        }
        for (int column = 0; column < COLUMNS; column++) {
            if (!RegisterTemplate.HEADERS.get(column).equals(text(header.getCell(column)))) {
                throw new RegisterFileException(UploadRejection.HEADER_MISMATCH, column + 1);
            }
        }
        for (Cell extra : header) {
            if (extra.getColumnIndex() >= COLUMNS && !text(extra).isEmpty()) {
                throw new RegisterFileException(UploadRejection.HEADER_MISMATCH, extra.getColumnIndex() + 1);
            }
        }
    }

    private List<RegisterRow> dataRows(Sheet sheet) {
        List<RegisterRow> rows = new ArrayList<>();
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row == null) {
                continue;
            }
            String[] cells = new String[COLUMNS];
            boolean blank = true;
            for (int column = 0; column < COLUMNS; column++) {
                cells[column] = text(row.getCell(column));
                blank &= cells[column].isEmpty();
            }
            if (blank) {
                continue;
            }
            if (rows.size() >= MAX_ROWS) {
                throw new RegisterFileException(UploadRejection.TOO_MANY_ROWS);
            }
            rows.add(new RegisterRow(index + 1, cells[0], cells[1], cells[2], cells[3], cells[4]));
        }
        if (rows.isEmpty()) {
            throw new RegisterFileException(UploadRejection.NO_ROWS);
        }
        return rows;
    }

    /**
     * A cell as a person sees it, trimmed. A formula is never evaluated: its last saved result is read, so the file
     * cannot make the server fetch or compute anything.
     */
    private String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        String value = switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> formatter.formatRawCellContents(cell.getNumericCellValue(),
                    cell.getCellStyle().getDataFormat(), cell.getCellStyle().getDataFormatString());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
        String trimmed = PreviewBuilder.clean(value);
        // Cut on a code-point boundary, so a pair is never split into a lone surrogate.
        return trimmed.codePointCount(0, trimmed.length()) > MAX_CELL_CHARS
                ? trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_CELL_CHARS))
                : trimmed;
    }
}
