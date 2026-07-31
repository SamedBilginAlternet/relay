import { prettyJson } from '../lib/format';
import { CopyButton } from './CopyButton';

type Props = { title: string; value: unknown };

/** `--bg-subtle` background, JetBrains Mono 13px, no syntax colouring, copy button. */
export function ParamBlock({ title, value }: Props) {
  const json = prettyJson(value);
  return (
    <div className="param-block">
      <div className="param-block__head">
        <span className="t-label">{title}</span>
        <CopyButton text={json} />
      </div>
      <pre className="t-mono" tabIndex={0}>
        {json}
      </pre>
    </div>
  );
}
