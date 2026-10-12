import type { ReactNode } from "react";

/**
 * Renders the small Markdown subset answers use - **bold**, `code`, "- " bullet lines and
 * paragraphs - as React elements. No HTML is ever injected, so tool output cannot inject markup.
 */
const inline = (text: string, keyPrefix: string): ReactNode[] => {
    const parts: ReactNode[] = [];
    const pattern = /(\*\*[^*]+\*\*|`[^`]+`)/g;
    let last = 0;
    let match: RegExpExecArray | null;
    let index = 0;
    while ((match = pattern.exec(text)) !== null) {
        if (match.index > last) parts.push(text.slice(last, match.index));
        const token = match[0];
        parts.push(token.startsWith("**")
            ? <strong key={`${keyPrefix}-${index++}`}>{token.slice(2, -2)}</strong>
            : <code key={`${keyPrefix}-${index++}`}>{token.slice(1, -1)}</code>);
        last = match.index + token.length;
    }
    if (last < text.length) parts.push(text.slice(last));
    return parts;
};

const AnswerText = ({ text }: { text: string }) => {
    const blocks: ReactNode[] = [];
    let bullets: string[] = [];
    const flush = () => {
        if (bullets.length === 0) return;
        const items = bullets;
        blocks.push(<ul key={`ul-${blocks.length}`}>{items.map((b, i) => <li key={i}>{inline(b, `li-${blocks.length}-${i}`)}</li>)}</ul>);
        bullets = [];
    };
    text.split(/\r?\n/).forEach((line, i) => {
        const trimmed = line.trim();
        if (trimmed.startsWith("- ")) {
            bullets.push(trimmed.slice(2));
            return;
        }
        flush();
        if (trimmed) blocks.push(<p key={`p-${i}`}>{inline(trimmed, `p-${i}`)}</p>);
    });
    flush();
    return <div className="answer-text">{blocks}</div>;
};

export default AnswerText;
