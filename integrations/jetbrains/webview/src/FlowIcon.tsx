// Copyright © 2000–2024 JetBrains s.r.o. Licensed under Apache-2.0.
// Adapted from ToolWindowAskAI for React, scalable sizing, and theme-inherited color.
// See src/main/resources/icons/flow/NOTICE.txt for attribution and license.
export function FlowIcon({ size = 16 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeMiterlimit={10}
      strokeWidth={1.3}
      aria-hidden="true"
      focusable="false"
    >
      <path d="M12.95 15.4a2.949 2.949 0 1 1-5.9 0V4.6a2.949 2.949 0 1 1 5.9 0zm0 0v.01" />
      <path d="M3.85 9.85a2.954 2.954 0 0 1-1.08-4.03A2.954 2.954 0 0 1 6.8 4.74l9.36 5.4c1.41.81 1.89 2.62 1.08 4.03a2.954 2.954 0 0 1-4.03 1.08z" />
      <path d="M13.21 4.75c1.41-.81 3.21-.33 4.03 1.08s.33 3.21-1.08 4.03l-9.36 5.4c-1.41.81-3.21.33-4.03-1.08a2.95 2.95 0 0 1 1.08-4.03z" />
    </svg>
  );
}
