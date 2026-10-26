/** One page of a list answer, as every paged endpoint returns it (the server's `Page`). */
export interface Page<T> {
  items: T[];
  /** Zero-based. */
  page: number;
  /** The page size asked for; the last page may hold fewer items. */
  size: number;
  /** How many items match in all. */
  total: number;
}
