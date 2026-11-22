package sg.annualva.finding;

/** Who made a Finding Status proposal. Carry-over (Release 2) adds its own. */
public enum ProposedAs {
    /** The SMO/Tech Lead of the System. */
    SMO,
    /**
     * A rescan (story 39): the file a Finding was read from was replaced and the rescan reported it nowhere, so it is
     * proposed Remediated with the rescan as evidence, for the Cyber Manager to review like any proposal.
     */
    RESCAN,
    /**
     * A deleted report file (ticket 60): the SMO/Tech Lead deleted the file a Finding was read from, no other file reports
     * it and it has a proposal history, so it is kept and proposed Remediated as a rescan would, for the Cyber Manager to
     * review like any proposal.
     */
    FILE_DELETED
}
