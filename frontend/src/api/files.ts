/** A file the server sent as an attachment. */
export interface Download {
  content: Blob;
  /** The name the server gave it, or the fallback when it gave none. */
  fileName: string;
}
