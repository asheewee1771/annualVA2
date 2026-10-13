/**
 * Quarantine and malware scan (IM8 as-12, story 24), shared by the Scan Report pipeline and the System Register
 * upload: bytes are held in the quarantine zone, submitted to the scanner, and only the scanner's clean copy is read.
 * It is the only module that calls the file-store and scanner ports for uploads. Depends on boundary only.
 */
package sg.annualva.scan;
