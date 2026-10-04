/** IM8 dp-8: the classification banner on every screen, including signed-out and error screens. */
export function ClassificationBanner({ text }: { text: string }) {
  return (
    <div className="classification-banner" role="note" aria-label="Classification">
      {text}
    </div>
  );
}
