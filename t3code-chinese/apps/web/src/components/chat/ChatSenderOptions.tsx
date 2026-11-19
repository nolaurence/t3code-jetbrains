import { useState, type ComponentProps, type ReactNode } from "react";

export const senderRuntimeModes = {
  "approval-required": {
    label: "Supervised",
    description: "Ask before commands and file changes.",
  },
  "auto-accept-edits": {
    label: "Auto-accept edits",
    description: "Auto-approve edits, ask before other actions.",
  },
  auto: {
    label: "Auto",
    description: "Supported providers approve routine actions; others still ask.",
  },
  "full-access": { label: "Full access", description: "Allow commands and edits without prompts." },
} as const;

const senderSegmentClassName =
  "flex h-6 cursor-pointer items-center justify-center gap-1 px-2 text-muted-foreground text-xs outline-none first:rounded-l-md last:rounded-r-md hover:text-foreground data-checked:bg-input/72 data-checked:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset";

type SenderOption = { id: string; label: string; description?: string; icon?: ReactNode };

export function SenderSegmentButton({
  checked,
  ...props
}: ComponentProps<"button"> & { checked: boolean }) {
  return (
    <button
      {...props}
      type="button"
      role="radio"
      aria-checked={checked}
      data-checked={checked || undefined}
      className={senderSegmentClassName}
    />
  );
}

function SenderSegment({
  option,
  checked,
  onChange,
  onPreview,
  iconOnly = false,
}: {
  option: SenderOption;
  checked: boolean;
  onChange: (id: string) => void;
  onPreview?: (option: SenderOption | null) => void;
  iconOnly?: boolean;
}) {
  return (
    <SenderSegmentButton
      checked={checked}
      aria-label={option.label}
      title={option.description ?? option.label}
      onFocus={() => onPreview?.(option)}
      onBlur={() => onPreview?.(null)}
      onPointerEnter={() => onPreview?.(option)}
      onPointerLeave={() => onPreview?.(null)}
      onClick={() => onChange(option.id)}
    >
      {option.icon}
      {iconOnly ? null : option.label}
    </SenderSegmentButton>
  );
}

export function SpeedSegments({
  options,
  value,
  onChange,
}: {
  options: ReadonlyArray<SenderOption>;
  value: string;
  onChange: (id: string) => void;
}) {
  return (
    <div role="radiogroup" aria-label="Service Tier" data-sender-segments>
      {options.map((option) => (
        <SenderSegment
          key={option.id}
          option={option}
          checked={option.id === value}
          onChange={onChange}
        />
      ))}
    </div>
  );
}

export function PermissionModes({
  options,
  value,
  onChange,
}: {
  options: ReadonlyArray<SenderOption>;
  value: string;
  onChange: (id: string) => void;
}) {
  const [preview, setPreview] = useState<SenderOption | null>(null);
  const shown = preview ?? options.find((option) => option.id === value);
  return (
    <div data-composer-permissions>
      <div className="permission-heading">Permissions</div>
      <div
        role="radiogroup"
        aria-label="Permissions"
        data-sender-segments
        onKeyDown={(event) => {
          if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
          const buttons = Array.from(event.currentTarget.querySelectorAll("button"));
          const index = buttons.findIndex((button) => button === document.activeElement);
          buttons[index + (event.key === "ArrowLeft" ? -1 : 1)]?.focus();
          event.preventDefault();
        }}
      >
        {options.map((option) => (
          <SenderSegment
            key={option.id}
            option={option}
            checked={option.id === value}
            onChange={(id) => {
              setPreview(null);
              onChange(id);
            }}
            onPreview={setPreview}
            iconOnly
          />
        ))}
      </div>
      <div className="permission-caption" aria-live="polite">
        <span>{shown?.label}</span>
        <small>{shown?.description}</small>
      </div>
    </div>
  );
}
