/** Previous and next page, with where the reader is: the page and the total are announced as they change. */
export function Pager({
  page,
  pages,
  total,
  onPage,
}: {
  page: number;
  pages: number;
  total: number;
  onPage: (page: number) => void;
}) {
  return (
    <div className="pager">
      <button type="button" className="button button--secondary" disabled={page === 0} onClick={() => onPage(page - 1)}>
        Previous page
      </button>
      <span aria-live="polite">
        Page {page + 1} of {pages} ({total} Findings)
      </span>
      <button
        type="button"
        className="button button--secondary"
        disabled={page + 1 >= pages}
        onClick={() => onPage(page + 1)}
      >
        Next page
      </button>
    </div>
  );
}
