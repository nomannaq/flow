import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { CSSProperties, RefObject } from 'react';

export function useAnchoredPopover<T extends HTMLElement = HTMLButtonElement>(
  open: boolean,
  close: () => void,
  width = 360,
  anchor?: RefObject<T | null>,
) {
  const ownTrigger = useRef<T>(null);
  const trigger = anchor ?? ownTrigger;
  const popup = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState<CSSProperties>();
  useLayoutEffect(() => {
    if (!open || !trigger.current) {
      setPosition(undefined);
      return;
    }
    const button = trigger.current;
    const place = () => {
      const rect = button.getBoundingClientRect();
      const fittedWidth = Math.max(0, Math.min(width, window.innerWidth - 16));
      const above = rect.top - 14;
      const below = window.innerHeight - rect.bottom - 14;
      const upwards = above >= below;
      setPosition({
        left: Math.max(8, Math.min(rect.left, window.innerWidth - fittedWidth - 8)),
        width: fittedWidth,
        maxHeight: Math.max(0, Math.min(480, upwards ? above : below)),
        ...(upwards ? { bottom: window.innerHeight - rect.top + 6 } : { top: rect.bottom + 6 }),
      });
    };
    place();
    const observer = new ResizeObserver(place);
    observer.observe(button.closest('footer') ?? button);
    window.addEventListener('scroll', place, true);
    window.addEventListener('resize', place);
    return () => {
      observer.disconnect();
      window.removeEventListener('scroll', place, true);
      window.removeEventListener('resize', place);
    };
  }, [open, width]);
  useEffect(() => {
    if (!open) return;
    const outside = (event: Event) => {
      const target = event.target as Node;
      if (!trigger.current?.contains(target) && !popup.current?.contains(target)) close();
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('focusin', outside);
    window.addEventListener('blur', close);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('focusin', outside);
      window.removeEventListener('blur', close);
    };
  }, [open, close]);
  return { trigger, popup, position };
}
