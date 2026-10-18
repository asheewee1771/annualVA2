package sg.annualva.finding;

/** Who made a Finding Status proposal. Carry-over (Release 2) adds its own. */
public enum ProposedAs {
    /** The SMO/Tech Lead of the System. */
    SMO,
    /**
     * A rescan (story 39): the file a Finding was read from was replaced and the rescan reported it nowhere, so it is
     * proposed Remediated with the rescan as evidence, for the Cyber Manager to review like any proposal.
     */
    RESCAN
}
