import React from 'react';

interface MarkdownViewProps {
  content: string;
}

type Align = 'left' | 'center' | 'right';

function isSeparatorLine(line: string): boolean {
  const trimmed = line.trim();
  if (!trimmed.includes('-')) return false;
  return /^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)+\|?$/.test(trimmed) ||
    /^\|?\s*:?-{2,}:?\s*\|?$/.test(trimmed);
}

function splitTableRow(line: string): string[] {
  let trimmed = line.trim();
  if (trimmed.startsWith('|')) trimmed = trimmed.slice(1);
  if (trimmed.endsWith('|')) trimmed = trimmed.slice(0, -1);
  return trimmed.split('|').map((cell) => cell.trim());
}

function parseAlignments(line: string): Align[] {
  return splitTableRow(line).map((cell) => {
    const left = cell.startsWith(':');
    const right = cell.endsWith(':');
    if (left && right) return 'center';
    if (right) return 'right';
    return 'left';
  });
}

function renderInline(text: string): React.ReactNode[] {
  // Match [label](https://...), `code`, or **bold**
  const tokenRegex = /(\[[^\]]+\]\(https?:\/\/[^\s)]+\)|`[^`]+`|\*\*[\s\S]+?\*\*)/g;
  const parts = text.split(tokenRegex);
  return parts.map((part, index) => {
    if (!part) return null;

    if (part.startsWith('**') && part.endsWith('**') && part.length > 4) {
      return <strong key={index}>{part.slice(2, -2)}</strong>;
    }

    if (part.startsWith('`') && part.endsWith('`') && part.length > 2) {
      return (
        <code
          key={index}
          style={{
            background: '#f1f5f9',
            color: '#0f172a',
            padding: '1px 5px',
            borderRadius: 4,
            fontSize: '0.9em',
            fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
          }}
        >
          {part.slice(1, -1)}
        </code>
      );
    }

    const linkMatch = part.match(/^\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)$/);
    if (linkMatch) {
      return (
        <a
          key={index}
          href={linkMatch[2]}
          target="_blank"
          rel="noopener noreferrer"
          style={{ color: '#2563eb', textDecoration: 'underline' }}
        >
          {linkMatch[1]}
        </a>
      );
    }

    return part;
  });
}

export const MarkdownView: React.FC<MarkdownViewProps> = ({ content }) => {
  const lines = content.replace(/\r\n/g, '\n').split('\n');
  const elements: React.ReactNode[] = [];

  let i = 0;
  while (i < lines.length) {
    const raw = lines[i];
    const trimmed = raw.trim();

    // 1. Code block ```
    if (trimmed.startsWith('```')) {
      const codeLines: string[] = [];
      i++;
      while (i < lines.length && !lines[i].trim().startsWith('```')) {
        codeLines.push(lines[i]);
        i++;
      }
      if (i < lines.length && lines[i].trim().startsWith('```')) {
        i++;
      }
      elements.push(
        <pre
          key={`code-${i}`}
          style={{
            background: '#f8fafc',
            border: '1px solid #e2e8f0',
            borderRadius: 8,
            padding: '10px 12px',
            overflowX: 'auto',
            fontSize: 12,
            lineHeight: 1.5,
            fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
            margin: '8px 0',
          }}
        >
          <code>{codeLines.join('\n')}</code>
        </pre>
      );
      continue;
    }

    // 2. Empty line
    if (!trimmed) {
      elements.push(<div key={`sp-${i}`} style={{ height: 6 }} />);
      i++;
      continue;
    }

    // 3. Horizontal rule (--- / *** / ___)
    if (/^(-{3,}|\*{3,}|_{3,})$/.test(trimmed)) {
      elements.push(
        <hr
          key={`hr-${i}`}
          style={{ border: 'none', borderTop: '1px solid #e2e8f0', margin: '12px 0' }}
        />
      );
      i++;
      continue;
    }

    // 4. Table: current line has pipes and next non-empty line is separator
    if (trimmed.includes('|')) {
      let sepIdx = i + 1;
      while (sepIdx < lines.length && !lines[sepIdx].trim()) {
        sepIdx++;
      }
      if (sepIdx < lines.length && isSeparatorLine(lines[sepIdx])) {
        const headers = splitTableRow(trimmed);
        const aligns = parseAlignments(lines[sepIdx]);
        const bodyRows: string[][] = [];
        let j = sepIdx + 1;

        while (j < lines.length) {
          const rowTrimmed = lines[j].trim();
          if (!rowTrimmed) {
            // Look ahead: Gemini sometimes puts blank lines between table rows
            let lookAhead = j + 1;
            while (lookAhead < lines.length && !lines[lookAhead].trim()) {
              lookAhead++;
            }
            if (lookAhead < lines.length && lines[lookAhead].trim().startsWith('|')) {
              j = lookAhead;
              continue;
            }
            break;
          }
          if (!rowTrimmed.includes('|')) break;
          bodyRows.push(splitTableRow(rowTrimmed));
          j++;
        }

        elements.push(
          <div
            key={`tbl-${i}`}
            style={{
              overflowX: 'auto',
              margin: '10px 0',
              border: '1px solid #e2e8f0',
              borderRadius: 8,
            }}
          >
            <table
              style={{
                width: '100%',
                borderCollapse: 'collapse',
                fontSize: 13,
                lineHeight: 1.5,
              }}
            >
              <thead>
                <tr style={{ background: '#f1f5f9' }}>
                  {headers.map((h, colIdx) => (
                    <th
                      key={colIdx}
                      style={{
                        padding: '8px 10px',
                        textAlign: aligns[colIdx] || 'left',
                        fontWeight: 'bold',
                        color: '#1e293b',
                        borderBottom: '2px solid #cbd5e1',
                        borderRight: colIdx < headers.length - 1 ? '1px solid #e2e8f0' : 'none',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {renderInline(h)}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {bodyRows.map((row, rowIdx) => (
                  <tr
                    key={rowIdx}
                    style={{
                      background: rowIdx % 2 === 1 ? '#f8fafc' : 'white',
                      borderTop: '1px solid #e2e8f0',
                    }}
                  >
                    {headers.map((_, colIdx) => (
                      <td
                        key={colIdx}
                        style={{
                          padding: '7px 10px',
                          textAlign: aligns[colIdx] || 'left',
                          color: '#334155',
                          verticalAlign: 'top',
                          borderRight: colIdx < headers.length - 1 ? '1px solid #e2e8f0' : 'none',
                        }}
                      >
                        {renderInline(row[colIdx] || '')}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        );
        i = j;
        continue;
      }
    }

    // 5. Blockquote
    if (trimmed.startsWith('>')) {
      const quoteLines: string[] = [];
      while (i < lines.length && lines[i].trim().startsWith('>')) {
        quoteLines.push(lines[i].trim().replace(/^>\s?/, ''));
        i++;
      }
      elements.push(
        <blockquote
          key={`bq-${i}`}
          style={{
            margin: '8px 0',
            padding: '6px 12px',
            borderLeft: '3px solid #3b82f6',
            background: '#f8fafc',
            color: '#475569',
            borderRadius: '0 6px 6px 0',
          }}
        >
          {quoteLines.map((ql, qIdx) => (
            <div key={qIdx}>{renderInline(ql)}</div>
          ))}
        </blockquote>
      );
      continue;
    }

    // 6. Headings
    if (trimmed.startsWith('#### ')) {
      elements.push(
        <h4 key={`h4-${i}`} style={{ fontSize: 14, fontWeight: 'bold', margin: '12px 0 4px', color: '#1e293b' }}>
          {renderInline(trimmed.slice(5))}
        </h4>
      );
      i++;
      continue;
    }
    if (trimmed.startsWith('### ')) {
      elements.push(
        <h3 key={`h3-${i}`} style={{ fontSize: 15, fontWeight: 'bold', margin: '14px 0 6px', color: '#111827' }}>
          {renderInline(trimmed.slice(4))}
        </h3>
      );
      i++;
      continue;
    }
    if (trimmed.startsWith('## ')) {
      elements.push(
        <h2
          key={`h2-${i}`}
          style={{
            fontSize: 17,
            fontWeight: 'bold',
            margin: '18px 0 8px',
            color: '#1e3a8a',
            borderBottom: '1px solid #e2e8f0',
            paddingBottom: 4,
          }}
        >
          {renderInline(trimmed.slice(3))}
        </h2>
      );
      i++;
      continue;
    }
    if (trimmed.startsWith('# ')) {
      elements.push(
        <h1 key={`h1-${i}`} style={{ fontSize: 19, fontWeight: 'bold', margin: '20px 0 10px', color: '#1e3a8a' }}>
          {renderInline(trimmed.slice(2))}
        </h1>
      );
      i++;
      continue;
    }

    // 7. Bullet points (support indentation)
    const leadingSpaces = raw.search(/\S|$/);
    if (trimmed.startsWith('- ') || trimmed.startsWith('* ') || trimmed.startsWith('・ ')) {
      const indentPx = 10 + Math.min(36, Math.floor(leadingSpaces / 2) * 14);
      elements.push(
        <div key={`ul-${i}`} style={{ display: 'flex', gap: 6, margin: '3px 0', paddingLeft: indentPx }}>
          <span style={{ color: '#2563eb' }}>{leadingSpaces >= 2 ? '◦' : '•'}</span>
          <span style={{ flex: 1 }}>{renderInline(trimmed.replace(/^[-*・]\s+/, ''))}</span>
        </div>
      );
      i++;
      continue;
    }

    // 8. Numbered list
    const numMatch = trimmed.match(/^(\d+)\.\s+(.*)/);
    if (numMatch) {
      elements.push(
        <div key={`ol-${i}`} style={{ display: 'flex', gap: 6, margin: '4px 0', paddingLeft: 10 }}>
          <span style={{ fontWeight: 'bold', color: '#2563eb', minWidth: 18 }}>{numMatch[1]}.</span>
          <span style={{ flex: 1 }}>{renderInline(numMatch[2])}</span>
        </div>
      );
      i++;
      continue;
    }

    // 9. Regular paragraph
    elements.push(
      <p key={`p-${i}`} style={{ margin: '4px 0' }}>
        {renderInline(trimmed)}
      </p>
    );
    i++;
  }

  return <div style={{ lineHeight: 1.65, fontSize: 14, color: '#1f2937' }}>{elements}</div>;
};
