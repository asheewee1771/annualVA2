package sg.annualva.web;

import java.util.List;

/**
 * One page of a list answer.
 *
 * @param page zero-based page number
 * @param size the page size asked for (the last page may hold fewer items)
 * @param total how many items match in all
 */
public record Page<T>(List<T> items, int page, int size, long total) {}
