import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ImageIcon } from './ui/Icons';

/**
 * Inside the frame. Emails are written for 1999 renderers, so the job here
 * is to get out of the way — no opinionated reset, just a white page, a
 * sane default for the minority of mail that sets no font at all, and a
 * rule stopping an image from bursting the width.
 */
const FRAME_CSS = `
  html { background: #fff; }
  body { margin: 0; padding: 16px 18px; background: #fff; color: #1c1c1e;
         font: 15px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
         word-break: break-word; }
  img { max-width: 100%; height: auto; border: 0; }
  a { color: #1a56c4; }
  table { max-width: 100%; }
`;

function buildDoc(html: string, showImages: boolean): string {
  // The only remote loads possible are the ones named here. Blocking images
  // this way rather than stripping <img> also covers CSS url(), the
  // <td background> attribute, and anything else that reaches for the
  // network — a tag allowlist can only guess at those. This intersects with
  // the page's own CSP, which a srcdoc frame inherits, so it can only ever
  // be stricter than the page.
  const csp = [
    "default-src 'none'",
    `img-src ${showImages ? 'https: data:' : "'none'"}`,
    "style-src 'unsafe-inline'",
    "script-src 'none'",
  ].join('; ');
  return `<!doctype html><html><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="${csp}">
<base target="_blank">
<style>${FRAME_CSS}</style></head>
<body>${html}</body></html>`;
}

/**
 * The original message, rendered the way a mail client has to render one:
 * in its own document, where the email's CSS and ours cannot reach each
 * other.
 *
 * On the sandbox — it grants `allow-same-origin` but NOT `allow-scripts`,
 * which is the safe half of a famously unsafe pair. Nothing inside can
 * execute, so the email cannot act at all; same-origin only lets this
 * component read the frame's layout to size it. Doing the reverse
 * (scripts, no same-origin) was the first attempt and does not work here:
 * a srcdoc frame inherits the page's CSP, and ours is `script-src 'self'`,
 * so an injected measuring script never runs — nonce or no nonce.
 *
 * Newsletters are built as fixed-width tables, 600px usually, and a table
 * cannot reflow below the width of its own content. Clipping that, or
 * making someone drag sideways to read every line, is what the old inline
 * render did. Here it is scaled to fit, which is what a mail client on a
 * phone does.
 */
export function EmailBodyFrame({ html }: { html: string }) {
  const [showImages, setShowImages] = useState(false);
  const [content, setContent] = useState<{ width: number; height: number } | null>(null);
  const [boxWidth, setBoxWidth] = useState(0);
  const boxRef = useRef<HTMLDivElement>(null);
  const frameRef = useRef<HTMLIFrameElement>(null);

  const doc = useMemo(() => buildDoc(html, showImages), [html, showImages]);
  // Worth offering the button only when there is something to load.
  const hasRemoteContent = useMemo(() => /<img\b|url\(/i.test(html), [html]);

  const measure = useCallback(() => {
    const root = frameRef.current?.contentDocument?.documentElement;
    if (!root) return;
    const width = root.scrollWidth;
    const height = root.scrollHeight;
    if (!height) return;
    setContent((prev) => (prev && prev.width === width && prev.height === height ? prev : { width, height }));
  }, []);

  // The frame's content settles after its own styles and (once allowed) its
  // images land, and there is no load event for "finished reflowing".
  useEffect(() => {
    setContent(null);
    const timers = [0, 80, 300, 900].map((t) => window.setTimeout(measure, t));
    return () => timers.forEach(window.clearTimeout);
  }, [doc, measure]);

  useEffect(() => {
    const box = boxRef.current;
    if (!box) return;
    const update = () => setBoxWidth(box.clientWidth);
    update();
    const observer = new ResizeObserver(update);
    observer.observe(box);
    return () => observer.disconnect();
  }, []);

  // Never below 45%: past that it stops being a readable email and becomes a
  // picture of one, so whatever is left over scrolls, as it did before.
  const overflowing = !!content && boxWidth > 0 && content.width > boxWidth + 1;
  const scale = overflowing ? Math.max(0.45, boxWidth / content!.width) : 1;
  const frameWidth = overflowing ? content!.width : boxWidth || undefined;
  const frameHeight = content?.height ?? 360;

  return (
    <div className="overflow-hidden rounded-xl bg-paper shadow-inner ring-1 ring-white/[0.06]">
      {hasRemoteContent && !showImages && (
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-black/[0.07] bg-amber-50 px-4 py-2">
          <span className="text-xs text-neutral-600">
            Images aren't loaded — they can tell the sender you opened this.
          </span>
          <button
            type="button"
            onClick={() => setShowImages(true)}
            className="inline-flex items-center gap-1.5 rounded-md bg-neutral-900 px-2.5 py-1 text-xs font-semibold text-white transition hover:bg-neutral-700"
          >
            <ImageIcon className="h-3.5 w-3.5" />
            Display images
          </button>
        </div>
      )}
      <div ref={boxRef} className="overflow-x-auto" style={{ height: frameHeight * scale }}>
        <iframe
          ref={frameRef}
          title="Original message"
          srcDoc={doc}
          sandbox="allow-same-origin allow-popups allow-popups-to-escape-sandbox"
          scrolling="no"
          onLoad={measure}
          style={{
            width: frameWidth,
            height: frameHeight,
            transform: scale === 1 ? undefined : `scale(${scale})`,
            transformOrigin: 'top left',
            border: 0,
            display: 'block',
          }}
        />
      </div>
    </div>
  );
}
