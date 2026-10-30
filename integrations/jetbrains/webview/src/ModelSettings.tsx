import { useId, useState } from 'react';
import * as Slider from '@radix-ui/react-slider';
import { Check, ChevronRight } from 'lucide-react';
import type { AgentConfigOption } from './bridge';

export const configLabel = (option: AgentConfigOption) =>
  option.kind === 'boolean'
    ? option.currentValue === true
      ? 'On'
      : 'Off'
    : (option.options.find((choice) => choice.value === option.currentValue)?.name ??
      String(option.currentValue));

type SettingsProps = {
  options: AgentConfigOption[];
  disabled: boolean;
  select: (option: AgentConfigOption, value: string | boolean) => void | Promise<void>;
};

function EffortSlider({
  option,
  disabled,
  select,
}: Omit<SettingsProps, 'options'> & { option: AgentConfigOption }) {
  const [preview, setPreview] = useState<number>();
  const confirmed = option.options.findIndex((choice) => choice.value === option.currentValue);
  const index = preview ?? Math.max(0, confirmed);
  const choice = option.options[index];
  const label = preview === undefined ? configLabel(option) : choice.name;
  const unavailable = disabled || option.options.length < 2;
  return (
    <div className="effort-setting" title={option.description}>
      <div className="effort-heading">
        <span>Effort</span>
        <span className="config-value">{label}</span>
      </div>
      <Slider.Root
        className="effort-slider"
        min={0}
        max={Math.max(1, option.options.length - 1)}
        step={1}
        value={[index]}
        disabled={unavailable}
        onValueChange={([value]) => setPreview(value)}
        onValueCommit={async ([value]) => {
          const selected = option.options[value];
          try {
            if (!unavailable && selected && selected.value !== option.currentValue)
              await select(option, selected.value);
          } finally {
            setPreview(undefined);
          }
        }}
        onPointerCancel={() => setPreview(undefined)}
        onKeyDown={(event) => {
          if (event.nativeEvent.isComposing) event.preventDefault();
        }}
      >
        <Slider.Track className="effort-track">
          <Slider.Range className="effort-range" />
          <span className="effort-marks" aria-hidden="true">
            {option.options.map((level) => (
              <span key={level.value} />
            ))}
          </span>
        </Slider.Track>
        <Slider.Thumb
          className="effort-thumb"
          aria-label={option.name}
          aria-description={choice.description || option.description}
          aria-valuetext={label}
          aria-valuemax={option.options.length - 1}
          aria-disabled={unavailable}
        />
      </Slider.Root>
    </div>
  );
}

export function ModelSettings({ options, disabled, select }: SettingsProps) {
  const group = useId();
  return (
    <div className="model-settings" aria-label="Agent model settings">
      {options.map((option) => {
        const speed =
          option.kind === 'select' &&
          option.options.length === 2 &&
          ['speed', 'fast', 'fast_mode', 'service_tier'].includes(option.id.replaceAll('-', '_'));
        const fast = speed
          ? option.options.find((choice) => ['on', 'fast'].includes(choice.value))
          : undefined;
        const standard = speed
          ? option.options.find((choice) =>
              ['off', 'standard', 'normal', 'default'].includes(choice.value),
            )
          : undefined;
        const on =
          option.kind === 'boolean'
            ? option.currentValue === true
            : option.currentValue === fast?.value;
        const effort =
          option.kind === 'select' &&
          option.options.length > 0 &&
          (option.category === 'thought_level' ||
            ['effort', 'reasoning_effort'].includes(option.id));
        return option.kind === 'boolean' || (fast && standard) ? (
          <button
            key={option.id}
            className="config-row"
            role="switch"
            aria-label={option.name}
            aria-checked={on}
            title={option.description}
            disabled={disabled}
            onClick={() =>
              select(option, option.kind === 'boolean' ? !on : (on ? standard! : fast!).value)
            }
          >
            <span>{option.name}</span>
            <span className="config-value">{configLabel(option)}</span>
            <span className="setting-switch" aria-hidden="true" />
          </button>
        ) : effort ? (
          <EffortSlider
            key={JSON.stringify([option.id, option.currentValue, option.options])}
            option={option}
            disabled={disabled}
            select={select}
          />
        ) : (
          <details
            key={option.id}
            name={group}
            className="config-setting"
            onToggle={(event) => {
              if (event.currentTarget.open) {
                const chosen =
                  event.currentTarget.querySelector<HTMLButtonElement>(
                    '[aria-selected="true"]:not(:disabled)',
                  ) ??
                  event.currentTarget.querySelector<HTMLButtonElement>(
                    '[role="option"]:not(:disabled)',
                  );
                chosen?.focus();
                chosen?.scrollIntoView({ block: 'nearest' });
              }
            }}
            onKeyDown={(event) => {
              if (event.nativeEvent.isComposing) return;
              const details = event.currentTarget;
              if ((event.key === 'Escape' || event.key === 'ArrowLeft') && details.open) {
                event.preventDefault();
                event.stopPropagation();
                details.open = false;
                details.querySelector('summary')?.focus();
              } else if (
                details.open &&
                ['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)
              ) {
                event.preventDefault();
                const choices = Array.from(
                  details.querySelectorAll<HTMLButtonElement>('[role="option"]:not(:disabled)'),
                );
                const index = choices.indexOf(document.activeElement as HTMLButtonElement);
                const next =
                  event.key === 'Home'
                    ? 0
                    : event.key === 'End'
                      ? choices.length - 1
                      : (index + (event.key === 'ArrowDown' ? 1 : -1) + choices.length) %
                        choices.length;
                choices[next]?.focus();
              }
            }}
          >
            <summary
              className="config-row"
              role="button"
              aria-label={option.name}
              aria-disabled={disabled || !option.options.length}
              tabIndex={disabled || !option.options.length ? -1 : 0}
              title={option.description}
              onClick={(event) => {
                if (disabled || !option.options.length) event.preventDefault();
              }}
            >
              <span>{option.name}</span>
              <span className="config-value">{configLabel(option)}</span>
              <ChevronRight size={14} aria-hidden="true" />
            </summary>
            <div
              className="config-choices"
              role="listbox"
              aria-label={option.name}
              aria-busy={disabled}
              onFocus={(event) => {
                event.currentTarget
                  .querySelectorAll<HTMLButtonElement>('[role="option"]')
                  .forEach((choice) => {
                    choice.tabIndex = choice === document.activeElement ? 0 : -1;
                  });
              }}
            >
              {option.options.map((choice) => (
                <button
                  key={choice.value}
                  className="model-option"
                  role="option"
                  aria-selected={choice.value === option.currentValue}
                  tabIndex={choice.value === option.currentValue ? 0 : -1}
                  title={choice.description}
                  disabled={disabled}
                  onClick={() => select(option, choice.value)}
                >
                  <span className="model-option-text">
                    <span className="model-option-name">{choice.name}</span>
                    {choice.description && (
                      <span className="model-option-detail">{choice.description}</span>
                    )}
                  </span>
                  {choice.value === option.currentValue && <Check size={15} aria-hidden="true" />}
                </button>
              ))}
            </div>
          </details>
        );
      })}
    </div>
  );
}
