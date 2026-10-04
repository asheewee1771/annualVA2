import { Component, type ErrorInfo, type ReactNode } from "react";

interface State {
  failed: boolean;
}

/** Last line of defence: a rendering failure shows a plain message, never a blank page or a stack. */
export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  override state: State = { failed: false };

  static getDerivedStateFromError(): State {
    return { failed: true };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error("Rendering failed", error.name, info.componentStack);
  }

  override render(): ReactNode {
    if (this.state.failed) {
      return (
        <main className="shell-main">
          <div className="error-panel" role="alert">
            <strong>Something went wrong on this page.</strong>
            <p>Reload the page. If it keeps happening, tell the VA POC.</p>
          </div>
        </main>
      );
    }
    return this.props.children;
  }
}
