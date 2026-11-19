import type { ReactNode } from "react";

export function MessageAuthorHeading({ children }: { children: string }) {
  return <h3 className="sr-only select-none">{children}</h3>;
}

export function AssistantMessageBody({ children }: { children: ReactNode }) {
  return (
    <>
      <MessageAuthorHeading>T3 Code</MessageAuthorHeading>
      <div data-thread-find-text="true">{children}</div>
    </>
  );
}

export function UserMessageBubble({
  children,
  className = "",
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <div
      className={`relative min-w-0 max-w-[80%] rounded-2xl text-message-foreground ${className}`}
    >
      {children}
    </div>
  );
}

export type WorkLogLineContent = {
  icon?: ReactNode;
  label: ReactNode;
  trailing?: ReactNode;
  wrapLabel?: boolean;
};

export function WorkLogLine({ icon, label, trailing, wrapLabel }: WorkLogLineContent) {
  return (
    <div
      className={`flex min-h-6 min-w-0 gap-1.5 text-sm leading-relaxed select-none [&_*]:select-none ${wrapLabel ? "items-start" : "items-center"}`}
      data-work-log-line
    >
      <div className="flex min-w-0 flex-1 items-baseline gap-1.5">
        {icon ? (
          <span className="relative w-6 shrink-0 leading-0" data-work-log-icon>
            <span className="inline-block size-6 overflow-hidden align-[calc(0.5cap_-_0.75rem)]">
              <span className="flex size-full items-center justify-center">{icon}</span>
            </span>
          </span>
        ) : null}
        <div
          className={`min-w-0 flex-1 text-secondary-label ${wrapLabel ? "" : "truncate [&_*]:whitespace-nowrap"}`}
          data-work-log-label
        >
          {label}
        </div>
      </div>
      {trailing}
    </div>
  );
}
